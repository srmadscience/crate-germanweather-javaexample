/*
 * Copyright 2026 Crate.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ie.rolfe.crate.iotexample;

import org.HdrHistogram.Histogram;
import org.postgresql.geometric.PGpoint;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Load-generator that connects to a CrateDB cluster over the PostgreSQL
 * wire protocol and runs a configurable mix of queries against climate data.
 *
 * <h2>Usage</h2>
 * <pre>
 *   CRATE_USER=&lt;user&gt; CRATE_PASSWORD=&lt;password&gt; \
 *   java ie.rolfe.crate.iotexample.QueryCrate \
 *       &lt;duration-seconds&gt; &lt;host&gt; &lt;requests-per-second&gt; &lt;sslmode&gt; \
 *       [TYPE:COUNT ...]
 *
 *   Supported query types: WKT, REGION, FTS
 *   Example: QueryCrate 120 myhost 50 disable WKT:100 REGION:50 FTS:30
 *   If no TYPE:COUNT args are given, runs WKT queries for the full duration.
 * </pre>
 *
 * <h2>Exit codes</h2>
 * The process exits with a non-zero code on failure. Each code identifies the
 * specific validation check or error that triggered the exit, which makes it
 * possible to diagnose failures from a shell script or CI log without reading
 * stderr (e.g. {@code if [ $? -eq 3 ]; then echo "bad RPS value"; fi}).
 * <pre>
 *   1 — too few command-line arguments (usage error)
 *   2 — duration-seconds is not a valid integer
 *   3 — requests-per-second is not a valid positive integer
 *   4 — CRATE_USER environment variable is missing
 *   5 — CRATE_PASSWORD environment variable is missing
 *   6 — TYPE:COUNT argument has bad format
 *   7 — unknown query type
 *   8 — query count is not a valid positive integer
 *   9 — query count is not positive
 * </pre>
 */
public class QueryCrate {

    // Exit codes — each maps to a specific validation failure (see class Javadoc).
    private static final int EXIT_USAGE              = 1;
    private static final int EXIT_BAD_DURATION       = 2;
    private static final int EXIT_BAD_RPS            = 3;
    private static final int EXIT_NO_USER            = 4;
    private static final int EXIT_NO_PASSWORD        = 5;
    private static final int EXIT_BAD_QUERY_SPEC     = 6;
    private static final int EXIT_UNKNOWN_QUERY_TYPE = 7;
    private static final int EXIT_BAD_COUNT          = 8;
    private static final int EXIT_COUNT_NOT_POSITIVE = 9;

    // Geo-proximity query: finds min/max temperature within 1 degree of a point at a given time.
    // The "?" placeholders are bind parameters — JDBC substitutes actual values at execution time,
    // which prevents SQL injection and lets the database reuse the query plan across calls.
    // The "::geo_point" is a CrateDB type cast that converts WKT text into a geospatial type.
    private static final String WKT_SQL =
            "SELECT min(data['temperature']) min_t, max(data['temperature']) max_t\n" +
            "FROM demo.climate_data\n" +
            "WHERE distance(geo_location, ?::geo_point) < 1\n" +
            "AND \"timestamp\" = ?";

    // Three-table join: climate readings × regions × named points. Finds the latest temperature
    // readings for every sensor location inside a named German region.
    // WITHIN() is a CrateDB geo function that tests whether a point falls inside a polygon.
    // The correlated subquery "SELECT max(d2.timestamp)" restricts results to the most recent
    // measurement epoch — a common pattern when you want "latest data" without a separate index.
    // Subtracting 273.15 converts Kelvin (the stored unit) to Celsius.
    private static final String REGION_SQL =
            "SELECT\n" +
            "  d.\"timestamp\" as time,\n" +
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
            "AND r.region_name = ?\n" +
            "AND d.\"timestamp\" = (SELECT max(d2.\"timestamp\") FROM demo.climate_data d2)";

    // Full-text search query using CrateDB's MATCH predicate. Works like a search engine —
    // the "economics" column has a full-text index, and MATCH scores each row by how well
    // its text matches the search term. _score is a built-in CrateDB relevance column.
    private static final String FTS_SQL =
            "SELECT region_name, _score\n" +
            "FROM demo.german_regions\n" +
            "WHERE MATCH(economics, ?)\n" +
            "ORDER BY _score DESC\n" +
            "LIMIT 3";

