// Copyright 2026 Crate.io
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

using System.Diagnostics;
using HdrHistogram;
using Npgsql;

/// <summary>
/// Load-generator that connects to a CrateDB cluster over the PostgreSQL
/// wire protocol and runs a configurable mix of queries against climate data.
///
/// Usage:
///   CRATE_USER=user CRATE_PASSWORD=password \
///   dotnet run -- duration-seconds host requests-per-second sslmode [TYPE:COUNT ...]
///
///   Supported query types: WKT, REGION, FTS
///   Example: dotnet run -- 120 myhost 50 disable WKT:100 REGION:50 FTS:30
///   If no TYPE:COUNT args are given, runs WKT queries for the full duration.
///
/// Exit codes:
///   1 — too few command-line arguments (usage error)
///   2 — duration-seconds is not a valid integer
///   3 — requests-per-second is not a valid positive integer
///   4 — CRATE_USER environment variable is missing
///   5 — CRATE_PASSWORD environment variable is missing
///   6 — TYPE:COUNT argument has bad format
///   7 — unknown query type
///   8 — query count is not a valid positive integer
///   9 — query count is not positive
/// </summary>

const int ExitUsage = 1;
const int ExitBadDuration = 2;
const int ExitBadRps = 3;
const int ExitNoUser = 4;
const int ExitNoPassword = 5;
const int ExitBadQuerySpec = 6;
const int ExitUnknownQueryType = 7;
const int ExitBadCount = 8;
const int ExitCountNotPositive = 9;

// Geo-proximity query: finds min/max temperature within 1 degree of a point at a given time.
// The $1/$2 placeholders are bind parameters — Npgsql substitutes actual values at execution time.
// The "::geo_point" is a CrateDB type cast that converts WKT text into a geospatial type.
const string WktSql =
    "SELECT min(data['temperature']) min_t, max(data['temperature']) max_t\n" +
    "FROM demo.climate_data\n" +
    "WHERE distance(geo_location, $1::geo_point) < 1\n" +
    "AND measurement_time = $2";

// Three-table join: climate readings × regions × named points.
// WITHIN() is a CrateDB geo function; the subquery restricts to the latest measurement epoch.
// Subtracting 273.15 converts Kelvin to Celsius.
const string RegionSql =
    "SELECT\n" +
    "  d.measurement_time as time,\n" +
    "  latitude(d.geo_location) as latitude,\n" +
    "  longitude(d.geo_location) as longitude,\n" +
    "  data['temperature'] - 273.15 as temperature,\n" +
    "  gp.nearest_town\n" +
    "FROM\n" +
    "  demo.climate_data d,\n" +
    "  demo.german_regions r,\n" +
    "  demo.geo_points gp\n" +
    "WHERE WITHIN(d.geo_location, r.geo_coords)\n" +
    "AND gp.geo_location = d.geo_location\n" +
    "AND r.region_name = $1\n" +
    "AND d.measurement_time = (SELECT max(d2.measurement_time) FROM demo.climate_data d2)";

// Full-text search using CrateDB's MATCH predicate. _score is a built-in relevance column.
const string FtsSql =
    "SELECT region_name, _score\n" +
    "FROM demo.german_regions\n" +
    "WHERE MATCH(economics, $1)\n" +
    "ORDER BY _score DESC\n" +
    "LIMIT 3";

string[] ftsTerms = ["cars", "trains", "factories", "energy"];
HashSet<string> validQueryTypes = ["WKT", "REGION", "FTS"];

// Populated once at startup from the database and sampled randomly during the workload.
List<(double x, double y)> geoPoints = [];
List<DateTime> timestamps = [];
List<string> regionNames = [];

var rng = new Random();

// --- Parse the four mandatory positional arguments ---

if (args.Length < 4)
{
    PrintUsageAndExit();
    return;
}

if (!long.TryParse(args[0], out var durationSeconds))
{
    Console.Error.WriteLine($"Invalid duration (must be an integer number of seconds): {args[0]}");
    Environment.Exit(ExitBadDuration);
    return;
}

var host = args[1];

