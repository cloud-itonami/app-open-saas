from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel
from typing import Any, Dict, List, Optional
import uuid
import json
import hashlib

# Integration with py kotodama for kotoba datomic substrate
try:
    from kotodama import kotoba_datomic
except ImportError:
    # Fallback/Mock for dry runs if kotodama module isn't strictly available in the python path
    class MockKotobaDatomic:
        def transact(self, graph_id: str, tx_data: list, session_token: str = None):
            print(f"[MOCK] transact {graph_id} -> {json.dumps(tx_data)}")
            return {"tx-data": tx_data}
        def q(self, graph_id: str, query: str, args: list = None):
            print(f"[MOCK] q {graph_id} -> {query}")
            return []
    kotoba_datomic = MockKotobaDatomic()

app = FastAPI(title="Salesforce Cleanroom API", version="58.0", description="Cleanroom API for Salesforce standard objects using py kotodama")

GRAPH_ID = "did:web:salesforce-opensaas.etzhayyim.com"

# Standard SObjects defined in the kotoba cleanroom schema
SOBJECT_MAPPING = {
    "Account": "account",
    "Contact": "contact",
    "Lead": "lead",
    "Opportunity": "opportunity",
    "Case": "case",
}

# Mock schema for Describe API
MOCK_DESCRIBE_SCHEMA = {
    "Account": [
        {"name": "Id", "type": "id", "updateable": False, "createable": False},
        {"name": "Name", "type": "string", "updateable": True, "createable": True},
        {"name": "Type", "type": "picklist", "updateable": True, "createable": True},
        {"name": "Industry", "type": "picklist", "updateable": True, "createable": True},
    ],
    "Contact": [
        {"name": "Id", "type": "id", "updateable": False, "createable": False},
        {"name": "AccountId", "type": "reference", "updateable": True, "createable": True, "referenceTo": ["Account"]},
        {"name": "FirstName", "type": "string", "updateable": True, "createable": True},
        {"name": "LastName", "type": "string", "updateable": True, "createable": True},
        {"name": "Email", "type": "email", "updateable": True, "createable": True},
    ],
    "Lead": [
        {"name": "Id", "type": "id", "updateable": False, "createable": False},
        {"name": "FirstName", "type": "string", "updateable": True, "createable": True},
        {"name": "LastName", "type": "string", "updateable": True, "createable": True},
        {"name": "Company", "type": "string", "updateable": True, "createable": True},
        {"name": "Status", "type": "picklist", "updateable": True, "createable": True},
    ],
    "Opportunity": [
        {"name": "Id", "type": "id", "updateable": False, "createable": False},
        {"name": "AccountId", "type": "reference", "updateable": True, "createable": True, "referenceTo": ["Account"]},
        {"name": "Name", "type": "string", "updateable": True, "createable": True},
        {"name": "StageName", "type": "picklist", "updateable": True, "createable": True},
        {"name": "Amount", "type": "currency", "updateable": True, "createable": True},
        {"name": "CloseDate", "type": "date", "updateable": True, "createable": True},
    ],
    "Case": [
        {"name": "Id", "type": "id", "updateable": False, "createable": False},
        {"name": "AccountId", "type": "reference", "updateable": True, "createable": True, "referenceTo": ["Account"]},
        {"name": "ContactId", "type": "reference", "updateable": True, "createable": True, "referenceTo": ["Contact"]},
        {"name": "Subject", "type": "string", "updateable": True, "createable": True},
        {"name": "Status", "type": "picklist", "updateable": True, "createable": True},
        {"name": "Priority", "type": "picklist", "updateable": True, "createable": True},
    ],
}

# ── PII split (ADR-0018) ─────────────────────────────────────────────────────
#
# The TypeScript AT-record path (src/salesforce-domain.ts) refuses a lead whose
# emailHash is not "sha256:<hex>" -- "raw PII is rejected". This module writes to
# the SAME substrate (GRAPH_ID above is this appview's own DID), so if it stored
# the raw Email a Salesforce client sends, the repo's headline claim would hold on
# one side only. The wire stays Salesforce-compatible (MOCK_DESCRIBE_SCHEMA still
# advertises Email); what changes is what lands in the substrate.
PII_HASHED_FIELDS = {"email": "email_hash", "phone": "phone_hash"}

# Attributes this API may write, per kotoba namespace. This must be a subset of
# salesforce-schema.kotoba.edn -- test/open_saas/contracts_test.cljs checks both
# directions. Without an allowlist the mapper wrote whatever key the client sent,
# so the schema constrained nothing about what actually reached the substrate.
ATTR_ALLOWLIST = {
    "account": {"id", "name", "type", "industry"},
    "contact": {"id", "account_id", "first_name", "last_name", "email_hash"},
    "lead": {"id", "first_name", "last_name", "company", "status"},
    "opportunity": {"id", "account_id", "name", "stage_name", "amount", "close_date"},
    "case": {"id", "account_id", "contact_id", "subject", "status", "priority"},
}

