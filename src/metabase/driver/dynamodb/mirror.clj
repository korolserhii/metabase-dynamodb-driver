(ns metabase.driver.dynamodb.mirror
  "Keeps an in-memory H2 copy (\"mirror\") of DynamoDB tables so Metabase's SQL query processor can run any query on
  them.

  * One private in-memory H2 database per set of connection details.
  * Data is loaded by an admin H2 user. Metabase queries run as a separate H2 user that only has SELECT rights, so
    native queries cannot write, create objects, or reach the filesystem.
  * Each table is re-read from DynamoDB when its copy is older than `refresh-minutes`."
  (:require
   [clojure.string :as str]
   [metabase.driver.dynamodb.client :as ddb]
   [metabase.util.json :as json]
   [metabase.util.log :as log])
  (:import
   (java.math BigDecimal)
   (java.security MessageDigest)
   (java.sql Connection DriverManager PreparedStatement Statement)
   (java.time LocalDate LocalDateTime OffsetDateTime)
   (java.util Base64 Map UUID)
   (java.util.concurrent.locks ReentrantReadWriteLock)
   (software.amazon.awssdk.core SdkBytes)
   (software.amazon.awssdk.services.dynamodb.model AttributeValue)))

(set! *warn-on-reflection* true)

(def reader-user
  "H2 user Metabase queries run as. It only has SELECT rights."
  "MB_DYNAMODB_READER")

(def ^:private admin-user "MB_DYNAMODB_LOADER")

(def ^:private staging-prefix "__mbddb_load_")

(def ^:private max-columns 500)

;;; ------------------------------------------------ settings ------------------------------------------------------