if (!int.TryParse(args[2], out var requestsPerSecond))
{
    Console.Error.WriteLine($"Invalid requests-per-second (must be a positive integer): {args[2]}");
    Environment.Exit(ExitBadRps);
    return;
}
if (requestsPerSecond <= 0)
{
    Console.Error.WriteLine("requests-per-second must be > 0");
    Environment.Exit(ExitBadRps);
    return;
}

var sslMode = args[3];

// Credentials from environment variables so they don't appear in shell history.
var user = Environment.GetEnvironmentVariable("CRATE_USER") ?? "";
if (string.IsNullOrEmpty(user))
{
    Console.Error.WriteLine("CRATE_USER environment variable is not set.");
    Environment.Exit(ExitNoUser);
    return;
}

var password = Environment.GetEnvironmentVariable("CRATE_PASSWORD") ?? "";
if (string.IsNullOrEmpty(password))
{
    Console.Error.WriteLine("CRATE_PASSWORD environment variable is not set.");
    Environment.Exit(ExitNoPassword);
    return;
}

// --- Parse optional TYPE:COUNT arguments ---

var queryCounts = new Dictionary<string, int>();
var indefiniteMode = args.Length == 4;

for (var i = 4; i < args.Length; i++)
{
    var parts = args[i].Split(':');
    if (parts.Length != 2)
    {
        Console.Error.WriteLine($"Invalid query spec (expected TYPE:COUNT): {args[i]}");
        Environment.Exit(ExitBadQuerySpec);
        return;
    }

    var type = parts[0].ToUpperInvariant();
    if (!validQueryTypes.Contains(type))
    {
        Console.Error.WriteLine($"Unknown query type: {parts[0]}. Valid types: {string.Join(", ", validQueryTypes)}");
        Environment.Exit(ExitUnknownQueryType);
        return;
    }

    if (!int.TryParse(parts[1], out var count))
    {
        Console.Error.WriteLine($"Invalid count (must be a positive integer): {parts[1]}");
        Environment.Exit(ExitBadCount);
        return;
    }
    if (count <= 0)
    {
        Console.Error.WriteLine($"Count must be > 0: {args[i]}");
        Environment.Exit(ExitCountNotPositive);
        return;
    }

    if (queryCounts.ContainsKey(type))
        queryCounts[type] += count;
    else
        queryCounts[type] = count;
}

// --- Determine which reference data to pre-load ---

var needsWkt = indefiniteMode || queryCounts.ContainsKey("WKT");
var needsRegion = queryCounts.ContainsKey("REGION");

// Build a flat shuffled work list so query types are interleaved randomly.
var workList = new List<string>();
if (!indefiniteMode)
{
    foreach (var (type, count) in queryCounts)
        for (var i = 0; i < count; i++)
            workList.Add(type);

    Shuffle(workList);
}

// CrateDB speaks the PostgreSQL wire protocol; connect via Npgsql.
// Map sslmode values to Npgsql's SslMode enum.
var npgsqlSslMode = sslMode.ToLowerInvariant() switch
{
    "disable" => SslMode.Disable,
    "prefer" => SslMode.Prefer,
    "require" => SslMode.Require,
    _ => SslMode.Prefer,
};

var connString = new NpgsqlConnectionStringBuilder
{
    Host = host,
    Port = 5432,
    Database = "crate",
    Username = user,
    Password = password,
    SslMode = npgsqlSslMode,
}.ConnectionString;

try
{
    await using var conn = new NpgsqlConnection(connString);
    await conn.OpenAsync();

    PrintClusterName(conn);

    if (needsWkt)
    {
        LoadGeoPoints(conn);
        Console.WriteLine($"Loaded {geoPoints.Count} geo points.");
        LoadTimestamps(conn);
        Console.WriteLine($"Loaded {timestamps.Count} timestamps.");
    }
    if (needsRegion)
    {
        LoadRegionNames(conn);
        Console.WriteLine($"Loaded {regionNames.Count} region names: [{string.Join(", ", regionNames)}]");
    }

    RunWorkload(conn, durationSeconds, requestsPerSecond, workList, indefiniteMode);
}
catch (NpgsqlException e)
{
    Console.Error.WriteLine($"Database error during workload execution: {e.Message}");
    Console.Error.WriteLine($"SQLState: {e.SqlState}");
    throw;
}