    // Canned search terms rotated randomly to simulate varied user searches.
    private static final String[] FTS_TERMS = {"cars", "trains", "factories", "energy"};

    private static final Set<String> VALID_QUERY_TYPES = Set.of("WKT", "REGION", "FTS");

    // These lists are populated once at startup from the database and then sampled randomly
    // during the workload. They act as a pool of realistic parameter values so each query
    // hits data that actually exists, rather than generating random coordinates that miss.
    private static final List<PGpoint> geoPoints = new ArrayList<>();
    private static final List<Timestamp> timestamps = new ArrayList<>();
    private static final List<String> regionNames = new ArrayList<>();

    public static void main(String[] args) {
        if (args.length < 4) {
            printUsageAndExit();
        }

        // --- Parse the four mandatory positional arguments ---

        long durationSeconds;
        try {
            durationSeconds = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid duration (must be an integer number of seconds): " + args[0]);
            System.exit(EXIT_BAD_DURATION);
            return; // unreachable, but the compiler doesn't know System.exit terminates the JVM
        }

        String host = args[1];

        int requestsPerSecond;
        try {
            requestsPerSecond = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid requests-per-second (must be a positive integer): " + args[2]);
            System.exit(EXIT_BAD_RPS);
            return;
        }
        if (requestsPerSecond <= 0) {
            System.err.println("requests-per-second must be > 0");
            System.exit(EXIT_BAD_RPS);
        }

        // CrateDB speaks the PostgreSQL wire protocol, so we connect via a standard JDBC
        // PostgreSQL driver. The sslmode parameter controls TLS: "disable", "require", etc.
        String sslMode = args[3];
        String jdbcUrl = "jdbc:postgresql://" + host + ":5432/crate?sslmode=" + sslMode;

        // Credentials come from environment variables rather than CLI args so they don't
        // appear in shell history or process listings (ps aux), which is a basic security practice.
        String user = System.getenv("CRATE_USER");
        if (user == null || user.isEmpty()) {
            System.err.println("CRATE_USER environment variable is not set.");
            System.exit(EXIT_NO_USER);
        }

        String password = System.getenv("CRATE_PASSWORD");
        if (password == null || password.isEmpty()) {
            System.err.println("CRATE_PASSWORD environment variable is not set.");
            System.exit(EXIT_NO_PASSWORD);
        }

        // --- Parse optional TYPE:COUNT arguments that define the query mix ---

        // LinkedHashMap preserves insertion order so the workload is deterministic given the same args.
        Map<String, Integer> queryCounts = new LinkedHashMap<>();
        // If no TYPE:COUNT args are given, we fall back to "indefinite mode" — WKT queries only,
        // running continuously until the duration expires.
        boolean indefiniteMode = (args.length == 4);

        for (int i = 4; i < args.length; i++) {
            String[] parts = args[i].split(":");
            if (parts.length != 2) {
                System.err.println("Invalid query spec (expected TYPE:COUNT): " + args[i]);
                System.exit(EXIT_BAD_QUERY_SPEC);
            }
            String type = parts[0].toUpperCase();
            if (!VALID_QUERY_TYPES.contains(type)) {
                System.err.println("Unknown query type: " + parts[0] + ". Valid types: " + VALID_QUERY_TYPES);
                System.exit(EXIT_UNKNOWN_QUERY_TYPE);
            }
            int count;
            try {
                count = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid count (must be a positive integer): " + parts[1]);
                System.exit(EXIT_BAD_COUNT);
                return;
            }
            if (count <= 0) {
                System.err.println("Count must be > 0: " + args[i]);
                System.exit(EXIT_COUNT_NOT_POSITIVE);
            }
            // merge() with Integer::sum handles the case where the user specifies the same type
            // more than once (e.g., "WKT:50 WKT:30" → WKT:80).
            queryCounts.merge(type, count, Integer::sum);
        }

        // --- Determine which reference data to pre-load ---

        // Only fetch reference data for query types we'll actually run. Each loader issues a
        // GROUP BY query that can be expensive, so we skip unnecessary ones.
        boolean needsWkt = indefiniteMode || queryCounts.containsKey("WKT");
        boolean needsRegion = queryCounts.containsKey("REGION");

        // Build a flat list with one entry per query to execute, then shuffle it.
        // Shuffling interleaves query types randomly so the database sees a realistic mixed
        // workload instead of all WKTs followed by all REGIONs — which would be unrealistic
        // and could skew caching behavior.
        List<String> workList = new ArrayList<>();
        if (!indefiniteMode) {
            for (Map.Entry<String, Integer> entry : queryCounts.entrySet()) {
                for (int i = 0; i < entry.getValue(); i++) {
                    workList.add(entry.getKey());
                }
            }
            Collections.shuffle(workList);
        }

        // JDBC uses a Properties bag for connection metadata.
        Properties properties = new Properties();
        properties.put("user", user);
        properties.put("password", password);

        // try-with-resources: the Connection is automatically closed (returned to the OS / pool)
        // when the block exits, even if an exception is thrown. This prevents connection leaks.
        try (Connection conn = DriverManager.getConnection(jdbcUrl, properties)) {
            printClusterName(conn);

            // Pre-load parameter pools from the database so queries hit real, existing data.
            if (needsWkt) {
                loadGeoPoints(conn);
                System.out.println("Loaded " + geoPoints.size() + " geo points.");
                loadTimestamps(conn);
                System.out.println("Loaded " + timestamps.size() + " timestamps.");
            }
            if (needsRegion) {
                loadRegionNames(conn);
                System.out.println("Loaded " + regionNames.size() + " region names: " + regionNames);
            }

            runWorkload(conn, durationSeconds, requestsPerSecond, workList, indefiniteMode);
        } catch (SQLException e) {
            // SQLState is a standardized 5-character code (e.g., "08001" = connection failure).
            // ErrorCode is vendor-specific. Both are useful for diagnosing issues in production.
            System.err.println("Database error during workload execution: " + e.getMessage());
            System.err.println("SQLState: " + e.getSQLState() + ", ErrorCode: " + e.getErrorCode());
            e.printStackTrace();
        }
    }

