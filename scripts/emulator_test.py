#!/usr/bin/env python3
"""Exercise a GitHub-built signed APK on Fedora's real local Android emulator.

ADB provisions the isolated test environment; actual phone control uses MCP.
Mutating MCP requests are never silently retried. No real calls or SMS are sent.
"""
import argparse, base64, hashlib, http.client, json, re, secrets, subprocess, time
from pathlib import Path
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--apk',type=Path,required=True)
parser.add_argument('--fixture',type=Path,required=True)
parser.add_argument('--serial',default='emulator-5580')
parser.add_argument('--adb',default=str(Path.home()/'.local/bin/adb'))
parser.add_argument('--port',type=int,default=18865)
parser.add_argument('--output',type=Path,default=Path('artifacts/fedora-validation'))
args=parser.parse_args()
if not args.serial.startswith('emulator-'): raise SystemExit('Tests are restricted to emulator serials; never target a physical phone')
args.output.mkdir(parents=True,exist_ok=True)
PACKAGE='dev.pocket.mcp';FIXTURE='dev.pocket.fixture'
token=secrets.token_urlsafe(32)
checks=[];counter=0;operation_prefix=None

def newop(label): return operation_prefix+"_"+label+"_"+secrets.token_hex(10)


def adb(*values,check=True,binary=False):
    r=subprocess.run([args.adb,'-s',args.serial,*map(str,values)],capture_output=True,text=not binary,timeout=40)
    if check and r.returncode: raise RuntimeError('ADB setup failed: '+str(values[0:3])+': '+(r.stderr.decode(errors='replace') if binary else r.stderr))
    return r.stdout


def passed(name):
    checks.append({'name':name,'status':'passed'});print('PASS:',name,flush=True)


def ui():
    adb('shell','rm','-f','/data/local/tmp/pocket-test-window.xml')
    adb('shell','uiautomator','dump','/data/local/tmp/pocket-test-window.xml')
    return ET.fromstring(adb('exec-out','cat','/data/local/tmp/pocket-test-window.xml'))