(defn- ->long [x default]
  (cond
    (number? x) (long x)
    (and (string? x) (re-matches #"\s*\d+\s*" x)) (Long/parseLong (str/trim x))
    :else default))

(defn max-rows
  "Maximum number of items read per table."
  [details]
  (max 1 (->long (:max-rows-per-table details) 100000)))

(defn max-bytes
  "Maximum amount of item data read per table, in bytes (setting is in MB)."
  [details]
  (* 1024 1024 (max 1 (->long (:max-mb-per-table details) 100))))

(defn- refresh-ms [details]
  (* 60 1000 (max 0 (->long (:refresh-minutes details) 10))))

;;; ------------------------------------------------ H2 database ---------------------------------------------------

(defn- sha256-hex [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn mirror-key
  "Identifies the mirror for these connection details: the same account, region and endpoint share one."
  [details]
  (subs (sha256-hex (str (ddb/region details) "|" (ddb/endpoint details) "|" (:access-key-id details))) 0 24))

(defn- h2-url [k]
  (str "jdbc:h2:mem:mbdynamodb_" k ";DB_CLOSE_DELAY=-1"))

(defonce ^:private mirrors
  ;; mirror-key -> mirror state map
  (atom {}))

(defn- quote-id ^String [^String s]
  (str "\"" (str/replace s "\"" "\"\"") "\""))

(defn- execute! [^Connection conn ^String sql]
  (with-open [stmt (.createStatement conn)]
    (.execute ^Statement stmt sql)))

(defn- create-mirror [k]
  (let [admin-password  (str (UUID/randomUUID))
        reader-password (str (UUID/randomUUID))
        ;; the first user to connect to a new in-memory H2 database becomes its admin
        conn            (DriverManager/getConnection (h2-url k) admin-user admin-password)]
    (execute! conn (format "CREATE USER IF NOT EXISTS %s PASSWORD '%s'" (quote-id reader-user) reader-password))
    (execute! conn (format "GRANT SELECT ON SCHEMA PUBLIC TO %s" (quote-id reader-user)))
    {:key             k
     :admin-conn      conn
     :reader-password reader-password
     :lock            (ReentrantReadWriteLock.)
     :load-lock       (Object.)
     :loaded          (atom {})   ; table-name -> epoch millis of last load
     :table-names     (atom nil)}))  ; {:at millis, :names [...]}

(defn mirror
  "The mirror for `details`, created on first use."
  [details]
  (let [k (mirror-key details)]
    (or (get @mirrors k)
        (locking mirrors
          (or (get @mirrors k)
              (let [m (create-mirror k)]
                (swap! mirrors assoc k m)
                m))))))

(defn reader-jdbc-spec
  "clojure.java.jdbc-style spec the Metabase connection pool uses: the SELECT-only user of this mirror."
  [details]
  (let [{k :key, :keys [reader-password]} (mirror details)]
    {:classname   "org.h2.Driver"
     :subprotocol "h2"
     :subname     (str "mem:mbdynamodb_" k ";IFEXISTS=TRUE")
     :user        reader-user
     :password    reader-password}))

(defmacro with-read-lock
  "Run `body` while no table of the mirror for `details` is being swapped in."
  [details & body]
  `(let [lock# (.readLock ^ReentrantReadWriteLock (:lock (mirror ~details)))]
     (.lock lock#)
     (try
       ~@body
       (finally (.unlock lock#)))))

;;; ------------------------------------------------ type inference -------------------------------------------------

(defn- number-kind [^String n]
  (if (re-matches #"-?\d{1,18}" n) :int :dec))

(def ^:private date-re #"\d{4}-\d{2}-\d{2}")
(def ^:private datetime-re #"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?")
(def ^:private datetime-tz-re #"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:?\d{2})")

(defn- string-kind
  "Classify a DynamoDB string value: :date, :ts, :tstz or :str."
  [^String s]
  (cond
    (re-matches date-re s)        :date
    (re-matches datetime-re s)    :ts
    (re-matches datetime-tz-re s) :tstz
    :else                         :str))

(defn- value-kind [^AttributeValue av]
  (case (ddb/attribute-type av)
    "S"    (string-kind (.s av))
    "N"    (number-kind (.n av))
    "BOOL" :bool
    "NUL"  :null
    :other))

(defn- column-type
  "H2 column type for an attribute whose values had these `kinds`."
  [kinds]
  (let [kinds (disj kinds :null)]
    (cond
      (empty? kinds)                     :varchar
      (= kinds #{:int})                  :bigint
      (every? #{:int :dec} kinds)        :decfloat
      (= kinds #{:bool})                 :boolean
      (= kinds #{:date})                 :date
      (= kinds #{:ts})                   :timestamp
      (= kinds #{:tstz})                 :timestamptz
      :else                              :varchar)))

(def ^:private h2-type
  {:varchar     "CHARACTER VARYING"
   :bigint      "BIGINT"
   :decfloat    "DECFLOAT"
   :boolean     "BOOLEAN"
   :date        "DATE"
   :timestamp   "TIMESTAMP"
   :timestamptz "TIMESTAMP WITH TIME ZONE"})

;;; ------------------------------------------------ value conversion ----------------------------------------------

(defn- bytes->base64 [^SdkBytes b]
  (.encodeToString (Base64/getEncoder) (.asByteArray b)))

(defn- ->clj
  "AttributeValue -> plain Clojure data, for JSON rendering of lists, maps and sets."
  [^AttributeValue av]
  (case (ddb/attribute-type av)
    "S"    (.s av)
    "N"    (BigDecimal. (.n av))
    "BOOL" (.bool av)
    "NUL"  nil
    "B"    (bytes->base64 (.b av))
    "SS"   (vec (.ss av))
    "NS"   (mapv #(BigDecimal. ^String %) (.ns av))
    "BS"   (mapv bytes->base64 (.bs av))
    "M"    (into (sorted-map) (map (fn [[k v]] [k (->clj v)])) (.m av))
    "L"    (mapv ->clj (.l av))
    nil))

(defn- ->text ^String [^AttributeValue av]
  (case (ddb/attribute-type av)
    "S"    (.s av)
    "N"    (.n av)
    "BOOL" (str (.bool av))
    "NUL"  nil
    (json/encode (->clj av))))

(defn- parse-tstz ^OffsetDateTime [^String s]
  (let [s (str/replace-first s " " "T")
        ;; +0200 -> +02:00
        s (str/replace s #"([+-]\d{2})(\d{2})$" "$1:$2")]
    (OffsetDateTime/parse s)))

(defn- ->column-value
  "Value to bind for column type `ctype`."
  [ctype ^AttributeValue av]
  (when (and av (not= "NUL" (ddb/attribute-type av)))
    (case ctype
      :bigint      (Long/parseLong (.n av))
      :decfloat    (BigDecimal. (.n av))
      :boolean     (.bool av)
      :date        (LocalDate/parse (.s av))
      :timestamp   (LocalDateTime/parse (str/replace-first (.s av) " " "T"))
      :timestamptz (parse-tstz (.s av))
      :varchar     (->text av))))

;;; ------------------------------------------------ loading --------------------------------------------------------

(defn- infer-columns
  "[[name ctype] ...] for `items`, key attributes first, the rest in order of first appearance."
  [key-names items]
  (let [order (volatile! (vec key-names))
        seen  (volatile! (set key-names))
        kinds (volatile! {})]
    (doseq [^Map item items
            [attr av] item]
      (when-not (contains? @seen attr)
        (vswap! seen conj attr)
        (vswap! order conj attr))
      (vswap! kinds update attr (fnil conj #{}) (value-kind av)))
    (let [names (vec @order)]
      (when (> (count names) max-columns)
        (log/warnf "DynamoDB table has %d attributes; only the first %d are available in Metabase"
                   (count names) max-columns))
      (mapv (fn [n] [n (column-type (get @kinds n #{}))])
            (take max-columns names)))))

(defn- insert-rows! [^Connection conn ^String table columns items]
  (let [sql (format "INSERT INTO %s (%s) VALUES (%s)"
                    (quote-id table)
                    (str/join ", " (map (comp quote-id first) columns))
                    (str/join ", " (repeat (count columns) "?")))]
    (with-open [^PreparedStatement ps (.prepareStatement conn sql)]
      (doseq [batch (partition-all 1000 items)]
        (doseq [^Map item batch]
          (doseq [[i [col ctype]] (map-indexed vector columns)]
            (.setObject ps (int (inc i)) (->column-value ctype (.get item col))))
          (.addBatch ps))
        (.executeBatch ps)))))

(defn- swap-in! [m ^Connection conn ^String staging ^String table]
  (let [wlock (.writeLock ^ReentrantReadWriteLock (:lock m))]
    (.lock wlock)
    (try
      (execute! conn (str "DROP TABLE IF EXISTS " (quote-id table)))
      (execute! conn (format "ALTER TABLE %s RENAME TO %s" (quote-id staging) (quote-id table)))
      (finally (.unlock wlock)))))

(defn load-table!
  "Read `table-name` from DynamoDB into the mirror for `details`."
  [details table-name]
  (let [m       (mirror details)
        ^Connection conn (:admin-conn m)
        limit   (max-rows details)
        staging (str staging-prefix (str/replace (str (UUID/randomUUID)) "-" ""))
        started (System/currentTimeMillis)
        mb      (quot (max-bytes details) (* 1024 1024))
        {:keys [items truncated? key-names cut-by bytes]}
        (ddb/with-client [client details]
          (assoc (ddb/scan client table-name limit (max-bytes details))
                 :key-names (ddb/key-attribute-names client table-name)))
        columns (infer-columns key-names items)]
    (when truncated?
      (if (= cut-by :bytes)
        (log/warnf "DynamoDB table %s is larger than %d MB; Metabase sees the first %d items (%d MB). Raise \"Maximum MB read per table\" only if the Metabase JVM has enough memory: the in-memory copy takes several times the data size."
                   table-name mb (count items) (quot bytes (* 1024 1024)))
        (log/warnf "DynamoDB table %s has more than %d items; Metabase sees the first %d (raise \"Maximum rows read per table\" to see more)"
                   table-name limit limit)))
    (try
      (execute! conn (format "CREATE TABLE %s (%s)"
                             (quote-id staging)
                             (str/join ", " (for [[col ctype] columns]
                                              (str (quote-id col) " " (h2-type ctype))))))
      (insert-rows! conn staging columns items)
      (swap-in! m conn staging table-name)
      (catch Throwable e
        (execute! conn (str "DROP TABLE IF EXISTS " (quote-id staging)))
        (throw e)))
    (swap! (:loaded m) assoc table-name (System/currentTimeMillis))
    (log/infof "Loaded DynamoDB table %s: %d items (about %d KB), %d columns in %d ms"
               table-name (count items) (quot bytes 1024) (count columns) (- (System/currentTimeMillis) started))))

(defn- stale? [details loaded-at]
  (or (nil? loaded-at)
      (> (- (System/currentTimeMillis) loaded-at) (refresh-ms details))))

(defn table-names
  "DynamoDB table names for `details`, cached for a minute."
  [details]
  (let [m (mirror details)
        {:keys [at names]} @(:table-names m)]
    (if (and at (< (- (System/currentTimeMillis) at) 60000))
      names
      (let [names (ddb/with-client [client details] (ddb/list-table-names client))]
        (reset! (:table-names m) {:at (System/currentTimeMillis), :names names})
        names))))

(defn ensure-tables!
  "Make sure the mirror has a fresh copy of each of `names`."
  [details names]
  (let [m (mirror details)]
    (locking (:load-lock m)
      (doseq [table names
              :when (stale? details (get @(:loaded m) table))]
        (load-table! details table)))))

(defn refresh-all!
  "Load every DynamoDB table that is stale and drop copies of tables that no longer exist. Used before sync."
  [details]
  (reset! (:table-names (mirror details)) nil)
  (let [m        (mirror details)
        names    (table-names details)
        existing (set names)]
    ;; one unreadable table (e.g. IAM allows ListTables but not Scan on it) must not break sync of the others
    (doseq [table names]
      (try
        (ensure-tables! details [table])
        (catch Throwable e
          (log/warnf "Skipping DynamoDB table %s: %s" table (ex-message e)))))
    (locking (:load-lock m)
      (doseq [gone (remove existing (keys @(:loaded m)))]
        (execute! (:admin-conn m) (str "DROP TABLE IF EXISTS " (quote-id gone)))
        (swap! (:loaded m) dissoc gone)))))

(defn tables-referenced-by
  "DynamoDB table names that appear in `sql`. Deliberately generous: loading an extra table is harmless."
  [details ^String sql]
  (let [lower (str/lower-case (or sql ""))]
    (filter #(str/includes? lower (str/lower-case %)) (table-names details))))

(defn staging-table?
  "True for temporary tables used while loading."
  [table-name]
  (str/starts-with? (str table-name) staging-prefix))
