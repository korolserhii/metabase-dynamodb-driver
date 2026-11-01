**New: "Maximum MB read per table"** (advanced setting, default 100). A table is read until either the row limit (100,000) or the MB limit is reached, whichever comes first, and the Metabase log says which limit cut it. Before, only rows were limited, so a table of large items could fill Metabase's memory long before 100,000 rows.

The README now explains that filters do not reduce DynamoDB reads (each refresh scans the table) and how the in-memory copy relates to the JVM heap.

**Install / upgrade:** download `dynamodb.metabase-driver.jar` below, put it in Metabase's `plugins/` directory (replacing the old jar), restart Metabase. Setup details are in the [README](https://github.com/korolserhii/metabase-dynamodb-driver#readme).

**Tested:** Metabase v0.65.2.5 with automated end-to-end tests against DynamoDB Local, including a table of 100 KB items cut by a 1 MB limit.
