# crate-devjourney-001

A small Java/JDBC load generator that connects to a [CrateDB](https://crate.io/) cluster over the PostgreSQL wire protocol and repeatedly queries climate-data for a random (location, timestamp) pair.

The single application class is [`PingCrate`](src/main/java/ie/rolfe/crate/iotexample/PingCrate.java).

## What it does

1. Opens a JDBC connection to a CrateDB cluster.
2. Prints the cluster name (`SELECT name FROM sys.cluster`) as a connectivity smoke test.
3. Loads every distinct `geo_location` from `demo.climate_data` into an in-memory list.
4. Loads every distinct `timestamp` from the same table.
5. For a configurable duration, picks a random location and a random timestamp on each iteration and runs:

   ```sql
   SELECT min(data['temperature']) AS min_t,
          max(data['temperature']) AS max_t
   FROM demo.climate_data
   WHERE distance(geo_location, ?::geo_point) < 1
     AND "timestamp" = ?
   ```

6. Records the round-trip latency of each query in a `SafeHistogramCache` and prints the histogram when the run finishes.

## Prerequisites

- Java 17+
- Maven 3.9+
- Network access to your CrateDB cluster on port 5432
- A populated `demo.climate_data` table with `geo_location` (`geo_point`), `"timestamp"` (`timestamp with time zone`) and `data` (`object` with a `temperature` field) columns

## Build

```bash
mvn clean package
```

## Run

The application takes four positional arguments and reads the database username and password from the `CRATE_USER` and `CRATE_PASSWORD` environment variables so they never land in shell history or process listings.

```bash
export CRATE_USER='admin'
export CRATE_PASSWORD='your-password'
mvn compile exec:java -Dexec.args="<duration-seconds> <host> <requests-per-second> <sslmode>"
```

| Argument              | Description                                                                                |
| --------------------- | ------------------------------------------------------------------------------------------ |
| `duration-seconds`    | How long the polling loop should run.                                                      |
| `host`                | CrateDB hostname (port `5432` and database `crate` are hard-coded).                        |
| `requests-per-second` | Target throughput. The loop paces itself so each iteration takes about `1000 / rps` ms. If the database can't keep up the loop just runs as fast as it can. |
| `sslmode`             | PostgreSQL SSL mode for the JDBC connection. Common values: `disable`, `require`, `verify-ca`, `verify-full`. Use `require` for CrateDB Cloud and `disable` for a plain local cluster. |

For example, to run for 120 seconds against a CrateDB Cloud cluster at ~50 req/sec with TLS:

```bash
export CRATE_USER='admin'
export CRATE_PASSWORD='your-password'
mvn compile exec:java -Dexec.args="120 gdimplex-1.eks1.eu-west-1.aws.cratedb.net 50 require"
```

Against a local cluster without TLS:

```bash
export CRATE_USER='admin'
export CRATE_PASSWORD='your-password'
mvn compile exec:java -Dexec.args="120 localhost 50 disable"
```

The JDBC URL is built as:

```
jdbc:postgresql://<host>:5432/crate?sslmode=<sslmode>
```

## Sample output

```
my-cluster
Loaded 727 geo points.
Loaded 8760 timestamps.
POINT(13.74999993480742 52.49999997206032) @ 2024-06-15 12:00:00.0 -> min=21.4 max=21.4
POINT(8.999999966472387 54.24999999348074) @ 2024-02-03 03:00:00.0 -> min=-1.2 max=-1.2
...
[histogram summary]
```

## Notes on the SQL

- **`distance(geo_location, ?::geo_point) < 1`** — CrateDB stores `geo_point` values in a Lucene-encoded form that quantises the underlying doubles, so an exact `=` comparison against a value you read back will not always match. Filtering with a 1-metre tolerance reliably identifies the same grid square at climate-data resolution.
- **`?::geo_point`** — the `?` is a JDBC parameter placeholder; `::geo_point` is PostgreSQL/Crate cast syntax. The parameter is bound as a WKT string (`POINT(lon lat)`) and the server parses it.
- **`"timestamp"`** — `timestamp` is a reserved word, so the column name must be quoted. Quoting also makes the identifier case-sensitive.

## Project layout

```
src/main/java/
├── ie/rolfe/crate/iotexample/
│   └── PingCrate.java            # the application
└── org/voltdb/voltutil/stats/    # vendored latency-histogram utilities
    ├── LatencyHistogram.java
    ├── SafeHistogramCache.java
    └── SizeHistogram.java
```

## License

Apache License 2.0. See the header in `PingCrate.java` and the [LICENSE](LICENSE) file.
