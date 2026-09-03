#!/usr/bin/env python3
import json, pathlib, subprocess, sys, tempfile
HERE=pathlib.Path(__file__).resolve().parent; RUNNER=HERE/'inverted_control_debugger.py'; PASS=0
def ok(v,n):
    global PASS
    if not v: raise AssertionError(n)
    PASS+=1
with tempfile.TemporaryDirectory() as raw:
    td=pathlib.Path(raw); plan=td/'plan.json'
    plan.write_text(json.dumps({'schema':'INVERTED_CONTROL_PLAN/1','session_id':'test','workspace':str(td),'transactions':[
        {'id':'write','action':'write_file','path':'a.txt','content':'alpha'},
        {'id':'fail','action':'shell','command':'python3 -c "import sys;print(\'OUT_MARKER\');print(\'ERR_MARKER\',file=sys.stderr);sys.exit(7)"'},
        {'id':'ask','action':'ai_prompt','prompt':'Give repaired value.'},
        {'id':'use','action':'write_file','path':'answer.txt','content':'${ai.ask}'},
        {'id':'assert','action':'assert_file_contains','path':'answer.txt','needle':'forty-two'}]}))
    commands='\n'.join([
      json.dumps({'schema':'AI_DEBUG_COMMAND/1','op':'replace_transaction','transaction':{'id':'fail','action':'shell','command':'python3 -c "print(42)"'}}),
      json.dumps({'schema':'AI_DEBUG_COMMAND/1','op':'answer','text':'forty-two'}),''])
    cp=subprocess.run([sys.executable,str(RUNNER),str(plan),'--fresh'],input=commands,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    ok(cp.returncode==0,'exit'); ok('"kind": "exception"' in cp.stdout,'exception event'); ok('"kind": "prompt"' in cp.stdout,'prompt event'); ok((td/'answer.txt').read_text()=='forty-two','answer consumed')
    state=json.loads((td/'plan.json.state.json').read_text()); ok(state['status']=='COMPLETE' and state['next_index']==5,'state')
    failures=[r for r in state['receipts'] if r.get('status')=='EXCEPTION']; ok(bool(failures),'journal')
    failure=failures[0]
    ok('OUT_MARKER' in failure.get('stdout',''),'failure stdout captured')
    ok('ERR_MARKER' in failure.get('stderr',''),'failure stderr captured')
    ok(failure.get('returncode')==7,'failure returncode captured')
    ok('OUT_MARKER' in str(failure.get('command','')),'failure command captured')
print(f'INVERTED_CONTROL_TESTS_PASS={PASS}')
