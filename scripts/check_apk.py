#!/usr/bin/env python3
import json, sys, zipfile
from pathlib import Path
reports=[]
for name in sys.argv[1:]:
    p=Path(name)
    with zipfile.ZipFile(p) as z:
        files=z.namelist()
        assert 'classes.dex' in files
        assert 'assets/tools.json' in files
        assert 'lib/arm64-v8a/libngrok_java.so' in files
        if 'arm64.apk' in p.name: assert not any(n.startswith('lib/x86_64/') for n in files)
        else: assert 'lib/x86_64/libngrok_java.so' in files
        assert not any('codex-computer-use' in n for n in files)
        tools=json.loads(z.read('assets/tools.json'))
        assert len(tools)==23
        assert all(t['inputSchema']['additionalProperties'] is False for t in tools)
    reports.append({'file':p.name,'bytes':p.stat().st_size,'mcp_tools':len(tools)})
Path('artifacts/apk-structure.json').write_text(json.dumps(reports,indent=2)+'\n')
print(json.dumps(reports))
