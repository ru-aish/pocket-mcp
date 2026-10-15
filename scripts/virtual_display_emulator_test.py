#!/usr/bin/env python3
"""Emulator-only signed APK integration/benchmark: real MCP -> Shizuku -> virtual displays.
Only use serials prefixed emulator-. This script never operates on physical phones.
"""
import argparse
import base64
import hashlib
import http.client
import json
import secrets
import subprocess
import time
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument("--serial",default="emulator-5580")
p.add_argument("--port",type=int,default=18865)
p.add_argument("--connection",type=Path,default=Path.home()/".local/state/pocket-mcp/emulator-connection.json")
p.add_argument("--output",type=Path,default=Path("artifacts/virtual-display-validation.json"))
args=p.parse_args()
if not args.serial.startswith("emulator-"): raise SystemExit("Physical phone testing is forbidden")
conn=json.loads(args.connection.read_text())
token=conn["bearer_token"]
counter=0
result={"serial":args.serial,"started":int(time.time()),"checks":[],"benchmarks":[],"errors":[],"passed":False}
prefix=None
sessions=[]
def adb(*a):
    r=subprocess.run(["adb","-s",args.serial,*map(str,a)],capture_output=True,text=True,timeout=30)
    if r.returncode: raise RuntimeError(f"adb {a[0]} failed: {r.stderr[:300]}")
    return r.stdout

def rpc(name,values=None):
    global counter
    counter+=1
    payload=json.dumps({"jsonrpc":"2.0","id":counter,"method":"tools/call","params":{"name":name,"arguments":values or {}}})
    c=http.client.HTTPConnection("127.0.0.1",args.port,timeout=35)
    try:
        c.request("POST","/mcp",payload,{"Authorization":"Bearer "+token,"Content-Type":"application/json","Accept":"application/json, text/event-stream","MCP-Protocol-Version":"2025-11-25"})
        response=c.getresponse();body=json.loads(response.read())
        if response.status!=200 or "result" not in body: raise RuntimeError(f"Server failed on {name}: {response.status} {str(body)[:200]}")
        return body["result"]
    finally:
        c.close()
def call(name,**kw):
    r=rpc(name,kw)
    if r.get("isError"): raise RuntimeError(f"{name}: {r['structuredContent']}")
    return r["structuredContent"]
def negative(name,**kw):
    r=rpc(name,kw)
    if not r.get("isError"): raise AssertionError(f"Expected rejection: {name}: {r}")
    return r["structuredContent"]
def newop(label):
    return prefix+"_"+label+"_"+secrets.token_hex(8)
def check(name,ok,details=None):
    result["checks"].append({"name":name,"passed":bool(ok),"details":details})
    print(("PASS" if ok else "FAIL")+": "+name+((" "+str(details)[:180]) if details is not None else ""),flush=True)
    if not ok:raise AssertionError(f"{name}: {details}")

def benchmark(stage):
    report={"stage":stage,"time":time.time()}
    for process in ("dev.pocket.mcp","dev.pocket.mcp:display","shizuku_server","dev.pocket.fixture"):
        proc=subprocess.run(["adb","-s",args.serial,"shell","pidof",process],capture_output=True,text=True,timeout=20)
        ids=proc.stdout.strip().split()
        mem=adb("shell","dumpsys","meminfo",ids[0]) if ids else ""
        import re
        m=re.search(r"TOTAL PSS:\s*([\d,]+)",mem) or re.search(r"TOTAL\s+([\d,]+)",mem)
        report[process+"_pss_kb"]=int(m.group(1).replace(",","")) if m else None
    report["display_dump"]=adb("shell","cmd","display","get-displays","-i").strip()
    report["power_state"]=[line.strip() for line in adb("shell","dumpsys","power").splitlines() if "mWakefulness=" in line][:2]
    result["benchmarks"].append(report)
    print("BENCH:",{k:v for k,v in report.items() if k!="time"},flush=True)
    return report

