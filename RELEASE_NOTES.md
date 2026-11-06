Compatibility update: the driver now builds and passes its automated end-to-end tests with **Metabase v0.65.3.3** (previously v0.65.3.1). No code or feature changes were needed — the earlier automatic check failed only because a build tool download (Clojure CLI) timed out on GitHub's side.

**Install:** download `dynamodb.metabase-driver.jar` below, put it in Metabase's `plugins/` directory (replacing the old jar), restart Metabase. Setup details are in the [README](https://github.com/korolserhii/metabase-dynamodb-driver#readme).
