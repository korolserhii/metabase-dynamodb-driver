"""End-to-end test: seed DynamoDB Local, connect Metabase to it through the driver, run real queries.

Expects DynamoDB Local on http://localhost:8000 and Metabase (with the driver in its plugins dir) on
http://localhost:3000. Exits non-zero on the first failure.
"""

import json
import sys
import time
import urllib.error
import urllib.request

import boto3

DDB_ENDPOINT = "http://localhost:8000"
MB = "http://localhost:3000"


def log(msg):
    print(msg, flush=True)


def fail(msg):
    log("FAIL: " + msg)
    sys.exit(1)


# --------------------------------------------------------------------------- seed DynamoDB Local

def seed():
    ddb = boto3.client("dynamodb", endpoint_url=DDB_ENDPOINT, region_name="us-east-1",
                       aws_access_key_id="dummy", aws_secret_access_key="dummy")
    for t in ddb.list_tables()["TableNames"]:
        ddb.delete_table(TableName=t)

    ddb.create_table(
        TableName="Orders",
        KeySchema=[{"AttributeName": "order_id", "KeyType": "HASH"}],
        AttributeDefinitions=[{"AttributeName": "order_id", "AttributeType": "S"}],
        BillingMode="PAY_PER_REQUEST")
    ddb.create_table(
        TableName="customer-events",
        KeySchema=[{"AttributeName": "customer", "KeyType": "HASH"},
                   {"AttributeName": "seq", "KeyType": "RANGE"}],
        AttributeDefinitions=[{"AttributeName": "customer", "AttributeType": "S"},
                              {"AttributeName": "seq", "AttributeType": "N"}],
        BillingMode="PAY_PER_REQUEST")

    # 30 orders over three months; amount = i*10 + 0.5 (DynamoDB stores 10.00 as 10, so use real fractions)
    for i in range(1, 31):
        month = 1 + (i - 1) // 10
        item = {
            "order_id": {"S": f"o-{i:03d}"},
            "amount": {"N": f"{i * 10}.5"},
            "quantity": {"N": str(i)},
            "paid": {"BOOL": i % 2 == 0},
            "created_at": {"S": f"2026-{month:02d}-{(i % 10) + 1:02d}T10:00:00Z"},
            "ship_date": {"S": f"2026-{month:02d}-{(i % 10) + 1:02d}"},
            "status": {"S": "new" if i % 3 else "shipped"},
            "tags": {"SS": ["a", "b"]},
            "customer": {"M": {"name": {"S": f"Customer {i}"}, "vip": {"BOOL": i > 25}}},
        }
        if i % 5 == 0:
            item["note"] = {"S": "fragile"}  # sparse attribute
        ddb.put_item(TableName="Orders", Item=item)

    # 2500 events: more than one 1000-item scan page
    with_batch = []
    for i in range(2500):
        with_batch.append({"PutRequest": {"Item": {
            "customer": {"S": f"c{i % 7}"},
            "seq": {"N": str(i)},
            "kind": {"S": ["view", "click", "buy"][i % 3]},
        }}})
        if len(with_batch) == 25:
            ddb.batch_write_item(RequestItems={"customer-events": with_batch})
            with_batch = []
    log("seeded DynamoDB Local")


# --------------------------------------------------------------------------- Metabase API

SESSION = None


