(ns metabase.driver.dynamodb.client
  "Thin wrapper around the AWS SDK v2 DynamoDB client: connecting, listing tables, describing keys and scanning."
  (:require
   [clojure.string :as str])
  (:import
   (java.net URI)
   (java.util Map)
   (software.amazon.awssdk.auth.credentials AwsBasicCredentials DefaultCredentialsProvider StaticCredentialsProvider)
   (software.amazon.awssdk.http.apache ApacheHttpClient)
   (software.amazon.awssdk.regions Region)
   (software.amazon.awssdk.services.dynamodb DynamoDbClient)
   (software.amazon.awssdk.services.dynamodb.model AttributeValue DescribeTableRequest KeySchemaElement
                                                   ListTablesRequest ListTablesResponse ScanRequest ScanResponse
                                                   TableDescription)))

(set! *warn-on-reflection* true)

(defn- blank->nil [s]
  (when (and (string? s) (not (str/blank? s)))
    (str/trim s)))

(defn region
  "The AWS region from connection `details`."
  [details]
  (or (blank->nil (:region details)) "us-east-1"))

(defn endpoint
  "The custom endpoint URL from connection `details`, or nil."
  [details]
  (blank->nil (:endpoint details)))

(defn endpoint-host
  "The host this driver will open connections to for `details`."
  [details]
  (if-let [e (endpoint details)]
    (or (.getHost (URI/create e)) e)
    (str "dynamodb." (region details) ".amazonaws.com")))

(defn- credentials-provider [details]
  (let [key-id (blank->nil (:access-key-id details))
        secret (blank->nil (:secret-access-key details))]
    (if (and key-id secret)
      (StaticCredentialsProvider/create (AwsBasicCredentials/create key-id secret))
      (.build (DefaultCredentialsProvider/builder)))))

(defn make-client
  "Build a new DynamoDB client for connection `details`. Close it when done."
  ^DynamoDbClient [details]
  (let [builder (-> (DynamoDbClient/builder)
                    (.region (Region/of (region details)))
                    (.credentialsProvider (credentials-provider details))
                    (.httpClientBuilder (ApacheHttpClient/builder)))]
    (when-let [e (endpoint details)]
      (.endpointOverride builder (URI/create e)))
    (.build builder)))

(defmacro with-client
  "Evaluate `body` with `binding` bound to a fresh DynamoDB client for `details`, closing it afterwards."
  [[binding details] & body]
  `(let [~binding (make-client ~details)]
     (try
       ~@body
       (finally
         (.close ~binding)))))

(defn list-table-names
  "All table names in the account/region, following pagination."
  [^DynamoDbClient client]
  (loop [start nil, acc []]
    (let [^ListTablesRequest req (cond-> (ListTablesRequest/builder)
                                   start (.exclusiveStartTableName ^String start)
                                   true  (.build))
          ^ListTablesResponse resp (.listTables client req)
          acc (into acc (.tableNames resp))]
      (if-let [next-start (.lastEvaluatedTableName resp)]
        (recur next-start acc)
        acc))))

(defn can-connect?
  "True if the credentials can list tables."
  [details]
  (with-client [client details]
    (.listTables client ^ListTablesRequest (.build (.limit (ListTablesRequest/builder) (int 1))))
    true))

(defn key-attribute-names
  "Names of the table's key attributes, partition key first."
  [^DynamoDbClient client ^String table-name]
  (let [^TableDescription desc (.table (.describeTable client ^DescribeTableRequest
                                                       (.build (.tableName (DescribeTableRequest/builder) table-name))))
        elems (sort-by (fn [^KeySchemaElement e] (if (= "HASH" (str (.keyType e))) 0 1))
                       (.keySchema desc))]
    (mapv (fn [^KeySchemaElement e] (.attributeName e)) elems)))

(declare attribute-type)

(defn- value-size
  "Approximate size of `av` in bytes, close to how DynamoDB counts item size (strings by character, not UTF-8)."
  ^long [^AttributeValue av]
  (case (attribute-type av)
    "S"    (count (.s av))
    "N"    (count (.n av))
    "B"    (alength (.asByteArray (.b av)))
    "SS"   (reduce + 0 (map count (.ss av)))
    "NS"   (reduce + 0 (map count (.ns av)))
    "BS"   (reduce + 0 (map (fn [^software.amazon.awssdk.core.SdkBytes b] (alength (.asByteArray b))) (.bs av)))
    "M"    (reduce + 3 (map (fn [[^String k v]] (+ (count k) (value-size v))) (.m av)))
    "L"    (reduce + 3 (map value-size (.l av)))
    1))

(defn item-size
  "Approximate size of a DynamoDB item in bytes: attribute names plus values."
  ^long [^Map item]
  (reduce + 0 (map (fn [[^String k v]] (+ (count k) (value-size v))) item)))

(defn scan
  "Read `table-name` until `max-items` items or about `max-bytes` of item data have been read, whichever comes first.
  Returns {:items [Map ...] :truncated? bool :bytes n :cut-by #{:rows :bytes}}. Each item is a java.util.Map of
  attribute name -> AttributeValue. The byte limit is checked per page (up to 1000 items), so it can be exceeded by
  at most one page."
  [^DynamoDbClient client ^String table-name max-items max-bytes]
  (loop [start-key nil, items (transient []), n 0, bytes 0]
    (let [remaining (- max-items n)
          ^ScanRequest req (cond-> (ScanRequest/builder)
                             true      (.tableName table-name)
                             true      (.limit (int (min 1000 (max 1 remaining))))
                             start-key (.exclusiveStartKey ^Map start-key)
                             true      (.build))
          ^ScanResponse resp (.scan client req)
          page  (.items resp)
          items (reduce conj! items page)
          n     (+ n (count page))
          bytes (+ bytes (long (reduce + 0 (map item-size page))))
          lek   (.lastEvaluatedKey resp)
          more? (boolean (and (.hasLastEvaluatedKey resp) (seq lek)))]
      (cond
        (and more? (< n max-items) (< bytes max-bytes)) (recur lek items n bytes)
        :else {:items      (persistent! items)
               :truncated? more?
               :bytes      bytes
               :cut-by     (cond (not more?) nil (>= bytes max-bytes) :bytes :else :rows)}))))

(defn attribute-type
  "The DynamoDB type of `av` as a string: S N B SS NS BS M L BOOL NUL."
  [^AttributeValue av]
  (str (.type av)))
