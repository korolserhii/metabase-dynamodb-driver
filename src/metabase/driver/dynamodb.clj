(ns metabase.driver.dynamodb
  "Amazon DynamoDB driver for Metabase.

  DynamoDB cannot group, join or aggregate, so this driver copies each table into a private in-memory H2 database
  (see [[metabase.driver.dynamodb.mirror]]) and lets Metabase's existing H2 SQL support do the querying. The driver
  is registered as a child of `:h2` to inherit that SQL dialect; everything about where data comes from, connecting,
  and what users may do is overridden here."
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.dynamodb.client :as ddb]
   [metabase.driver.dynamodb.mirror :as mirror]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

(driver/register! :dynamodb, :parent #{:h2})

;;; ------------------------------------------------ what users can do ----------------------------------------------

;; The H2 copy is read-only from Metabase's point of view; anything that would write to "the database" is off.
(doseq [[feature supported?] {:actions              false
                              :actions/custom       false
                              :actions/data-editing false
                              :uploads              false
                              :database-routing     false
                              :transforms/table     false
                              :transforms/python    false
                              :transforms/testing   false}]
  (defmethod driver/database-supports? [:dynamodb feature] [_driver _feature _db] supported?))

;; H2 hides itself from the "add database" list; DynamoDB must not.
(defmethod driver/superseded-by :dynamodb [_driver] nil)

;;; ------------------------------------------------ connecting -----------------------------------------------------

(defmethod driver/connection-hosts :dynamodb
  [_driver details]
  [(ddb/endpoint-host details)])

(defmethod driver/routes-connection-through-ssh-tunnel? :dynamodb [_driver] false)

(defmethod driver/incorporate-ssh-tunnel-details :dynamodb
  [_driver details]
  details)

(defmethod driver/validate-db-details! :dynamodb
  [_driver details]
  (when (str/blank? (str (:region details)))
    (throw (ex-info (tru "AWS region is required.") {:status-code 400})))
  (when-let [e (ddb/endpoint details)]
    (when-not (re-matches #"(?i)https?://.+" e)
      (throw (ex-info (tru "The custom endpoint must start with http:// or https://") {:status-code 400})))))

(defmethod driver/can-connect? :dynamodb
  [driver details]
  (driver/validate-db-details! driver details)
  (ddb/can-connect? details))

(defmethod driver/humanize-connection-error-message :dynamodb
  [_driver messages]
  (let [message (str (first messages))]
    (cond
      (re-find #"(?i)security token|signature|UnrecognizedClient|InvalidSignature" message)
      (tru "AWS rejected the access key or secret. Check the credentials.")

      (re-find #"(?i)not authorized|AccessDenied" message)
      (tru "These credentials are not allowed to list DynamoDB tables (dynamodb:ListTables is required).")

      (re-find #"(?i)Unable to load credentials" message)
      (tru "No AWS credentials found. Enter an access key, or give the Metabase host an IAM role.")

      :else message)))

;; The Metabase connection pool connects to the H2 copy, as the SELECT-only user.
(defmethod sql-jdbc.conn/connection-details->spec :dynamodb
  [_driver details]
  (mirror/reader-jdbc-spec details))

;;; ------------------------------------------------ sync -----------------------------------------------------------

(defmethod driver/sync-in-context :dynamodb
  [_driver database do-sync-fn]
  ;; no read lock here: sync runs queries, and a query may need to (re)load a table, which takes the write lock.
  (mirror/refresh-all! (:details database))
  (do-sync-fn))

(defmethod driver/describe-database* :dynamodb
  [driver database]
  (-> ((get-method driver/describe-database* :h2) driver database)
      (update :tables (fn [tables]
                        (into #{} (remove #(mirror/staging-table? (:name %))) tables)))))

;;; ------------------------------------------------ queries --------------------------------------------------------

(def ^:private server-functions
  "H2 functions that touch the server rather than the data. The SELECT-only user cannot run most of them anyway;
  rejecting them by name gives a clear message."
  #{"ABORT_SESSION" "CANCEL_SESSION" "CSVREAD" "CSVWRITE" "DATABASE_PATH" "DB_OBJECT_ID" "DB_OBJECT_SQL"
    "FILE_READ" "FILE_WRITE" "LINK_SCHEMA" "MEMORY_FREE" "MEMORY_USED" "SYSTEM_RANGE_LINK"})

(def ^:private server-functions-re
  (re-pattern (str "(?i)\\b(?:" (str/join "|" server-functions) ")\\b")))

(defn- check-sql! [^String sql]
  (when-let [found (seq (re-seq server-functions-re (or sql "")))]
    (throw (ex-info (tru "These functions are not supported: {0}" (str/join ", " (distinct (map str/upper-case found))))
                    {:type driver-api/qp.error-type.db}))))

(defmethod driver/execute-reducible-query :dynamodb
  [driver query context respond]
  (let [sql     (get-in query [:native :query])
        details (driver.conn/effective-details (driver-api/database (driver-api/metadata-provider)))]
    (check-sql! sql)
    (mirror/ensure-tables! details (mirror/tables-referenced-by details sql))
    (mirror/with-read-lock details
      ;; skip the :h2 implementation: its admin-user check reads an H2 connection string we don't have.
      (sql-jdbc.execute/execute-reducible-query driver query context respond))))

(defmethod driver/execute-write-query! :dynamodb
  [_driver _query]
  (throw (ex-info (tru "DynamoDB databases are read-only in Metabase.") {:status-code 400})))

(log/info "Metabase DynamoDB driver loaded")
