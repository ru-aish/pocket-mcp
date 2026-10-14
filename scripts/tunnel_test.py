#!/usr/bin/env python3
"""Exercise a GitHub-built APK's real ngrok path on an isolated local emulator.

Credentials are read from a mode-0600 file and never printed or written to reports.
No physical device, real messages, calls or user providers are touched.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--apk',type=Path,required=True)
parser.add_argument('--token-file',type=Path)
parser.add_argument('--serial',default='emulator-5580')
parser.add_argument('--expect-failure',action='store_true')
parser.add_argument('--dns-failure',action='store_true',help='Temporarily break Private DNS on this emulator and verify the DNS diagnostic')
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
if not args.serial.startswith('emulator-'):parser.error('Only an explicit emulator serial is permitted')
token='0'*48
if args.token_file:
    if stat.S_IMODE(args.token_file.stat().st_mode)&0o077:parser.error('Token file must be private (0600)')
    token=args.token_file.read_text().strip()
if not re.fullmatch(r'[A-Za-z0-9_-]{32,160}',token):parser.error('Expected a plain ngrok authtoken')
bearer=secrets.token_urlsafe(32)
adb_bin=str(Path.home()/'.local/bin/adb')
package='dev.pocket.mcp'
report={'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'serial':args.serial,'built_locally':False,'expected_failure':args.expect_failure,'checks':[]}
args.output.mkdir(parents=True,exist_ok=True)
window='/data/local/tmp/pocket-tunnel-test.xml'
dns_original={}

def adb(*values):
    r=subprocess.run([adb_bin,'-s',args.serial,*map(str,values)],capture_output=True,text=True,timeout=40)
    if r.returncode:raise RuntimeError('ADB test setup failed')
    return r.stdout

def ui():
    adb('shell','uiautomator','dump',window)
    return ET.fromstring(adb('exec-out','cat',window))

def click(description):
    w,h=map(int,re.findall(r'(\d+)x(\d+)',adb('shell','wm','size'))[-1])
    for attempt in range(12):
        for node in ui().iter('node'):
            if node.get('content-desc')!=description:continue
            points=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
            if len(points)==4 and points[2]>points[0] and points[3]>points[1] and (points[1]+points[3])/2<h*.90:
                adb('shell','input','tap',(points[0]+points[2])//2,(points[1]+points[3])//2)
                return
        start,end=(.8,.3) if attempt<4 else (.3,.8)
        adb('shell','input','swipe',max(8,w//30),int(h*start),max(8,w//30),int(h*end),250)
    raise AssertionError('Missing setup control: '+description)

def passed(name):
    report['checks'].append(name)
    print('PASS:',name,flush=True)

def public_rpc(url,method,authorization=None):
    headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream','ngrok-skip-browser-warning':'true','MCP-Protocol-Version':'2025-11-25'}
    if authorization is not None:headers['Authorization']='Bearer '+authorization
    payload=json.dumps({'jsonrpc':'2.0','id':1,'method':method,'params':{}}).encode()
    # Only these read-only probes may retry a connection failure; never use this for tools/call.
    assert method in ('ping','tools/list')
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url,data=payload,headers=headers),timeout=25) as r:
                return r.status,json.loads(r.read())
        except urllib.error.HTTPError as e:return e.code,None
        except OSError:
            if attempt==2:raise
            time.sleep(2)

try:
    adb('install','-r',args.apk)
    adb('shell','am','force-stop',package)
    adb('shell','input','keyevent','KEYCODE_WAKEUP')
    adb('shell','wm','dismiss-keyguard')
    adb('shell','pm','grant',package,'android.permission.POST_NOTIFICATIONS')
    if args.dns_failure:
        for name in ('private_dns_mode','private_dns_specifier'):
            dns_original[name]=adb('shell','settings','get','global',name).strip()
        adb('shell','settings','put','global','private_dns_specifier','pocket-mcp-dns-test.invalid')
        adb('shell','settings','put','global','private_dns_mode','hostname')
        time.sleep(3)
    adb('shell','am','start','-n',package+'/.MainActivity')
    click('P_NGROK');adb('shell','input','text',token);adb('shell','input','keyevent','KEYCODE_BACK')
    click('P_BEARER');adb('shell','input','text',bearer);adb('shell','input','keyevent','KEYCODE_BACK')
    click('P_SAVE');click('P_START_PUBLIC')
    deadline=time.monotonic()+150
    status='';previous=''
    while time.monotonic()<deadline:
        for node in ui().iter('node'):
            if node.get('content-desc')=='P_STATUS':status=node.get('text','')
        stage=status.splitlines()[0] if status else 'No status'
        if stage!=previous:print(stage,flush=True);previous=stage
        if stage in ('Connection: online','Connection: failed','Connection: https-unverified'):break
        time.sleep(1)
    else:raise AssertionError('Public connection did not finish starting within 150 seconds')
    # Only P_STATUS is copied. Credential input fields and raw SDK logs are excluded.
    report['status']=status
    print(status,flush=True)
    if args.dns_failure:
        assert stage=='Connection: failed'
        assert 'Failed at: dns-check' in status and 'Diagnostic: dns' in status
        passed('broken Android Private DNS fails with specific VPN/DNS recovery advice')
    elif args.expect_failure:
        assert stage=='Connection: failed','Invalid token unexpectedly started a connection'
        assert 'Diagnostic:' in status,'Failure details were discarded'
        assert 'ERR_NGROK_105' in status,'Invalid-token probe failed for a different reason'
        passed('invalid token fails with a safe visible diagnostic')
    else:
        assert stage=='Connection: online','Public ngrok connection failed'
        match=re.search(r'https://[^\s]+/mcp',status)
        assert match,'Online status is missing public URL'
        url=match.group()
        passed('Android app verifies authenticated public HTTPS round trip')
        assert public_rpc(url,'ping')[0]==401
        assert public_rpc(url,'ping','invalid')[0]==401
        passed('public URL rejects missing and wrong bearer')
        code,result=public_rpc(url,'ping',bearer)
        assert code==200 and result.get('result')=={}
        passed('authenticated public MCP ping reaches Android server')
        code,result=public_rpc(url,'tools/list',bearer)
        assert code==200
        names={t['name'] for t in result['result']['tools']}
        assert {'phone_alarm','phone_observe','phone_capabilities'}.issubset(names)
        passed('public MCP exposes direct alarm API and screen fallback')
    report['passed']=True
except Exception as e:
    report['passed']=False
    report['failure_type']=type(e).__name__
    # The exception may contain HTTP/request data. Do not serialize it.
    print('FAIL:',type(e).__name__,flush=True)
finally:
    for name,value in dns_original.items():
        try:
            if value=='null':adb('shell','settings','delete','global',name)
            else:adb('shell','settings','put','global',name,value)
        except Exception:print('Emulator Private DNS restore needs inspection',flush=True)
    try:
        pid=adb('shell','pidof',package).strip()
        logs=adb('logcat','--pid='+pid,'-d') if pid else ''
        report['credentials_absent_from_app_logs']=token not in logs and bearer not in logs
        if not report['credentials_absent_from_app_logs']:report['passed']=False
    except Exception:report['log_scan']='unavailable'
    try:
        adb('shell','am','start','-n',package+'/.MainActivity');click('P_STOP')
        adb('shell','rm','-f',window)
    except Exception:print('Cleanup needs inspection',flush=True)
    (args.output/'results.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Report:',args.output/'results.json',flush=True)
raise SystemExit(0 if report.get('passed') else 1)