    private static void runWorkload(Connection conn,
                                    long durationSeconds,
                                    int requestsPerSecond,
                                    List<String> workList,
                                    boolean indefiniteMode) throws SQLException {
        // Sanity check: indefinite mode only runs WKT queries, so we need both pools populated.
        if (indefiniteMode) {
            if (geoPoints.isEmpty()) {
                System.err.println("No geo points loaded; nothing to poll.");
                return;
            }
            if (timestamps.isEmpty()) {
                System.err.println("No timestamps loaded; nothing to poll.");
                return;
            }
        }

        // One HdrHistogram per query type, keyed by name (e.g., "WKT", "REGION", "FTS").
        // HdrHistogram records latency values with configurable precision and range, and
        // supports efficient percentile queries without storing every individual sample.
        Map<String, Histogram> histograms = new LinkedHashMap<>();
        // Wall-clock deadline — we stop issuing queries once we pass this time.
        final long deadline = System.currentTimeMillis() + durationSeconds * 1000L;
        // Target inter-query gap in milliseconds. For example, 50 req/s → 20ms between queries.
        // This is a simple open-loop rate limiter (it doesn't account for query execution time
        // stacking up, but throttle() handles that by skipping sleep when we're already behind).
        final long targetGapMs = 1000L / requestsPerSecond;

        // PreparedStatements are compiled once by the database and reused across executions.
        // This avoids re-parsing the SQL on every call, which matters at high throughput.
        // We only prepare statements for query types we'll actually run.
        PreparedStatement wktPs = null;
        PreparedStatement regionPs = null;
        PreparedStatement ftsPs = null;
        try {
            if (!geoPoints.isEmpty()) {
                wktPs = conn.prepareStatement(WKT_SQL);
            }
            if (!regionNames.isEmpty()) {
                regionPs = conn.prepareStatement(REGION_SQL);
            }
            ftsPs = conn.prepareStatement(FTS_SQL);

            if (indefiniteMode) {
                // Simple loop: fire WKT queries back-to-back (rate-limited) until time expires.
                while (System.currentTimeMillis() < deadline) {
                    long start = System.currentTimeMillis();
                    executeWktQuery(wktPs, histograms);
                    throttle(start, targetGapMs);
                }
            } else {
                // Walk through the shuffled work list, dispatching each query by type.
                for (String queryType : workList) {
                    // Check the deadline before each query so we don't overrun.
                    if (System.currentTimeMillis() >= deadline) {
                        System.out.println("Duration limit reached; stopping.");
                        break;
                    }
                    long start = System.currentTimeMillis();
                    switch (queryType) {
                        case "WKT" -> {
                            if (wktPs == null) throw new SQLException("WKT statement not prepared — no geo points loaded");
                            executeWktQuery(wktPs, histograms);
                        }
                        case "REGION" -> {
                            if (regionPs == null) throw new SQLException("REGION statement not prepared — no regions loaded");
                            executeRegionQuery(regionPs, histograms);
                        }
                        case "FTS" -> executeFtsQuery(ftsPs, histograms);
                    }
                    // Pause to maintain the target request rate before the next query.
                    throttle(start, targetGapMs);
                }
            }
        } finally {
            // Always close PreparedStatements to release server-side resources.
            // We don't use try-with-resources here because the statements are conditionally created.
            if (wktPs != null) wktPs.close();
            if (regionPs != null) regionPs.close();
            if (ftsPs != null) ftsPs.close();
        }

        // Print the collected latency histograms so the user can see p50/p99/etc. for each type.
        printHistograms(histograms);
    }