def api(method, path, body=None, expect_ok=True):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(MB + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if SESSION:
        req.add_header("X-Metabase-Session", SESSION)
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        if expect_ok:
            fail(f"{method} {path} -> {e.code}: {raw[:2000]}")
        try:
            return json.loads(raw)
        except ValueError:
            return {"error": raw}


def wait_for_metabase():
    for _ in range(180):
        try:
            with urllib.request.urlopen(MB + "/api/health", timeout=5) as resp:
                if json.loads(resp.read().decode()).get("status") == "ok":
                    return
        except Exception:
            pass
        time.sleep(2)
    fail("Metabase did not start")


def setup_admin():
    global SESSION
    token = api("GET", "/api/session/properties")["setup-token"]
    res = api("POST", "/api/setup", {
        "token": token,
        "user": {"first_name": "E2E", "last_name": "Test", "email": "e2e@example.com",
                 "password": "Very-Strong-Passw0rd!", "site_name": "e2e"},
        "prefs": {"site_name": "e2e", "allow_tracking": False},
    })
    SESSION = res["id"]
    log("Metabase admin created")


def run(query, expect_ok=True):
    res = api("POST", "/api/dataset", query, expect_ok=expect_ok)
    status = res.get("status")
    if expect_ok and status != "completed":
        fail(f"query failed: {json.dumps(query)}\n{res.get('error')}")
    return res


def rows(res):
    return res["data"]["rows"]


def main():
    seed()
    wait_for_metabase()
    setup_admin()

    engines = api("GET", "/api/setting/engines")
    if "dynamodb" not in engines:
        fail("driver not offered in 'Add database'; engines: " + ", ".join(sorted(engines)))
    if engines["dynamodb"].get("superseded-by"):
        fail("driver is hidden from 'Add database' (superseded-by is set)")
    log("driver is listed in 'Add database' as: " + engines["dynamodb"]["driver-name"])

    bad = api("POST", "/api/database", {
        "engine": "dynamodb", "name": "bad", "details": {"region": "us-east-1", "endpoint": "http://localhost:1"},
    }, expect_ok=False)
    if "id" in (bad or {}):
        fail("a database with an unreachable endpoint was accepted")
    log("unreachable endpoint is rejected")

    db = api("POST", "/api/database", {
        "engine": "dynamodb",
        "name": "DynamoDB e2e",
        "details": {"region": "us-east-1", "endpoint": "http://localhost:8000",
                    "access-key-id": "dummy", "secret-access-key": "dummy"},
    })
    db_id = db["id"]
    log(f"database created: {db_id}")

    tables = {}
    for _ in range(90):
        meta = api("GET", f"/api/database/{db_id}/metadata")
        tables = {t["name"]: t for t in meta.get("tables", [])}
        if {"Orders", "customer-events"} <= set(tables) and all(tables[n]["fields"] for n in tables):
            break
        time.sleep(2)
    else:
        fail(f"sync did not find the tables; found {sorted(tables)}")
    log("synced tables: " + ", ".join(sorted(tables)))

    if any(n.startswith("__mbddb_") for n in tables):
        fail("internal staging table leaked into Metabase")

    orders = tables["Orders"]
    fields = {f["name"]: f for f in orders["fields"]}
    log("Orders fields: " + ", ".join(f"{n}:{f['base_type']}" for n, f in sorted(fields.items())))
    expected_types = {
        "order_id": "type/Text",
        "quantity": "type/BigInteger",
        "amount": "type/Float",
        "paid": "type/Boolean",
        "created_at": "type/DateTimeWithLocalTZ",
        "ship_date": "type/Date",
        "note": "type/Text",
    }
    for name, base_type in expected_types.items():
        if name not in fields:
            fail(f"field {name} missing")
        if fields[name]["base_type"] != base_type:
            fail(f"field {name}: expected {base_type}, got {fields[name]['base_type']}")

    # 1. count
    r = rows(run({"database": db_id, "type": "query",
                  "query": {"source-table": orders["id"], "aggregation": [["count"]]}}))
    if r[0][0] != 30:
        fail(f"count: expected 30, got {r}")
    log("count(Orders) = 30")

    # 2. sum with filter
    r = rows(run({"database": db_id, "type": "query",
                  "query": {"source-table": orders["id"],
                            "aggregation": [["sum", ["field", fields["amount"]["id"], None]]],
                            "filter": ["=", ["field", fields["paid"]["id"], None], True]}}))
    # paid = even i: sum(i*10) = 2400, plus 15 * 0.5
    if abs(float(r[0][0]) - 2407.5) > 0.001:
        fail(f"sum(amount) where paid: expected 2407.5, got {r}")
    log("sum(amount) where paid = 2407.5")

    # 3. group by month
    r = rows(run({"database": db_id, "type": "query",
                  "query": {"source-table": orders["id"], "aggregation": [["count"]],
                            "breakout": [["field", fields["created_at"]["id"], {"temporal-unit": "month"}]]}}))
    if [row[1] for row in r] != [10, 10, 10]:
        fail(f"count by month: expected [10, 10, 10], got {r}")
    log("count by month = 10/10/10")

    # 4. a table bigger than one scan page
    ev = tables["customer-events"]
    r = rows(run({"database": db_id, "type": "query",
                  "query": {"source-table": ev["id"], "aggregation": [["count"]]}}))
    if r[0][0] != 2500:
        fail(f"count(customer-events): expected 2500, got {r}")
    log("count(customer-events) = 2500 (multi-page scan)")

    # 5. native SQL
    r = rows(run({"database": db_id, "type": "native",
                  "native": {"query": 'SELECT "status", COUNT(*) FROM "Orders" GROUP BY "status" ORDER BY 1'}}))
    if r != [["new", 20], ["shipped", 10]]:
        fail(f"native group by: got {r}")
    log("native SQL works")

    # 6. map attributes come through as JSON text
    r = rows(run({"database": db_id, "type": "native",
                  "native": {"query": 'SELECT "customer" FROM "Orders" WHERE "order_id" = \'o-030\''}}))
    if json.loads(r[0][0]) != {"name": "Customer 30", "vip": True}:
        fail(f"map attribute: got {r}")
    log("map attribute is JSON")

    # 7. writes and server access are refused
    for sql in ['CREATE TABLE "x" ("a" INT)',
                'DELETE FROM "Orders"',
                "SELECT FILE_READ('/etc/passwd')",
                "CREATE ALIAS EXEC AS 'String e(String c) throws Exception { return c; }'"]:
        res = run({"database": db_id, "type": "native", "native": {"query": sql}}, expect_ok=False)
        if res.get("status") == "completed":
            fail(f"this should have been refused: {sql}")
    r = rows(run({"database": db_id, "type": "query",
                  "query": {"source-table": orders["id"], "aggregation": [["count"]]}}))
    if r[0][0] != 30:
        fail("data changed after refused writes")
    log("writes, file access and Java aliases are refused")

    # 8. new data shows up after a refresh
    ddb = boto3.client("dynamodb", endpoint_url=DDB_ENDPOINT, region_name="us-east-1",
                       aws_access_key_id="dummy", aws_secret_access_key="dummy")
    ddb.put_item(TableName="Orders", Item={"order_id": {"S": "o-999"}, "amount": {"N": "1"}})
    api("PUT", f"/api/database/{db_id}", {"details": {
        "region": "us-east-1", "endpoint": "http://localhost:8000",
        "access-key-id": "dummy", "secret-access-key": "dummy", "refresh-minutes": 0}})
    r = rows(run({"database": db_id, "type": "query",
                  "query": {"source-table": orders["id"], "aggregation": [["count"]]}}))
    if r[0][0] != 31:
        fail(f"new item not visible after refresh: {r}")
    log("refresh picks up new items")

    log("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