def click(description,scroll=True):
    dimensions=re.findall(r'(\d+)x(\d+)',adb('shell','wm','size'))[-1];w,h=map(int,dimensions)
    for attempt in range(12):
        root=ui()
        for node in root.iter('node'):
            if node.get('content-desc')!=description: continue
            points=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
            if len(points)==4 and points[2]>points[0] and points[3]>points[1]:
                y=(points[1]+points[3])//2
                if y>h*.90: continue # Avoid partially clipped controls under navigation bars.
                adb('shell','input','tap',(points[0]+points[2])//2,y);time.sleep(.3);return
        if not scroll: break
        # Scroll in the page margin: text fields may intercept drags through their contents.
        adb('shell','input','swipe',max(8,w//30),int(h*.80),max(8,w//30),int(h*.30),250)
    raise AssertionError('Bootstrap UI control missing: '+description)


def start_local():
    adb('shell','am','start','-n',PACKAGE+'/.MainActivity');time.sleep(1)
    click('P_START_LOCAL')
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        try:
            code,_=rpc('ping')
            if code==200: return
        except OSError: pass
        time.sleep(.4)
    raise AssertionError('Local server did not become ready')


def rpc(method,params=None,authorization=True,headers=None,http_method='POST',body=None):
    global counter
    counter+=1
    payload=body if body is not None else json.dumps({'jsonrpc':'2.0','id':counter,'method':method,'params':params or {}})
    h={'Content-Type':'application/json','Accept':'application/json, text/event-stream','MCP-Protocol-Version':'2025-11-25'}
    if authorization: h['Authorization']='Bearer '+token
    h.update(headers or {})
    c=http.client.HTTPConnection('127.0.0.1',args.port,timeout=35)
    try:
        c.request(http_method,'/mcp',payload.encode(),h);r=c.getresponse();data=r.read();return r.status,json.loads(data) if data else None
    finally: c.close()


def call(name,**values):
    status,result=rpc('tools/call',{'name':name,'arguments':values})
    assert status==200 and 'result' in result,(status,result)
    return result['result']


def success(name,**values):
    result=call(name,**values)
    assert not result.get('isError'),(name,result)
    return result['structuredContent']


def observe(): return success('phone_observe',max_nodes=500)
def target(resource):
    for _ in range(20):
        state=observe()
        for node in state['nodes']:
            if node['resource_id'].endswith('/'+resource): return state,node
        time.sleep(.2)
    raise AssertionError('Fixture node not found: '+resource)


def act(kind,resource=None,**values):
    if resource:
        state,node=target(resource)
        values['selector']={'resource_id':node['resource_id']}
    else: state=observe()
    return success('phone_act',kind=kind,snapshot_id=state['snapshot_id'],operation_id=newop('test'),**values)


def text(expected):
    for _ in range(25):
        if any(expected in n['text'] for n in observe()['nodes']): return
        time.sleep(.15)
    raise AssertionError('Missing observed postcondition: '+expected)


def fixture():
    adb('shell','am','start','-n',FIXTURE+'/.FixtureActivity')
    text('MCP_TEST_FIXTURE')


def run():
    global operation_prefix
    adb('install','-r',args.apk)
    # Hosted debug fixture keys can change between runs; only this disposable fixture is reset.
    adb('uninstall',FIXTURE,check=False);adb('install',args.fixture)
    adb('shell','cmd','notification','disallow_listener',PACKAGE+'/'+PACKAGE+'.PhoneNotifications',check=False)
    adb('shell','am','force-stop',PACKAGE);adb('shell','am','force-stop',FIXTURE)
    for perm in ('READ_CONTACTS','CALL_PHONE','READ_PHONE_STATE','SEND_SMS','READ_CALENDAR','WRITE_CALENDAR'):
        adb('shell','pm','revoke',PACKAGE,'android.permission.'+perm,check=False)
    for perm in ('READ_CONTACTS','WRITE_CONTACTS','READ_CALENDAR','WRITE_CALENDAR'):
        adb('shell','pm','grant',FIXTURE,'android.permission.'+perm)
    api=int(adb('shell','getprop','ro.build.version.sdk').strip())
    if api>=33:
        adb('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
        adb('shell','pm','grant',FIXTURE,'android.permission.POST_NOTIFICATIONS')
    if api>=33: adb('shell','appops','set',PACKAGE,'ACCESS_RESTRICTED_SETTINGS','allow')
    adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
    # Preserve any existing emulator accessibility services. This never targets the USB phone.
    service=PACKAGE+'/'+PACKAGE+'.PhoneObserver'
    current=adb('shell','settings','get','secure','enabled_accessibility_services').strip()
    enabled=[] if current in ('','null') else current.split(':')
    others=':'.join(v for v in enabled if v!=service)
    if others: adb('shell','settings','put','secure','enabled_accessibility_services',others)
    else: adb('shell','settings','delete','secure','enabled_accessibility_services')
    time.sleep(.3)
    if service not in enabled: enabled.append(service)
    adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(enabled));adb('shell','settings','put','secure','accessibility_enabled','1')
    adb('forward','tcp:'+str(args.port),'tcp:8765')
    adb('shell','am','start','-n',PACKAGE+'/.MainActivity');time.sleep(1)
    click('P_BEARER');adb('shell','input','text',token);adb('shell','input','keyevent','KEYCODE_BACK');click('P_SAVE')
    start_local()
    passed('signed release installs and starts through normal app UI')
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        cap=success('phone_capabilities')
        if cap['accessibility_connected']: break
        time.sleep(.4)
    assert cap['accessibility_connected'] and cap['tunnel_native_loaded'],cap
    operation_prefix=cap['operation_prefix']
    passed('accessibility bound and real JNI wrapper/library initialized')
    assert rpc('ping',authorization=False)[0]==401
    assert rpc('ping',headers={'Authorization':'Bearer invalid'})[0]==401
    assert rpc('ping',headers={'Origin':'https://malicious.invalid'})[0]==403
    passed('missing/wrong bearer and browser Origin rejected')
    assert rpc('ping',headers={'MCP-Protocol-Version':'invalid'})[0]==400
    assert rpc('ping',http_method='GET')[0]==405
    assert rpc('ping',body='invalid json')[0]==400
    assert rpc('ping',headers={'Content-Type':'text/plain'})[0]==415
    passed('protocol version, method, JSON and content type checks')
    status,r=rpc('initialize',{'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'fedora-emulator-test','version':'1'}})
    assert status==200 and r['result']['serverInfo']['name']=='pocket-mcp'
    assert rpc('x',body=json.dumps({'jsonrpc':'2.0','method':'notifications/initialized'}))[0]==202
    assert len(rpc('tools/list')[1]['result']['tools'])==16
    passed('initialize, notification, ping and all 16 tool schemas')
    assert call('phone_observe',max_nodes=-1)['isError']
    assert call('phone_capabilities',unknown=True)['isError']
    assert call('not_a_tool')['isError']
    passed('invalid/unknown tool arguments rejected')
    fixture()
    state=observe();assert state['package_name']==FIXTURE
    full=call('phone_observe',include_image=True,max_nodes=0,max_image_dimension=640)
    assert not full['isError'],full
    images=[v for v in full['content'] if v['type']=='image'];assert len(images)==1
    data=base64.b64decode(images[0]['data']);assert data[:2]==b'\xff\xd8'
    (args.output/'fixture-screen.jpg').write_bytes(data)
    assert max(full['structuredContent']['image_width'],full['structuredContent']['image_height'])<=640
    assert full['structuredContent']['coordinate_height']>full['structuredContent']['image_height']
    passed('real screenshot, original coordinate dimensions and resized JPEG')
    tiny=success('phone_observe',max_nodes=1);assert tiny['nodes_truncated']
    passed('bounded tree reports truncation and omits image by default')
    act('type','mcp_test_input',text='  Pocket_42  ',replace=True);text('  Pocket_42  ')
    passed('exact text entry preserves whitespace')
    act('type','mcp_test_input',text='',replace=True)
    state,node=target('mcp_test_input');assert node['text'] in ('','MCP_INPUT'),node
    passed('empty text clears field and verifies correctly with hint text')
    act('type','mcp_test_input',text='Pocket_42',replace=True)
    state,node=target('mcp_test_click');op=newop('dedup')
    action={'kind':'click','snapshot_id':state['snapshot_id'],'selector':{'resource_id':node['resource_id']},'operation_id':op}
    first=success('phone_act',**action);text('STATUS_CLICKED:Pocket_42 COUNT:1')
    second=success('phone_act',**dict(reversed(list(action.items()))));assert first==second;text('COUNT:1')
    conflict=call('phone_act',**dict(action,kind='long_click'));assert conflict['isError'] and conflict['structuredContent']['code']=='OPERATION_CONFLICT'
    assert success('phone_operation',operation_id=op)['status']=='recorded'
    passed('semantic click, durable deduplication, reordered keys and ID conflict checks')
    act('long_click','mcp_test_long_click');text('STATUS_LONG_CLICKED')
    passed('native long click changes observed fixture state')
    act('click','mcp_test_click');text('COUNT:2')
    state,node=target('mcp_test_long_click');bounds=node['bounds']
    act('long_press',x=(bounds['left']+bounds['right'])//2,y=(bounds['top']+bounds['bottom'])//2,duration_ms=700);text('STATUS_LONG_CLICKED')
    passed('gesture long press changes observed fixture state')
    state,node=target('mcp_focused_input');bounds=node['bounds']
    act('tap',x=(bounds['left']+bounds['right'])//2,y=(bounds['top']+bounds['bottom'])//2)
    act('type',text='Focused_9',replace=True);text('Focused_9')
    act('key',key='enter');text('STATUS_ENTERED:Focused_9')
    passed('coordinate tap, focused input and real IME Enter postcondition')
    act('key',key='back')
    for _ in range(8):
        if any('MCP_BOTTOM_MARKER' in n['text'] for n in observe()['nodes']): break
        act('scroll','mcp_test_scroll',direction='down')
        time.sleep(.35) # Android smooth scrolling can outlast the input acknowledgement.
    text('MCP_BOTTOM_MARKER');passed('semantic scrolling reveals fixture bottom marker')
    state,node=target('mcp_test_scroll');b=node['bounds'];x=(b['left']+b['right'])//2
    act('swipe',x=x,y=b['top']+40,end_x=x,end_y=b['bottom']-40,duration_ms=450)
    passed('real swipe gesture dispatched')
    old=observe();adb('shell','am','start','-a','android.settings.SETTINGS');time.sleep(.4)
    stale=call('phone_act',kind='tap',snapshot_id=old['snapshot_id'],x=50,y=100,operation_id=newop('stale'))
    assert stale['isError'] and stale['structuredContent']['code']=='STALE_OBSERVATION',stale
    passed('stale package/window action rejected before dispatch')
    # Provider tests use only seeded emulator data, never a personal phone account.
    denied=call('phone_contacts',query='Pocket MCP Test Ada');assert denied['isError'] and denied['structuredContent']['code']=='PERMISSION_REQUIRED'
    for perm in ('READ_CONTACTS','READ_CALENDAR','WRITE_CALENDAR'): adb('shell','pm','grant',PACKAGE,'android.permission.'+perm)
    contacts=success('phone_contacts',query='Pocket MCP Test Ada')['contacts'];assert any(c['number']=='+15550100420' for c in contacts),contacts
    passed('contacts permission gate and direct provider retrieval')
    calendars=success('phone_calendars')['calendars'];calendar=next(c for c in calendars if c['name']=='Pocket Test Calendar' and c['writable'])
    op_event=newop('event');now=int(time.time()*1000)
    event={'calendar_id':calendar['id'],'title':'Pocket MCP emulator verification','start_ms':now+3600000,'end_ms':now+7200000,'timezone':'UTC','operation_id':op_event}
    created=success('phone_calendar_create',**event);assert created['status']=='verified'
    assert success('phone_calendar_create',**event)['reference']==created['reference']
    passed('direct calendar insert/readback and retry returns same persisted event')
    sms=call('phone_sms',number='+15550100420',text='Not sent',operation_id=newop('sms'));assert sms['isError'] and sms['structuredContent']['code']=='PERMISSION_REQUIRED'
    calling=call('phone_call',number='+15550100420',operation_id=newop('call'));assert calling['isError'] and calling['structuredContent']['code']=='PERMISSION_REQUIRED'
    passed('SMS and calls denied without grants; no real message/call sent')
    missing=call('phone_notifications');assert missing['isError']
    passed('notification access gate reports unavailable rather than success')
    adb('shell','cmd','notification','allow_listener',PACKAGE+'/'+PACKAGE+'.PhoneNotifications')
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        if success('phone_capabilities')['notification_listener_connected']: break
        time.sleep(.3)
    replies=success('phone_notifications')['reply_actions'];reply=next(r for r in replies if r['package_name']==FIXTURE)
    assert 'Ada' in reply['title'] or 'Ada' in reply['conversation_title']
    assert reply['group_conversation'] is False
    fixture()
    success('phone_reply',notification_key=reply['notification_key'],posted_at_ms=reply['posted_at_ms'],action_index=reply['action_index'],text='Synthetic reply only',operation_id=newop('reply'))
    text('STATUS_REPLY:Synthetic reply only')
    passed('app-provided notification reply updates the actual receiving fixture')
    success('phone_media',package_name=FIXTURE,action='play',operation_id=newop('play'));text('STATUS_MEDIA_PLAYING')
    assert any(m['package_name']==FIXTURE and m['playback_state']==3 for m in success('phone_capabilities')['media_sessions'])
    success('phone_media',package_name=FIXTURE,action='pause',operation_id=newop('pause'));text('STATUS_MEDIA_PAUSED')
    passed('direct media-session play/pause with native state readback')
    apps=success('phone_apps')['apps'];assert any(a['package_name']==FIXTURE for a in apps)
    launched=success('phone_open_app',package_name=FIXTURE,operation_id=newop('launch'));assert launched['status']=='requested';text('MCP_TEST_FIXTURE')
    passed('launcher discovery and launch verified with actual foreground package')
    act('key',key='home');time.sleep(.4);assert observe()['package_name']!=FIXTURE
    act('key',key='recents');time.sleep(.4);act('key',key='back')
    passed('Home, Recents and Back navigation')
    fixture();snapshot=observe();adb('shell','input','keyevent','KEYCODE_SLEEP');time.sleep(.4)
    asleep=call('phone_act',kind='tap',snapshot_id=snapshot['snapshot_id'],x=50,y=100,operation_id=newop('sleep'));assert asleep['isError'] and asleep['structuredContent']['code']=='SCREEN_OFF',asleep
    adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
    passed('screen-off action rejected')
    # Force-stop/restart proves encrypted settings and operation ledger survive process death.
    adb('shell','am','force-stop',PACKAGE);time.sleep(.4)
    start_local()
    assert success('phone_operation',operation_id=op)['status']=='recorded'
    assert success('phone_act',**action)==first
    passed('encrypted bearer and idempotency ledger survive app process restart')
    adb('shell','am','start','-n',PACKAGE+'/.MainActivity');time.sleep(.5);click('P_STOP')
    time.sleep(.5)
    try: rpc('ping');raise AssertionError('Server still responds after Stop')
    except OSError: pass
    passed('Stop closes local listener and removes foreground service')
    adb('shell','rm','-f','/data/local/tmp/pocket-test-window.xml')
    adb('forward','--remove','tcp:'+str(args.port))


report={'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'serial':args.serial,'built_locally':False,'checks':checks,'scope':'Signed GitHub release on real local Fedora Android emulator; no live call/SMS or public ngrok account test'}
try:
    run();report['passed']=True
except Exception as e:
    report['passed']=False;report['failure_type']=type(e).__name__;report['failure']=str(e)[:1000]
    raise
finally:
    report['api']=adb('shell','getprop','ro.build.version.sdk',check=False).strip()
    (args.output/'results.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Validation report:',str(args.output/'results.json'),flush=True)
