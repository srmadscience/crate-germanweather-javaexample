

# Java

## Basic connectivity: Crate can use any PostgreSQL-compatible JDBC driver.

An example connection string is:

```java
jdbc:postgresql://<host>:5432/doc// 
```

## Example: Using Java against our German Weather Data data set

If you want to see a simple example CrateDB/JDBC application check out this repository:

https://github.com/crate/cratedb-explore/tree/main/src_weather/main/java

This is A Java/JDBC load generator that connects to a CrateDB cluster over the PostgreSQL wire protocol and runs a configurable mix of queries against German climate and region data. It assumes you have [loaded the data](https://cratedb.com/explore/iot-analytics/import?use-case=iot).

The single application class is [QueryCrate](https://github.com/crate/cratedb-explore/blob/main/src_weather/main/java/QueryCrate.java).

###  What it does

* Opens a JDBC connection to a CrateDB cluster.
* Prints the cluster name (SELECT name FROM sys.cluster) as a connectivity smoke test.
* Pre-loads reference data from the database (only for query types that will be run):
  * WKT queries: loads every distinct geo_location and timestamp from demo.climate_data. 
  * REGION queries: loads every region_name from demo.german_regions.
* Runs a workload of queries at a configurable rate, choosing from three query types:
  * WKT — geo-proximity query: finds min/max temperature within 1 metre of a random point at a random timestamp.
  * REGION — three-table join: finds the latest temperature readings for every sensor location inside a named German region, converting Kelvin to Celsius.
  * FTS — full-text search: searches the economics column of demo.german_regions using CrateDB's MATCH predicate and returns the top 3 results by relevance score. *Records the round-trip latency of each query in an [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram) and prints percentile summaries when the run finishes.
* Records the round-trip latency of each query in an [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram) and prints percentile summaries when the run finishes.





