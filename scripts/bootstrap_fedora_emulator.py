#!/usr/bin/env python3
"""Restore only the emulator runtime/image; never compiles an APK locally."""
import concurrent.futures, hashlib, os, time, urllib.request, zipfile
from pathlib import Path

BASE=Path(os.environ.get('POCKET_EMULATOR_SDK',str(Path.home()/'.cache/pocket-mcp/emulator-sdk')))
URL='https://dl.google.com/android/repository/sys-img/android/x86_64-35_r02.zip'
SIZE=782404023
SHA1='2d857d170c0d1b827149565da34b3383e5306f7f'
IMAGE=BASE/'system-images/android-35/default/x86_64'
BASE.mkdir(parents=True,exist_ok=True)
if not (IMAGE/'system.img').exists():
    archive=BASE/'api35-image.zip'
    prefix=archive.stat().st_size if archive.exists() else 0
    prefix=min(prefix,SIZE)
    starts=list(range(prefix,SIZE,32*1024*1024))
    parts=BASE/'image-parts';parts.mkdir(exist_ok=True)
    def fetch(start):
        end=min(start+32*1024*1024,SIZE)-1
        output=parts/str(start)
        if output.exists() and output.stat().st_size==end-start+1: return output
        for attempt in range(4):
            try:
                request=urllib.request.Request(URL,headers={'Range':f'bytes={start}-{end}'})
                with urllib.request.urlopen(request,timeout=45) as response, output.open('wb') as stream:
                    if response.status!=206 or response.headers.get('Content-Range')!=f'bytes {start}-{end}/{SIZE}': raise RuntimeError('Range response mismatch')
                    while chunk:=response.read(1024*1024): stream.write(chunk)
                if output.stat().st_size!=end-start+1: raise RuntimeError('Incomplete image segment')
                return output
            except Exception:
                if attempt==3: raise
                time.sleep(attempt+1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        pending={pool.submit(fetch,start) for start in starts}
        while pending:
            done,pending=concurrent.futures.wait(pending,timeout=25,return_when=concurrent.futures.FIRST_COMPLETED)
            for future in done: future.result()
            total=prefix+sum(p.stat().st_size for p in parts.iterdir())
            print(f'Android emulator image: {total*100//SIZE}%',flush=True)
    with archive.open('ab') as dest:
        for start in starts:
            with (parts/str(start)).open('rb') as source:
                while chunk:=source.read(4*1024*1024): dest.write(chunk)
    sha=hashlib.sha1()
    with archive.open('rb') as source:
        while chunk:=source.read(4*1024*1024): sha.update(chunk)
    if sha.hexdigest()!=SHA1: raise RuntimeError('Android system image checksum mismatch')
    IMAGE.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as source: source.extractall(IMAGE.parent)
    archive.unlink()
    for p in parts.iterdir(): p.unlink()
    parts.rmdir()
for name,target in [('emulator',Path.home()/'.local/share/android-emulator-backups/emulator-37.1.11'),('platform-tools',Path.home()/'.local/opt/platform-tools')]:
    p=BASE/name
    if not p.exists(): p.symlink_to(target,target_is_directory=True)
print(f'Emulator SDK ready: {BASE}',flush=True)