// --- Helper methods ---

void PrintUsageAndExit()
{
    Console.Error.WriteLine("Usage: QueryCrate <duration-seconds> <host> <requests-per-second> <sslmode> [TYPE:COUNT ...]");
    Console.Error.WriteLine($"  Supported query types: {string.Join(", ", validQueryTypes)}");
    Console.Error.WriteLine("  Example: QueryCrate 120 myhost 50 disable WKT:100 REGION:50");
    Console.Error.WriteLine("  If no TYPE:COUNT args are given, runs WKT queries for the full duration.");
    Environment.Exit(ExitUsage);
}

void PrintClusterName(NpgsqlConnection conn)
{
    using var cmd = new NpgsqlCommand("SELECT name FROM sys.cluster", conn);
    using var reader = cmd.ExecuteReader();
    if (reader.Read())
        Console.WriteLine(reader.GetString(0));
}

void LoadGeoPoints(NpgsqlConnection conn)
{
    using var cmd = new NpgsqlCommand("SELECT geo_location FROM demo.climate_data GROUP BY geo_location", conn);
    using var reader = cmd.ExecuteReader();
    while (reader.Read())
    {
        var value = reader.GetValue(0);
        if (value is DBNull) continue;

        var str = value.ToString() ?? "";
        // CrateDB may return geo_point as "(x,y)" or as a two-element array.
        var cleaned = str.Trim('(', ')', '[', ']');
        var coords = cleaned.Split(',');
        if (coords.Length == 2 &&
            double.TryParse(coords[0].Trim(), out var x) &&
            double.TryParse(coords[1].Trim(), out var y))
        {
            geoPoints.Add((x, y));
        }
    }
}

void LoadTimestamps(NpgsqlConnection conn)
{
    using var cmd = new NpgsqlCommand(
        "SELECT measurement_time FROM demo.climate_data GROUP BY measurement_time ORDER BY measurement_time",
        conn);
    using var reader = cmd.ExecuteReader();
    while (reader.Read())
    {
        if (!reader.IsDBNull(0))
            timestamps.Add(reader.GetDateTime(0));
    }
}

void LoadRegionNames(NpgsqlConnection conn)
{
    using var cmd = new NpgsqlCommand("SELECT region_name FROM demo.german_regions", conn);
    using var reader = cmd.ExecuteReader();
    while (reader.Read())
    {
        if (!reader.IsDBNull(0))
            regionNames.Add(reader.GetString(0));
    }
}

void RunWorkload(NpgsqlConnection conn, long durationSecs, int rps, List<string> work, bool isIndefinite)
{
    if (isIndefinite)
    {
        if (geoPoints.Count == 0) { Console.Error.WriteLine("No geo points loaded; nothing to poll."); return; }
        if (timestamps.Count == 0) { Console.Error.WriteLine("No timestamps loaded; nothing to poll."); return; }
    }

    var histograms = new Dictionary<string, LongHistogram>();
    var deadline = Stopwatch.GetTimestamp() + durationSecs * Stopwatch.Frequency;
    var targetGapMs = 1000.0 / rps;

    if (isIndefinite)
    {
        while (Stopwatch.GetTimestamp() < deadline)
        {
            var start = Stopwatch.GetTimestamp();
            ExecuteWktQuery(conn, histograms);
            Throttle(start, targetGapMs);
        }
    }
    else
    {
        foreach (var queryType in work)
        {
            if (Stopwatch.GetTimestamp() >= deadline)
            {
                Console.WriteLine("Duration limit reached; stopping.");
                break;
            }
            var start = Stopwatch.GetTimestamp();
            switch (queryType)
            {
                case "WKT":
                    if (geoPoints.Count == 0) throw new InvalidOperationException("WKT requested but no geo points loaded");
                    ExecuteWktQuery(conn, histograms);
                    break;
                case "REGION":
                    if (regionNames.Count == 0) throw new InvalidOperationException("REGION requested but no regions loaded");
                    ExecuteRegionQuery(conn, histograms);
                    break;
                case "FTS":
                    ExecuteFtsQuery(conn, histograms);
                    break;
            }
            Throttle(start, targetGapMs);
        }
    }

    PrintHistograms(histograms);
}

