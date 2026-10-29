First public version of the Amazon DynamoDB driver for self-hosted Metabase.

**Install:** download `dynamodb.metabase-driver.jar` below, put it in Metabase's `plugins/` directory, restart Metabase, then *Admin → Databases → Add database → Amazon DynamoDB*. Setup details and the IAM policy are in the [README](https://github.com/korolserhii/metabase-dynamodb-driver#readme).

**What works:** the visual query builder (filters, summaries, grouping by date), native SQL, ISO date/timestamp detection, nested maps and lists as JSON. Read-only.

**How it works:** tables are scanned into an in-memory copy inside Metabase and refreshed on an interval, so scans cost read capacity and very large tables are read up to a configurable row limit.

**Tested:** Metabase v0.65.2.2 with automated end-to-end tests against DynamoDB Local.
**Not yet tested:** real AWS accounts and large production tables. Reports from either are very welcome — please open an issue.
