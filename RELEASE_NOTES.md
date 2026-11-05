**Safer reads.** A refresh is now paced so it never takes all of a table's read capacity: by default half of a provisioned table's capacity, or 200 read units per second for on-demand tables. Change it in the advanced option "Maximum read units per second".

When a table is cut off at the read limit, the Metabase log says so once per table and mentions the **Pro edition** (early access, not released yet) for large tables.

**Pro** — large tables on disk, parallel reads, hourly and daily read budgets, attribute masking, AssumeRole, guaranteed updates: planned at $299 per year for one production Metabase deployment, free 30-day trial. [Request a trial](https://github.com/korolserhii/metabase-dynamodb-driver/issues/new?template=pro-trial.yml).

**Install:** download `dynamodb.metabase-driver.jar` below, put it in Metabase's `plugins/` directory (replacing the old jar), restart Metabase. Setup details are in the [README](https://github.com/korolserhii/metabase-dynamodb-driver#readme).