# CamelCase wire field -> snake_case schema attribute. "contactid" was missing
# before the allowlist existed, so Case.ContactId silently landed as
# :case/contactid -- an attribute the schema never declared.
FIELD_TO_ATTR = {
    "stagename": "stage_name",
    "closedate": "close_date",
    "firstname": "first_name",
    "lastname": "last_name",
    "accountid": "account_id",
    "contactid": "contact_id",
}


def hash_channel(value):
    """sha256:-prefixed digest of a contact channel.

    A value that already carries the prefix is passed through, so a client that
    performed the split itself is not hashed twice.
    """
    if isinstance(value, str) and value.startswith("sha256:"):
        return value
    return "sha256:" + hashlib.sha256(str(value).encode("utf-8")).hexdigest()


def to_kotoba_entity(sobject_name: str, sobject_id: str, data: dict):
    """Maps Salesforce JSON payloads to Kotoba Datomic entities based on cleanroom schema."""
    namespace = SOBJECT_MAPPING.get(sobject_name)
    if not namespace:
        raise ValueError(f"Unknown sobject: {sobject_name}")

    allowed = ATTR_ALLOWLIST[namespace]
    entity = {f":{namespace}/id": sobject_id}
    for k, v in data.items():
        key = k.lower()
        attr = FIELD_TO_ATTR.get(key, key)
        if attr in PII_HASHED_FIELDS:
            attr = PII_HASHED_FIELDS[attr]
            v = hash_channel(v)
        if attr not in allowed:
            raise ValueError(
                f"{sobject_name}.{k} maps to :{namespace}/{attr}, which the cleanroom "
                f"schema does not declare -- refusing to write an undeclared attribute"
            )
        entity[f":{namespace}/{attr}"] = v

    return entity

@app.get("/services/data/v58.0/sobjects/{sobject_name}/{id}")
async def get_sobject(sobject_name: str, id: str):
    namespace = SOBJECT_MAPPING.get(sobject_name)
    if not namespace:
        raise HTTPException(status_code=404, detail="SObject not found")
        
    query = f"""
    [:find (pull ?e [*])
     :in $ ?id
     :where [?e :{namespace}/id ?id]]
    """
    try:
        results = kotoba_datomic.q(GRAPH_ID, query, [id])
        if not results or not results[0]:
            raise HTTPException(status_code=404, detail="Not Found")
        
        ent = results[0][0]
        res = {"Id": id, "attributes": {"type": sobject_name, "url": f"/services/data/v58.0/sobjects/{sobject_name}/{id}"}}
        for k, v in ent.items():
            if k == ":db/id" or k == f":{namespace}/id":
                continue
            # Reverse mapping to CamelCase output
            key = k.split("/")[-1].title().replace("_", "")
            if key.endswith("Id"):
                key = key[:-2] + "Id"  # Keep 'Id' cased properly
            res[key] = v
            
        return res
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/services/data/v58.0/sobjects/{sobject_name}")
async def create_sobject(sobject_name: str, request: Request):
    data = await request.json()
    namespace = SOBJECT_MAPPING.get(sobject_name)
    if not namespace:
        raise HTTPException(status_code=404, detail="SObject not found")
        
    # Generate 18-char SFDC-like ID or fallback to standard UUID
    new_id = str(uuid.uuid4())[:18]
    try:
        entity = to_kotoba_entity(sobject_name, new_id, data)
        kotoba_datomic.transact(GRAPH_ID, [entity])
        return {"id": new_id, "success": True, "errors": []}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.patch("/services/data/v58.0/sobjects/{sobject_name}/{id}")
async def update_sobject(sobject_name: str, id: str, request: Request):
    data = await request.json()
    namespace = SOBJECT_MAPPING.get(sobject_name)
    if not namespace:
        raise HTTPException(status_code=404, detail="SObject not found")
        
    try:
        entity = to_kotoba_entity(sobject_name, id, data)
        # :db.unique/identity naturally upserts the entity
        kotoba_datomic.transact(GRAPH_ID, [entity])
        return {}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.delete("/services/data/v58.0/sobjects/{sobject_name}/{id}")
async def delete_sobject(sobject_name: str, id: str):
    namespace = SOBJECT_MAPPING.get(sobject_name)
    if not namespace:
        raise HTTPException(status_code=404, detail="SObject not found")
    
    try:
        query = f"[:find ?e :in $ ?id :where [?e :{namespace}/id ?id]]"
        results = kotoba_datomic.q(GRAPH_ID, query, [id])
        if not results:
            raise HTTPException(status_code=404, detail="Not Found")
            
        eid = results[0][0]
        kotoba_datomic.transact(GRAPH_ID, [[":db.fn/retractEntity", eid]])
        return {}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.get("/services/data/v58.0/query/")
