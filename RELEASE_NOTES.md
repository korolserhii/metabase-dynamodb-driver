When a table is cut off at the read limit, the Metabase log now also mentions the **Pro edition** (early access) for large tables. No other changes.

**Pro** — large tables on disk, parallel reads, a read-capacity budget, attribute masking, AssumeRole, guaranteed updates: $299 per Metabase server per year, free 14-day trial. [Request a trial](https://github.com/korolserhii/metabase-dynamodb-driver/issues/new?template=pro-trial.yml).

**Install:** download `dynamodb.metabase-driver.jar` below, put it in Metabase's `plugins/` directory (replacing the old jar), restart Metabase. Setup details are in the [README](https://github.com/korolserhii/metabase-dynamodb-driver#readme).
