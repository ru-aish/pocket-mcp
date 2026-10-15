#!/usr/bin/env python3
"""Exercise disappearance/restart of Shizuku on the Fedora emulator only, no real phone."""
import http.client,json,secrets,subprocess,time
from pathlib import Path
SERIAL="emulator-5580"
token=json.loads((Path.home()/".local/state/pocket-mcp/emulator-connection.json").read_text())["bearer_token"]
n=0
def call(name,**args):
    global n
    n+=1
    c=http.client.HTTPConnection("127.0.0.1",18865,timeout=30)
    try:
        c.request("POST","/mcp",json.dumps({"jsonrpc":"2.0","id":n,"method":"tools/call","params":{"name":name,"arguments":args}}),{"Authorization":"Bearer "+token,"Content-Type":"application/json","Accept":"application/json, text/event-stream"})
        body=json.loads(c.getresponse().read())["result"]
        return body
    finally:c.close()
def ok(name,**args):
    response=call(name,**args)
    if response["isError"]:raise AssertionError(name+": "+str(response["structuredContent"]))
    return response["structuredContent"]
def op(prefix,stage):return prefix+"_"+stage+"_"+secrets.token_hex(8)
def adb(*args,check=True):
    run=subprocess.run(["adb","-s",SERIAL,*args],capture_output=True,text=True,timeout=35)
    if check and run.returncode:raise RuntimeError(str(args)+": "+run.stderr[:240])
    return run.stdout.strip()
prefix=ok("phone_capabilities")["operation_prefix"]
agent=ok("phone_agent_create",operation_id=op(prefix,"death"))["session_id"]
native=ok("phone_agent_create",operation_id=op(prefix,"native"))["session_id"]
report={"serial":SERIAL,"checks":[],"passed":False}
def assert_check(label,condition,detail=None):
    report["checks"].append({"name":label,"passed":bool(condition),"detail":detail})
    print(("PASS" if condition else "FAIL")+": "+label+" "+str(detail)[:160],flush=True)
    if not condition:raise AssertionError(label)
try:
    display=ok("phone_agent_acquire_ui",session_id=agent,operation_id=op(prefix,"lease"))["display_id"]
    assert_check("virtual screen leased before Shizuku death",display>0,display)
    pids=adb("shell","pidof","shizuku_server")
    assert_check("emulator Shizuku shell server found",bool(pids),pids)
    adb("shell","kill","-9",pids.split()[0])
    time.sleep(.8)
    state=ok("phone_agent_status",session_id=agent)
    assert_check("Shizuku death invalidates UI lease without losing agent session",not state["has_ui"],state)
    check_native=ok("phone_agent_status",session_id=native)
    assert_check("native-only agent session survives Shizuku death",not check_native["has_ui"],check_native)
    assert_check("direct native queries survive Shizuku death",len(ok("phone_apps")["apps"])>0)
    error=call("phone_agent_acquire_ui",session_id=agent,operation_id=op(prefix,"denied"))
    assert_check("UI fails closed when Shizuku unavailable",error["isError"],error["structuredContent"])
    rest=adb("shell","/data/local/tmp/shizuku_starter")
    time.sleep(1)
    reconnected=None
    for i in range(12):
        try:
            reconnected=ok("phone_agent_acquire_ui",session_id=agent,operation_id=op(prefix,"reconnect"))
            break
        except AssertionError:
            time.sleep(.35)
    assert_check("fresh UI lease succeeds after restarting Shizuku",reconnected is not None and reconnected["display_id"]>0,reconnected)
    report["passed"]=True
except Exception as e:
    report["error"]=str(e)[:500]
finally:
    for sid in (agent,native):
        try:ok("phone_agent_close",session_id=sid,operation_id=op(prefix,"close"))
        except Exception as e:report.setdefault("cleanup_errors",[]).append(str(e)[:160])
    report["displays_after"]=ok("phone_displays")["displays"]
    Path("artifacts/shizuku-death-recovery.json").write_text(json.dumps(report,indent=2)+"\n")
    print("REPORT:",json.dumps(report,indent=2),flush=True)
if not report["passed"]:raise SystemExit(1)
