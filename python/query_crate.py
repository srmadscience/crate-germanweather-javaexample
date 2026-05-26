#!/usr/bin/env python3
#
# Copyright 2026 Crate.io
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Load-generator that connects to a CrateDB cluster over the PostgreSQL
wire protocol and runs a configurable mix of queries against climate data.

Usage:
    CRATE_USER=<user> CRATE_PASSWORD=<password> \
    python query_crate.py \
        <duration-seconds> <host> <requests-per-second> <sslmode> \
        [TYPE:COUNT ...]

    Supported query types: WKT, REGION, FTS
    Example: python query_crate.py 120 myhost 50 disable WKT:100 REGION:50 FTS:30
    If no TYPE:COUNT args are given, runs WKT queries for the full duration.

Exit codes:
    1 — too few command-line arguments (usage error)
    2 — duration-seconds is not a valid integer
    3 — requests-per-second is not a valid positive integer
    4 — CRATE_USER environment variable is missing
    5 — CRATE_PASSWORD environment variable is missing
    6 — TYPE:COUNT argument has bad format
    7 — unknown query type
    8 — query count is not a valid positive integer
    9 — query count is not positive
"""

import os
import sys
import random
import time

import psycopg2
from hdrh.histogram import HdrHistogram

# Exit codes — each maps to a specific validation failure.
EXIT_USAGE = 1
EXIT_BAD_DURATION = 2
EXIT_BAD_RPS = 3
EXIT_NO_USER = 4
EXIT_NO_PASSWORD = 5
EXIT_BAD_QUERY_SPEC = 6
EXIT_UNKNOWN_QUERY_TYPE = 7
EXIT_BAD_COUNT = 8
EXIT_COUNT_NOT_POSITIVE = 9

# Geo-proximity query: finds min/max temperature within 1 degree of a point at a given time.
# The %s placeholders are bind parameters — psycopg2 substitutes actual values at execution time,
# which prevents SQL injection and lets the database reuse the query plan across calls.
# The "::geo_point" is a CrateDB type cast that converts WKT text into a geospatial type.
WKT_SQL = (
    "SELECT min(data['temperature']) min_t, max(data['temperature']) max_t\n"
    "FROM demo.climate_data\n"
    "WHERE distance(geo_location, %s::geo_point) < 1\n"
    "AND measurement_time = %s"
)

# Three-table join: climate readings × regions × named points. Finds the latest temperature
# readings for every sensor location inside a named German region.
# WITHIN() is a CrateDB geo function that tests whether a point falls inside a polygon.
# The correlated subquery restricts results to the most recent measurement epoch.
# Subtracting 273.15 converts Kelvin (the stored unit) to Celsius.
REGION_SQL = (
    "SELECT\n"
    "  d.measurement_time as time,\n"
    "  latitude(d.geo_location) as latitude,\n"
    "  longitude(d.geo_location) as longitude,\n"
    "  data['temperature'] - 273.15 as temperature,\n"
    "  gp.nearest_town\n"
    "FROM\n"
    "  demo.climate_data d,\n"
    "  demo.german_regions r,\n"
    "  demo.geo_points gp\n"
    "WHERE WITHIN(d.geo_location, r.geo_coords)\n"
    "AND gp.geo_location = d.geo_location\n"
    "AND r.region_name = %s\n"
    "AND d.measurement_time = (SELECT max(d2.measurement_time) FROM demo.climate_data d2)"
)

# Full-text search query using CrateDB's MATCH predicate.
# _score is a built-in CrateDB relevance column.
FTS_SQL = (
    "SELECT region_name, _score\n"
    "FROM demo.german_regions\n"
    "WHERE MATCH(economics, %s)\n"
    "ORDER BY _score DESC\n"
    "LIMIT 3"
)

# Canned search terms rotated randomly to simulate varied user searches.
FTS_TERMS = ["cars", "trains", "factories", "energy"]

VALID_QUERY_TYPES = {"WKT", "REGION", "FTS"}

# Populated once at startup from the database and then sampled randomly during the workload.
geo_points: list[tuple[float, float]] = []
timestamps: list = []
region_names: list[str] = []


def print_usage_and_exit():
    print(
        "Usage: query_crate.py <duration-seconds> <host> <requests-per-second> <sslmode> [TYPE:COUNT ...]",
        file=sys.stderr,
    )
    print(f"  Supported query types: {VALID_QUERY_TYPES}", file=sys.stderr)
    print(
        "  Example: query_crate.py 120 myhost 50 disable WKT:100 REGION:50",
        file=sys.stderr,
    )
    print(
        "  If no TYPE:COUNT args are given, runs WKT queries for the full duration.",
        file=sys.stderr,
    )
    sys.exit(EXIT_USAGE)


def print_cluster_name(cur):
    """Quick connectivity check — queries the CrateDB system table for the cluster name."""
    cur.execute("SELECT name FROM sys.cluster")
    row = cur.fetchone()
    if row:
        print(row[0])


def load_geo_points(cur):
    """Loads every distinct geographic location from the climate data table."""
    cur.execute("SELECT geo_location FROM demo.climate_data GROUP BY geo_location")
    for row in cur:
        value = row[0]
        if value is not None:
            if isinstance(value, (list, tuple)) and len(value) == 2:
                geo_points.append((float(value[0]), float(value[1])))
            elif isinstance(value, str):
                cleaned = value.strip("()")
                parts = cleaned.split(",")
                if len(parts) == 2:
                    geo_points.append((float(parts[0].strip()), float(parts[1].strip())))


def load_timestamps(cur):
    """Loads every distinct timestamp."""
    cur.execute(
        "SELECT measurement_time FROM demo.climate_data "
        "GROUP BY measurement_time ORDER BY measurement_time"
    )
    for row in cur:
        if row[0] is not None:
            timestamps.append(row[0])


def load_region_names(cur):
    """Loads all German region names used by the REGION query type."""
    cur.execute("SELECT region_name FROM demo.german_regions")
    for row in cur:
        if row[0] is not None:
            region_names.append(row[0])


def record_latency(histograms: dict[str, HdrHistogram], query_type: str, start_ms: float):
    """Records a latency sample into the named histogram, creating it on first use."""
    latency = int((time.monotonic() - start_ms) * 1000)
    if query_type not in histograms:
        histograms[query_type] = HdrHistogram(1, 60_000, 3)
    histograms[query_type].record_value(max(latency, 0))


def execute_wkt_query(cur, histograms: dict[str, HdrHistogram]):
    start = time.monotonic()
    point = random.choice(geo_points)
    ts = random.choice(timestamps)
    wkt = f"POINT({point[0]} {point[1]})"
    cur.execute(WKT_SQL, (wkt, ts))
    row = cur.fetchone()
    if row:
        record_latency(histograms, "WKT", start)
        print(f"{wkt} @ {ts} -> min={row[0]} max={row[1]}")


def execute_region_query(cur, histograms: dict[str, HdrHistogram]):
    start = time.monotonic()
    region = random.choice(region_names)
    cur.execute(REGION_SQL, (region,))
    for row in cur:
        print(
            f"region={region} time={row[0]} lat={row[1]} lon={row[2]} "
            f"temp={row[3]} town={row[4]}"
        )
    record_latency(histograms, "REGION", start)


def execute_fts_query(cur, histograms: dict[str, HdrHistogram]):
    start = time.monotonic()
    term = random.choice(FTS_TERMS)
    cur.execute(FTS_SQL, (term,))
    for row in cur:
        print(f"FTS '{term}' -> region={row[0]} score={row[1]}")
    record_latency(histograms, "FTS", start)


def print_histograms(histograms: dict[str, HdrHistogram]):
    for name, h in histograms.items():
        print(
            f"{name}: count={h.total_count} avg={h.get_mean_value():.1f}ms "
            f"p50={h.get_value_at_percentile(50)}ms "
            f"p99={h.get_value_at_percentile(99)}ms "
            f"p99.9={h.get_value_at_percentile(99.9)}ms "
            f"max={h.get_max_value()}ms"
        )


def throttle(start: float, target_gap_s: float):
    """Simple rate limiter: sleeps for the remaining time in the target interval."""
    elapsed = time.monotonic() - start
    sleep_s = target_gap_s - elapsed
    if sleep_s > 0:
        time.sleep(sleep_s)


def run_workload(cur, duration_seconds, requests_per_second, work_list, indefinite_mode):
    if indefinite_mode:
        if not geo_points:
            print("No geo points loaded; nothing to poll.", file=sys.stderr)
            return
        if not timestamps:
            print("No timestamps loaded; nothing to poll.", file=sys.stderr)
            return

    histograms: dict[str, HdrHistogram] = {}
    deadline = time.monotonic() + duration_seconds
    target_gap_s = 1.0 / requests_per_second

    if indefinite_mode:
        while time.monotonic() < deadline:
            start = time.monotonic()
            execute_wkt_query(cur, histograms)
            throttle(start, target_gap_s)
    else:
        for query_type in work_list:
            if time.monotonic() >= deadline:
                print("Duration limit reached; stopping.")
                break
            start = time.monotonic()
            if query_type == "WKT":
                if not geo_points:
                    raise RuntimeError("WKT requested but no geo points loaded")
                execute_wkt_query(cur, histograms)
            elif query_type == "REGION":
                if not region_names:
                    raise RuntimeError("REGION requested but no regions loaded")
                execute_region_query(cur, histograms)
            elif query_type == "FTS":
                execute_fts_query(cur, histograms)
            throttle(start, target_gap_s)

    print_histograms(histograms)


def main():
    args = sys.argv[1:]
    if len(args) < 4:
        print_usage_and_exit()

    # --- Parse the four mandatory positional arguments ---

    try:
        duration_seconds = int(args[0])
    except ValueError:
        print(f"Invalid duration (must be an integer number of seconds): {args[0]}", file=sys.stderr)
        sys.exit(EXIT_BAD_DURATION)

    host = args[1]

    try:
        requests_per_second = int(args[2])
    except ValueError:
        print(
            f"Invalid requests-per-second (must be a positive integer): {args[2]}",
            file=sys.stderr,
        )
        sys.exit(EXIT_BAD_RPS)

    if requests_per_second <= 0:
        print("requests-per-second must be > 0", file=sys.stderr)
        sys.exit(EXIT_BAD_RPS)

    ssl_mode = args[3]

    # Credentials from environment variables so they don't appear in shell history.
    user = os.environ.get("CRATE_USER", "")
    if not user:
        print("CRATE_USER environment variable is not set.", file=sys.stderr)
        sys.exit(EXIT_NO_USER)

    password = os.environ.get("CRATE_PASSWORD", "")
    if not password:
        print("CRATE_PASSWORD environment variable is not set.", file=sys.stderr)
        sys.exit(EXIT_NO_PASSWORD)

    # --- Parse optional TYPE:COUNT arguments ---

    query_counts: dict[str, int] = {}
    indefinite_mode = len(args) == 4

    for arg in args[4:]:
        parts = arg.split(":")
        if len(parts) != 2:
            print(f"Invalid query spec (expected TYPE:COUNT): {arg}", file=sys.stderr)
            sys.exit(EXIT_BAD_QUERY_SPEC)

        qtype = parts[0].upper()
        if qtype not in VALID_QUERY_TYPES:
            print(f"Unknown query type: {parts[0]}. Valid types: {VALID_QUERY_TYPES}", file=sys.stderr)
            sys.exit(EXIT_UNKNOWN_QUERY_TYPE)

        try:
            count = int(parts[1])
        except ValueError:
            print(f"Invalid count (must be a positive integer): {parts[1]}", file=sys.stderr)
            sys.exit(EXIT_BAD_COUNT)

        if count <= 0:
            print(f"Count must be > 0: {arg}", file=sys.stderr)
            sys.exit(EXIT_COUNT_NOT_POSITIVE)

        query_counts[qtype] = query_counts.get(qtype, 0) + count

    # --- Determine which reference data to pre-load ---

    needs_wkt = indefinite_mode or "WKT" in query_counts
    needs_region = "REGION" in query_counts

    # Build a flat shuffled work list so query types are interleaved randomly.
    work_list: list[str] = []
    if not indefinite_mode:
        for qtype, count in query_counts.items():
            work_list.extend([qtype] * count)
        random.shuffle(work_list)

    # CrateDB speaks the PostgreSQL wire protocol; connect via psycopg2.
    conn_str = (
        f"host={host} port=5432 dbname=crate "
        f"user={user} password={password} sslmode={ssl_mode}"
    )

    try:
        with psycopg2.connect(conn_str) as conn:
            conn.autocommit = True
            with conn.cursor() as cur:
                print_cluster_name(cur)

                if needs_wkt:
                    load_geo_points(cur)
                    print(f"Loaded {len(geo_points)} geo points.")
                    load_timestamps(cur)
                    print(f"Loaded {len(timestamps)} timestamps.")

                if needs_region:
                    load_region_names(cur)
                    print(f"Loaded {len(region_names)} region names: {region_names}")

                run_workload(cur, duration_seconds, requests_per_second, work_list, indefinite_mode)

    except psycopg2.Error as e:
        print(f"Database error during workload execution: {e}", file=sys.stderr)
        if hasattr(e, "pgcode"):
            print(f"SQLState: {e.pgcode}", file=sys.stderr)
        raise


if __name__ == "__main__":
    main()