    private static void executeWktQuery(PreparedStatement ps, Map<String, Histogram> histograms) throws SQLException {
        long start = System.currentTimeMillis();
        // Pick a random geo point and timestamp from the pre-loaded pools.
        // ThreadLocalRandom is the correct choice for concurrent random numbers — unlike
        // java.util.Random, it doesn't use a shared atomic counter, so it scales well
        // across threads (even though this program is single-threaded today).
        PGpoint p = geoPoints.get(ThreadLocalRandom.current().nextInt(geoPoints.size()));
        Timestamp ts = timestamps.get(ThreadLocalRandom.current().nextInt(timestamps.size()));
        // Build a Well-Known Text (WKT) string — the standard text format for geospatial data.
        // CrateDB will parse this into an internal geo_point via the ::geo_point cast in the SQL.
        String wkt = "POINT(" + p.x + " " + p.y + ")";
        // Bind the parameters by position (1-indexed). This fills in the "?" placeholders in WKT_SQL.
        ps.setString(1, wkt);
        ps.setTimestamp(2, ts);

        // try-with-resources ensures the ResultSet is closed even if we throw while reading rows.
        // Unclosed ResultSets can leak server-side cursors and eventually exhaust connections.
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                recordLatency(histograms, "WKT", start);
                System.out.printf("%s @ %s -> min=%s max=%s%n",
                        wkt, ts, rs.getObject("min_t"), rs.getObject("max_t"));
            }
        }
    }

    private static void executeRegionQuery(PreparedStatement ps, Map<String, Histogram> histograms) throws SQLException {
        long start = System.currentTimeMillis();
        // Pick a random region name from the pool — e.g., "Bayern", "Sachsen", etc.
        String region = regionNames.get(ThreadLocalRandom.current().nextInt(regionNames.size()));
        ps.setString(1, region);

        // This query can return multiple rows (one per sensor location in the region),
        // so we iterate with while() instead of a single if().
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.printf("region=%s time=%s lat=%s lon=%s temp=%s town=%s%n",
                        region,
                        rs.getObject("time"),
                        rs.getObject("latitude"),
                        rs.getObject("longitude"),
                        rs.getObject("temperature"),
                        rs.getObject("nearest_town"));
            }
        }
        recordLatency(histograms, "REGION", start);
    }

    private static void executeFtsQuery(PreparedStatement ps, Map<String, Histogram> histograms) throws SQLException {
        long start = System.currentTimeMillis();
        // Rotate through the canned search terms to simulate varied full-text searches.
        String term = FTS_TERMS[ThreadLocalRandom.current().nextInt(FTS_TERMS.length)];
        ps.setString(1, term);

        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // _score is a floating-point relevance score computed by CrateDB's Lucene-based
                // full-text engine. Higher means a better match to the search term.
                System.out.printf("FTS '%s' -> region=%s score=%s%n",
                        term, rs.getString("region_name"), rs.getObject("_score"));
            }
        }
        recordLatency(histograms, "FTS", start);
    }

    // Records a latency sample into the named histogram, creating it on first use.
    // HdrHistogram is configured to track values from 1ms to 60,000ms (1 minute) with
    // 3 significant digits of precision — enough to distinguish 1.00ms from 1.01ms.
    private static void recordLatency(Map<String, Histogram> histograms, String type, long startMs) {
        long latency = System.currentTimeMillis() - startMs;
        histograms.computeIfAbsent(type, k -> new Histogram(60_000, 3))
                  .recordValue(Math.max(latency, 0));
    }

    // Prints a percentile summary for each query type.
    private static void printHistograms(Map<String, Histogram> histograms) {
        for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
            Histogram h = entry.getValue();
            System.out.printf("%s: count=%d avg=%.1fms p50=%dms p99=%dms p99.9=%dms max=%dms%n",
                    entry.getKey(),
                    h.getTotalCount(),
                    h.getMean(),
                    h.getValueAtPercentile(50),
                    h.getValueAtPercentile(99),
                    h.getValueAtPercentile(99.9),
                    h.getMaxValue());
        }
    }

    // Simple rate limiter: sleeps for whatever time remains in the target interval after
    // subtracting how long the query took. If the query already took longer than the interval,
    // we skip sleeping entirely (sleepMs <= 0) rather than trying to "catch up."
    private static void throttle(long start, long targetGapMs) {
        long sleepMs = targetGapMs - (System.currentTimeMillis() - start);
        if (sleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            // Re-set the interrupt flag. Thread.sleep() clears it when it throws, but upstream
            // code may need to see it. This is a standard Java concurrency best practice —
            // never swallow an interrupt without re-asserting it.
            Thread.currentThread().interrupt();
        }
    }

    private static void printUsageAndExit() {
        System.err.println("Usage: QueryCrate <duration-seconds> <host> <requests-per-second> <sslmode> [TYPE:COUNT ...]");
        System.err.println("  Supported query types: " + VALID_QUERY_TYPES);
        System.err.println("  Example: QueryCrate 120 myhost 50 disable WKT:100 REGION:50");
        System.err.println("  If no TYPE:COUNT args are given, runs WKT queries for the full duration.");
        System.exit(EXIT_USAGE);
    }

    // Quick connectivity check — queries the CrateDB system table for the cluster name.
    // sys.cluster is a CrateDB-specific metadata table (not standard PostgreSQL).
    private static void printClusterName(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM sys.cluster")) {
            if (rs.next()) {
                System.out.println(rs.getString("name"));
            }
        }
    }

    // Loads every distinct geographic location from the climate data table.
    // GROUP BY collapses duplicates — without it we'd get one row per measurement, not per location.
    private static void loadGeoPoints(Connection conn) throws SQLException {
        String sql = "SELECT geo_location FROM demo.climate_data GROUP BY geo_location";
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                // The JDBC driver may return geo data as a PGpoint (PostgreSQL's native type)
                // or as a plain string, depending on the driver version and CrateDB config.
                // We handle both cases so the code is resilient to driver behavior changes.
                Object value = rs.getObject("geo_location");
                if (value instanceof PGpoint p) {
                    geoPoints.add(p);
                } else if (value != null) {
                    geoPoints.add(parsePoint(value.toString()));
                }
            }
        }
    }

    // Parses a string like "(9.5,47.3)" into a PGpoint. PGpoint's constructor handles the format.
    private static PGpoint parsePoint(String s) throws SQLException {
        return new PGpoint(s);
    }

    // Loads every distinct timestamp. The ORDER BY isn't strictly necessary for random sampling,
    // but it makes the loaded data deterministic and easier to debug.
    private static void loadTimestamps(Connection conn) throws SQLException {
        String sql = "SELECT \"timestamp\" FROM demo.climate_data "
                + "GROUP BY \"timestamp\" ORDER BY \"timestamp\"";
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("timestamp");
                if (ts != null) {
                    timestamps.add(ts);
                }
            }
        }
    }

    // Loads all German region names used by the REGION query type.
    private static void loadRegionNames(Connection conn) throws SQLException {
        String sql = "SELECT region_name FROM demo.german_regions";
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("region_name");
                if (name != null) {
                    regionNames.add(name);
                }
            }
        }
    }
}