async def query_soql(q: str):
    """
    Cleanroom basic SOQL parser mapped to kotoba datomic pull.
    Example SOQL: SELECT Name, StageName FROM Opportunity
    """
    q_lower = q.lower()
    if not q_lower.startswith("select ") or " from " not in q_lower:
        raise HTTPException(status_code=400, detail="Malformed SOQL")
        
    parts = q_lower.split(" from ")
    select_clause = parts[0].replace("select ", "").strip()
    rest = parts[1].strip().split(" ")
    sobject_name_raw = rest[0]
    
    sobject_name = next((k for k in SOBJECT_MAPPING if k.lower() == sobject_name_raw.lower()), None)
    if not sobject_name:
        raise HTTPException(status_code=400, detail=f"Unknown SObject in SOQL: {sobject_name_raw}")
        
    namespace = SOBJECT_MAPPING[sobject_name]
    
    # We execute a blanket wildcard pull for the cleanroom PoC
    datalog_query = f"[:find (pull ?e [*]) :where [?e :{namespace}/id _]]"
    
    try:
        results = kotoba_datomic.q(GRAPH_ID, datalog_query)
        records = []
        for row in results:
            ent = row[0]
            rec = {"attributes": {"type": sobject_name, "url": f"/services/data/v58.0/sobjects/{sobject_name}/{ent.get(f':{namespace}/id')}"}}
            for k, v in ent.items():
                if k == ":db/id":
                    continue
                key = k.split("/")[-1].title().replace("_", "")
                if key.endswith("Id"):
                    key = key[:-2] + "Id"
                rec[key] = v
            records.append(rec)
            
        return {"totalSize": len(records), "done": True, "records": records}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/services/data/v58.0/composite/")
async def composite_api(request: Request):
    """
    Salesforce Composite API endpoint.
    Executes a series of REST API requests in a single call.
    """
    body = await request.json()
    composite_requests = body.get("compositeRequest", [])
    
    if not composite_requests:
        raise HTTPException(status_code=400, detail="Missing compositeRequest array")
        
    responses = []
    
    # Process sequentially for the PoC (handling reference IDs like @{ref.id} is complex,
    # so this is a simplified batch executor for independent requests).
    for req in composite_requests:
        method = req.get("method", "").upper()
        url = req.get("url", "")
        ref_id = req.get("referenceId")
        req_body = req.get("body", {})
        
        # Route parsing
        # Example url: /services/data/v58.0/sobjects/Account
        url_parts = [p for p in url.split("/") if p]
        
        res_body = {}
        http_status = 400
        
        try:
            if "sobjects" in url_parts:
                idx = url_parts.index("sobjects")
                if len(url_parts) > idx + 1:
                    sobject_name = url_parts[idx + 1]
                    
                    if method == "POST" and len(url_parts) == idx + 2:
                        # Create
                        new_id = str(uuid.uuid4())[:18]
                        entity = to_kotoba_entity(sobject_name, new_id, req_body)
                        kotoba_datomic.transact(GRAPH_ID, [entity])
                        res_body = {"id": new_id, "success": True, "errors": []}
                        http_status = 201
                        
                    elif len(url_parts) == idx + 3:
                        record_id = url_parts[idx + 2]
                        if method == "PATCH":
                            # Update
                            entity = to_kotoba_entity(sobject_name, record_id, req_body)
                            kotoba_datomic.transact(GRAPH_ID, [entity])
                            res_body = {} # SFDC returns empty 204 on success
                            http_status = 204
                            
                        elif method == "DELETE":
                            # Delete
                            namespace = SOBJECT_MAPPING.get(sobject_name)
                            if namespace:
                                q_str = f"[:find ?e :in $ ?id :where [?e :{namespace}/id ?id]]"
                                q_res = kotoba_datomic.q(GRAPH_ID, q_str, [record_id])
                                if q_res:
                                    kotoba_datomic.transact(GRAPH_ID, [[":db.fn/retractEntity", q_res[0][0]]])
                                    http_status = 204
                                else:
                                    http_status = 404
                                    res_body = [{"errorCode": "NOT_FOUND", "message": "Record not found"}]
                                    
                        elif method == "GET":
                            # Read
                            # Note: To fully support this without code duplication, we should extract the logic from get_sobject.
                            # For this iteration, returning a stub indicating it needs routing.
                            http_status = 501
                            res_body = [{"errorCode": "NOT_IMPLEMENTED", "message": "GET in composite not fully wired in PoC"}]
            
            else:
                 http_status = 404
                 res_body = [{"errorCode": "NOT_FOUND", "message": f"URL {url} not found in PoC router"}]
                 
        except Exception as e:
            http_status = 500
            res_body = [{"errorCode": "INTERNAL_ERROR", "message": str(e)}]
            
        responses.append({
            "body": res_body,
            "httpHeaders": {},
            "httpStatusCode": http_status,
            "referenceId": ref_id
        })
        
    return {"compositeResponse": responses}
