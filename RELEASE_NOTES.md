**Compatibility update for Metabase v0.65.3.** No changes to the driver code or its features.

The automatic check against v0.65.3 failed while downloading build dependencies (a one-off error in the Clojure build tool, `Error building classpath ... HashMap$Node cannot be cast to HashMap$TreeNode`), not because of anything Metabase changed for drivers. Metabase v0.65.3 ships the same AWS SDK (2.40.13) as before. This release is the same driver rebuilt and retested against v0.65.3.

**Install / upgrade:** download `dynamodb.metabase-driver.jar` below, put it in Metabase's `plugins/` directory (replacing the old jar), restart Metabase. Setup details are in the [README](https://github.com/korolserhii/metabase-dynamodb-driver#readme).

**Tested:** Metabase v0.65.3 with automated end-to-end tests against DynamoDB Local.