def run():
    global prefix
    cap=call("phone_capabilities")
    prefix=cap["operation_prefix"]
    check("signed build has multi-agent capability",cap["agent_virtual_display"]["maximum_ui_agents"]==3,cap["agent_virtual_display"])
    baseline=call("phone_displays")
    check("physical display reserved, no idle virtual displays",len(baseline["displays"])==1 and baseline["displays"][0]["display_id"]==0,baseline)
    benchmark("baseline")
    one=call("phone_agent_create",operation_id=newop("agent"))
    sessions.append(one["session_id"])
    check("agent created without UI allocation",not one["has_ui"] and len(call("phone_displays")["displays"])==1,one["agent"])
    check("forged lease identity rejected",negative("phone_agent_acquire_ui",session_id="a"*32,operation_id=newop("forged"))["code"]=="NOT_READY")
    check("UI observation cannot implicitly control physical display",negative("phone_observe")["code"]=="INVALID_ARGUMENT")
    for i in range(2):
        x=call("phone_agent_create",operation_id=newop("agent"))
        sessions.append(x["session_id"])
    fourth=call("phone_agent_create",operation_id=newop("agent"))
    sessions.append(fourth["session_id"])
    check("four sessions supported, no display pre-allocation",len(call("phone_displays")["displays"])==1)
    import concurrent.futures
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        native=list(pool.map(lambda _: call("phone_apps"),range(3)))
    check("three concurrent native-only operations allocate no displays",
        len(native)==3 and all("apps" in item for item in native) and len(call("phone_displays")["displays"])==1)
    acquired=[]
    for i,sid in enumerate(sessions[:3]):
        for attempt in range(25):
            try:
                t0=time.monotonic()
                lease=call("phone_agent_acquire_ui",session_id=sid,operation_id=newop("acquire"))
                elapsed=(time.monotonic()-t0)*1000
                acquired.append(lease["display_id"])
                result["benchmarks"].append({"stage":"allocate","index":i,"ms":elapsed,"display_id":lease["display_id"]})
                print("LEASE",i,lease["display_id"],f"{elapsed:.1f} ms",flush=True)
                break
            except RuntimeError as err:
                if any(w in str(err) for w in ("binding","disconnected","Shizuku")) and attempt<24:
                    time.sleep(.4)
                    continue
                raise
    check("three distinct nonphysical display IDs",len(set(acquired))==3 and all(x>0 for x in acquired),acquired)
    displays=call("phone_displays")["displays"]
    check("display listing matches active lease IDs",{v["display_id"] for v in displays if v["role"]=="virtual"}==set(acquired))
    check("fourth UI allocation rejected",negative("phone_agent_acquire_ui",session_id=sessions[3],operation_id=newop("overflow"))["code"]=="NOT_READY")
    benchmark("three-idle-displays")
    adb("shell","am","start","-a","android.settings.SETTINGS")
    time.sleep(.4)
    def physical_top():
        import re
        section=adb("shell","dumpsys","activity","activities").split("Display #0 (activities from top to bottom):",1)[-1].split("Display #",1)[0]
        match=re.search(r"topResumedActivity=ActivityRecord\{[^}]+\s(\S+?)/(\S+?)\s",section)
        return match.group(1) if match else None
    expected_physical=physical_top()
    check("physical foreground prepared for isolation test",expected_physical=="com.android.settings",expected_physical)
    launch_results=[]
    for sid in sessions[:3]:
        v=call("phone_open_app",package_name="dev.pocket.fixture",session_id=sid,operation_id=newop("launch"))
        launch_results.append(v["status"])
    check("three fixture launches dispatched to leased displays",launch_results==["requested"]*3,launch_results)
    observed=[]
    observation_errors=[]
    for sid in sessions[:3]:
        state=None
        last_error=''
        for attempt in range(12):
            try:
                state=call("phone_observe",session_id=sid,max_nodes=120)
                if state["package_name"]=="dev.pocket.fixture": break
            except RuntimeError as e:
                last_error=str(e)[:500]
            time.sleep(.3)
        observed.append(state)
        observation_errors.append(last_error)
    result["observation_errors"]=observation_errors
    result["activities_snapshot"]=[line for line in adb("shell","dumpsys","activity","activities").splitlines() if "Display #" in line or "dev.pocket.fixture" in line][:90]
    result["windows_snapshot"]=[line for line in adb("shell","dumpsys","window","displays").splitlines() if "Display:" in line or "dev.pocket.fixture" in line][:100]
    check("independent accessibility windows across three virtual screens",
        all(s and s["package_name"]=="dev.pocket.fixture" and s["window_id"]!=-1 for s in observed),
        [(x.get("package_name"),x.get("window_id")) if x else None for x in observed])
    check("agent windows are not same target",len({s["window_id"] for s in observed})==3,[s["window_id"] for s in observed])
    def enter_on_agent(i):
        sid=sessions[i]
        state=call("phone_observe",session_id=sid,max_nodes=300)
        match=[n for n in state["nodes"] if n["resource_id"].endswith("/mcp_test_input")]
        if len(match)!=1: raise AssertionError("Agent input missing: "+str(i))
        phrase="AgentDisplay_"+str(i)
        result=call("phone_act",session_id=sid,kind="type",snapshot_id=state["snapshot_id"],
            selector={"resource_id":match[0]["resource_id"]},text=phrase,replace=True,operation_id=newop("text"))
        return result,phrase
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        typed=list(pool.map(enter_on_agent,range(3)))
    check("three agents concurrently perform independent text actions",
        all(t[0]["status"]=="verified" for t in typed),[t[0] for t in typed])
    independent=[]
    for idx,sid in enumerate(sessions[:3]):
        fresh=call("phone_observe",session_id=sid,max_nodes=300)
        alltexts=" ".join(n["text"] for n in fresh["nodes"])
        independent.append(typed[idx][1] in alltexts and all(typed[j][1] not in alltexts for j in range(3) if j!=idx))
    check("three same-app windows retain distinct UI state",all(independent),independent)
    first=call("phone_observe",session_id=sessions[0],max_nodes=300)
    hit=[n for n in first["nodes"] if n["resource_id"].endswith("/mcp_test_click")]
    if len(hit)!=1:raise AssertionError("Tap target unavailable")
    bounds=hit[0]["bounds"]
    tapped=call("phone_act",session_id=sessions[0],kind="tap",snapshot_id=first["snapshot_id"],
        x=(bounds["left"]+bounds["right"])//2,y=(bounds["top"]+bounds["bottom"])//2,
        operation_id=newop("targettap"))
    time.sleep(.3)
    post=call("phone_observe",session_id=sessions[0],max_nodes=300)
    alltexts=" ".join(n["text"] for n in post["nodes"])
    check("display-targeted gesture changes only virtual app",tapped["status"]=="dispatched" and
        "COUNT:1" in alltexts,tapped)
    for other in sessions[1:3]:
        different=call("phone_observe",session_id=other,max_nodes=300)
        other_text=" ".join(n["text"] for n in different["nodes"])
        check("gesture does not activate other agent window","COUNT:1" not in other_text)
    physical_after=physical_top()
    result["physical_foreground"]={"before":expected_physical,"after":physical_after}
    check("physical display remains on human's app",physical_after==expected_physical,result["physical_foreground"])
    t0=time.monotonic()
    captured=call("phone_observe",session_id=sessions[0],include_image=True,max_image_dimension=720,max_nodes=0)
    screenshot_ms=(time.monotonic()-t0)*1000
    result["benchmarks"].append({"stage":"on_demand_screenshot","latency_ms":screenshot_ms})
    check("leased virtual display screenshot emitted",captured["image_width"]>0 and captured["image_height"]>0,captured.get("image_width"))
    image_hashes=[]
    screenshot_latencies=[]
    for sid in sessions[:3]:
        time.sleep(1.1)  # Accessibility screenshots are Android-rate-limited, even across displays.
        t0=time.monotonic()
        response=rpc("phone_observe",{"session_id":sid,"include_image":True,"max_image_dimension":720,"max_nodes":0})
        screenshot_latencies.append((time.monotonic()-t0)*1000)
        items=[v for v in response["content"] if v["type"]=="image"]
        if len(items)!=1:raise AssertionError("Expected exactly one JPEG frame from display; response="+str(response.get("structuredContent",response))[:420])
        image_hashes.append(hashlib.sha256(base64.b64decode(items[0]["data"])).hexdigest())
    check("on-demand screenshots differ across distinct agent windows",len(set(image_hashes))==3,image_hashes)
    result["screenshot_latency_ms"]=screenshot_latencies
    benchmark("three-running-fixtures")
    stale=negative("phone_act",session_id=sessions[1],snapshot_id=observed[0]["snapshot_id"],kind="tap",x=80,y=160,operation_id=newop("stale"))
    check("cross-agent snapshot cannot authorize tap",stale["code"]=="STALE_OBSERVATION",stale)
    sleep=adb("shell","input","keyevent","KEYCODE_SLEEP")
    try:
        time.sleep(.8)
        sleeping=[]
        for sid in sessions[:3]:
            try:
                state=call("phone_observe",session_id=sid,max_nodes=50)
                sleeping.append({"ok":True,"package":state["package_name"],"locked":state["locked"]})
            except Exception as e:
                sleeping.append({"ok":False,"error":str(e)[:200]})
        result["screen_off"]=sleeping
        check("screen-off virtual observation succeeds for all three",all(i.get("ok") and i.get("package")=="dev.pocket.fixture" for i in sleeping),sleeping)
        # Demonstrate a state-changing accessibility operation while the physical panel is dark.
        sid=sessions[0]
        state=call("phone_observe",session_id=sid,max_nodes=300)
        match=[n for n in state["nodes"] if n["resource_id"].endswith("/mcp_test_input")]
        if len(match)!=1: raise AssertionError("Screen-off input target unavailable")
        change=call("phone_act",session_id=sid,kind="type",snapshot_id=state["snapshot_id"],
            selector={"resource_id":match[0]["resource_id"]},text="SCREEN_OFF_SUCCESS",replace=True,operation_id=newop("screenoff"))
        after=call("phone_observe",session_id=sid,max_nodes=300)
        check("actual virtual input succeeds while panel is off",change["status"]=="verified" and
            any("SCREEN_OFF_SUCCESS" in n["text"] for n in after["nodes"]),change)
        result["power_during_screen_off"]=[line.strip() for line in adb("shell","dumpsys","power").splitlines() if "mWakefulness=" in line][:2]
        import re
        panel=adb("shell","dumpsys","display")
        main=[line for line in panel.splitlines() if 'DisplayDeviceInfo{"Built-in Screen"' in line]
        panel_off=bool(main and re.search(r"state OFF, committedState OFF",main[0]))
        result["physical_panel_off_during_virtual_action"]=panel_off
        check("physical display 0 actually off during virtual actions",panel_off,result["power_during_screen_off"])
    finally:
        adb("shell","input","keyevent","KEYCODE_WAKEUP")
        adb("shell","wm","dismiss-keyguard")
    benchmark("after-screen-off")
    call("phone_agent_release_ui",session_id=sessions[1],operation_id=newop("release"))
    after=set(v["display_id"] for v in call("phone_displays")["displays"] if v["role"]=="virtual")
    check("release removes only selected virtual display",after==set(acquired)-{acquired[1]},sorted(after))
    benchmark("two-displays-after-release")
    call("phone_agent_acquire_ui",session_id=sessions[3],operation_id=newop("acquire4"))
    check("fourth agent acquires freed capacity",len(call("phone_displays")["displays"])==4)
    first=call("phone_observe",session_id=sessions[0],max_nodes=50)
    moved=call("phone_act",session_id=sessions[0],kind="key",key="back",
        snapshot_id=first["snapshot_id"],operation_id=newop("virtualback"))
    check("display-targeted Back key dispatches",moved["status"]=="dispatched",moved)
    time.sleep(.4)
    check("virtual Back never changes human physical foreground",physical_top()==expected_physical,physical_top())
    result["passed"]=True

try:
    run()
except Exception as e:
    result["errors"].append({"type":type(e).__name__,"message":str(e)[:1000]})
    print("ERROR:",type(e).__name__,str(e)[:1000],flush=True)
finally:
    for sid in sessions:
        try: call("phone_agent_close",session_id=sid,operation_id=newop("close"))
        except Exception:pass
    try:
        result["after_cleanup"]=call("phone_displays")
        check("cleanup leaves only the human physical display",
            len(result["after_cleanup"]["displays"])==1 and result["after_cleanup"]["displays"][0]["display_id"]==0,
            result["after_cleanup"])
        benchmark("cleanup")
        time.sleep(.8)
        proc=subprocess.run(["adb","-s",args.serial,"shell","pidof","dev.pocket.mcp:display"],
            capture_output=True,text=True,timeout=20)
        result["idle_shizuku_user_process"]=proc.stdout.strip()
        check("idle Shizuku user-service process is released",not result["idle_shizuku_user_process"],
            result["idle_shizuku_user_process"])
    except Exception as e:
        result["cleanup_error"]=str(e)[:300]
        result["passed"]=False
    result["finished"]=int(time.time())
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+"\n")
    print("REPORT:",args.output,flush=True)
if not result["passed"]:raise SystemExit(1)
