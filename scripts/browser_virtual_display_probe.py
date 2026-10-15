#!/usr/bin/env python3
"""Probe Chromium WebView-shell on three leased displays of the Fedora emulator."""
import hashlib,http.client,json,secrets,subprocess,time
from pathlib import Path
serial="emulator-5580"
token=json.loads((Path.home()/".local/state/pocket-mcp/emulator-connection.json").read_text())["bearer_token"]
counter=0
def call(name,**args):
    global counter
    counter+=1
    c=http.client.HTTPConnection("127.0.0.1",18865,timeout=30)
    try:
        c.request("POST","/mcp",json.dumps({"jsonrpc":"2.0","id":counter,"method":"tools/call","params":{"name":name,"arguments":args}}),
            {"Authorization":"Bearer "+token,"Content-Type":"application/json","Accept":"application/json, text/event-stream"})
        body=json.loads(c.getresponse().read())["result"]
        if body.get("isError"):raise RuntimeError(name+": "+str(body["structuredContent"]))
        return body["structuredContent"]
    finally:c.close()
prefix=call("phone_capabilities")["operation_prefix"]
def op(name):return prefix+"_"+name+"_"+secrets.token_hex(10)
sessions=[]
result={"browser":"org.chromium.webview_shell","emulator":serial,"checks":[]}
try:
    for i in range(3):
        sid=call("phone_agent_create",operation_id=op("browser"))["session_id"]
        sessions.append(sid)
        lease=call("phone_agent_acquire_ui",session_id=sid,operation_id=op("lease"))
        result["checks"].append({"agent":i,"display":lease["display_id"],"stage":"lease"})
        launched=call("phone_open_app",session_id=sid,package_name="org.chromium.webview_shell",operation_id=op("launch"))
        result["checks"].append({"agent":i,"stage":"launch","result":launched["status"]})
    for i,sid in enumerate(sessions):
        current=None
        for attempt in range(16):
            current=call("phone_observe",session_id=sid,max_nodes=150)
            if current["package_name"]=="org.chromium.webview_shell":break
            time.sleep(.3)
        result["checks"].append({"agent":i,"stage":"observe","package":current["package_name"],"window_id":current["window_id"],"node_count":len(current["nodes"]),"node_samples":[n["text"][:65] for n in current["nodes"] if n["text"]][:9],"editables":[{"id":n["resource_id"],"text":n["text"],"role":n["role"]} for n in current["nodes"] if n["editable"]]})
    import concurrent.futures
    def edit(i):
        sid=sessions[i]
        snapshot=call("phone_observe",session_id=sid,max_nodes=150)
        field=next((n for n in snapshot["nodes"] if n["resource_id"].endswith("/url_field")),None)
        if field is None: raise AssertionError("Browser address field inaccessible")
        intended="https://agent"+str(i)+".example.invalid/"
        response=call("phone_act",session_id=sid,kind="type",snapshot_id=snapshot["snapshot_id"],
            selector={"resource_id":field["resource_id"]},text=intended,replace=True,operation_id=op("edit"))
        if response["status"]!="verified":raise AssertionError(response)
        return intended
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        changed=list(executor.map(edit,range(3)))
    for i,sid in enumerate(sessions):
        snap=call("phone_observe",session_id=sid,max_nodes=150)
        values=[n["text"] for n in snap["nodes"] if n["resource_id"].endswith("/url_field")]
        distinct=len(values)==1 and values[0]==changed[i] and all(changed[j]!=values[0] for j in range(3) if j!=i)
        result["checks"].append({"agent":i,"stage":"independent_url_edit","passed":distinct,"observed":values})
    result["passed"]=all(c["passed"] for c in result["checks"] if c["stage"]=="independent_url_edit") and len({c["window_id"] for c in result["checks"] if c["stage"]=="observe" and c["package"]=="org.chromium.webview_shell"})==3
except Exception as e:
    result["error"]=str(e)[:600]
    result["passed"]=False
finally:
    for sid in sessions:
        try:call("phone_agent_close",session_id=sid,operation_id=op("close"))
        except Exception as e:result.setdefault("cleanup_errors",[]).append(str(e)[:200])
    Path("artifacts/browser-virtual-display-probe.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result,indent=2))