void ExecuteWktQuery(NpgsqlConnection conn, Dictionary<string, LongHistogram> histograms)
{
    var sw = Stopwatch.StartNew();
    var point = geoPoints[rng.Next(geoPoints.Count)];
    var ts = timestamps[rng.Next(timestamps.Count)];
    var wkt = $"POINT({point.x} {point.y})";

    using var cmd = new NpgsqlCommand(WktSql, conn);
    cmd.Parameters.AddWithValue(wkt);
    cmd.Parameters.AddWithValue(ts);
    using var reader = cmd.ExecuteReader();
    if (reader.Read())
    {
        sw.Stop();
        RecordLatency(histograms, "WKT", sw.ElapsedMilliseconds);
        Console.WriteLine($"{wkt} @ {ts} -> min={reader.GetValue(0)} max={reader.GetValue(1)}");
    }
}

void ExecuteRegionQuery(NpgsqlConnection conn, Dictionary<string, LongHistogram> histograms)
{
    var sw = Stopwatch.StartNew();
    var region = regionNames[rng.Next(regionNames.Count)];

    using var cmd = new NpgsqlCommand(RegionSql, conn);
    cmd.Parameters.AddWithValue(region);
    using var reader = cmd.ExecuteReader();
    while (reader.Read())
    {
        Console.WriteLine(
            $"region={region} time={reader.GetValue(0)} lat={reader.GetValue(1)} " +
            $"lon={reader.GetValue(2)} temp={reader.GetValue(3)} town={reader.GetValue(4)}");
    }
    sw.Stop();
    RecordLatency(histograms, "REGION", sw.ElapsedMilliseconds);
}

void ExecuteFtsQuery(NpgsqlConnection conn, Dictionary<string, LongHistogram> histograms)
{
    var sw = Stopwatch.StartNew();
    var term = ftsTerms[rng.Next(ftsTerms.Length)];

    using var cmd = new NpgsqlCommand(FtsSql, conn);
    cmd.Parameters.AddWithValue(term);
    using var reader = cmd.ExecuteReader();
    while (reader.Read())
    {
        Console.WriteLine($"FTS '{term}' -> region={reader.GetString(0)} score={reader.GetValue(1)}");
    }
    sw.Stop();
    RecordLatency(histograms, "FTS", sw.ElapsedMilliseconds);
}

void RecordLatency(Dictionary<string, LongHistogram> histograms, string type, long latencyMs)
{
    if (!histograms.ContainsKey(type))
        histograms[type] = new LongHistogram(60_000, 3);
    histograms[type].RecordValue(Math.Max(latencyMs, 0));
}

void PrintHistograms(Dictionary<string, LongHistogram> histograms)
{
    foreach (var (name, h) in histograms)
    {
        Console.WriteLine(
            $"{name}: count={h.TotalCount} avg={h.GetMean():F1}ms " +
            $"p50={h.GetValueAtPercentile(50)}ms " +
            $"p99={h.GetValueAtPercentile(99)}ms " +
            $"p99.9={h.GetValueAtPercentile(99.9)}ms " +
            $"max={h.GetMaxValue()}ms");
    }
}

void Throttle(long startTimestamp, double targetGapMs)
{
    var elapsedMs = (Stopwatch.GetTimestamp() - startTimestamp) * 1000.0 / Stopwatch.Frequency;
    var sleepMs = targetGapMs - elapsedMs;
    if (sleepMs > 0)
        Thread.Sleep((int)sleepMs);
}

void Shuffle<T>(List<T> list)
{
    for (var i = list.Count - 1; i > 0; i--)
    {
        var j = rng.Next(i + 1);
        (list[i], list[j]) = (list[j], list[i]);
    }
}
