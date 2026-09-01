#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, os, pathlib, subprocess, sys, tempfile, time, traceback

PLAN='INVERTED_CONTROL_PLAN/1'; STATE='INVERTED_CONTROL_STATE/1'; EVENT='AI_DEBUG_EVENT/1'; CMD='AI_DEBUG_COMMAND/1'
ACTIONS={'shell','write_file','assert_file_contains','assert_file_exists','ai_prompt','checkpoint'}

class PlanError(RuntimeError): pass
class AiAbort(RuntimeError): pass

def sha(b): return hashlib.sha256(b).hexdigest()
def atomic(path: pathlib.Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix=path.name+'.',suffix='.tmp',dir=str(path.parent))
    try:
        with os.fdopen(fd,'wb') as f: f.write(data); f.flush(); os.fsync(f.fileno())
        os.replace(tmp,path)
    finally:
        pathlib.Path(tmp).unlink(missing_ok=True)
def save(path,obj): atomic(path,(json.dumps(obj,indent=2,sort_keys=True)+'\n').encode())
def load(path):
    x=json.loads(path.read_text());
    if not isinstance(x,dict): raise PlanError(f'{path}: root must be object')
    return x

def validate(plan):
    if plan.get('schema')!=PLAN: raise PlanError('bad plan schema')
    tx=plan.get('transactions')
    if not isinstance(tx,list) or not tx: raise PlanError('transactions required')
    seen=set()
    for t in tx:
        if not isinstance(t,dict) or not t.get('id') or t['id'] in seen: raise PlanError('bad/duplicate transaction id')
        seen.add(t['id'])
        if t.get('action') not in ACTIONS: raise PlanError(f"unsupported action {t.get('action')}")

def emit(kind, **payload):
    x={'schema':EVENT,'kind':kind,'at_ms':int(time.time()*1000),**payload}
    print('[[AI_DEBUG_EVENT/1]]',flush=True); print(json.dumps(x,sort_keys=True),flush=True); print('[[/AI_DEBUG_EVENT/1]]',flush=True)

def ai_read(noninteractive=False):
    if noninteractive: raise AiAbort('AI interaction required in --noninteractive mode')
    line=sys.stdin.readline()
    if not line: raise AiAbort('AI debugger stdin closed')
    x=json.loads(line)
    if not isinstance(x,dict) or x.get('schema') not in (None,CMD): raise AiAbort('bad AI command')
    return x

def resolve(workspace,s):
    p=pathlib.Path(s); return (p if p.is_absolute() else workspace/p).resolve()
def lookup(key,state):
    if key.startswith('ai.'): return str(state.get('ai',{}).get(key[3:],'') or '')
    if key.startswith('receipt.'):
        tid,_,field=key[8:].partition('.')
        for r in reversed(state.get('receipts',[])):
            if r.get('id')==tid: return str(r.get(field,'') or '')
    return ''
def render(s,state):
    s=str(s)
    for _ in range(128):
        a=s.find('${')
        if a<0: break
        b=s.find('}',a+2)
        if b<0: break
        s=s[:a]+lookup(s[a+2:b],state)+s[b+1:]
    return s

def run_shell(workspace,t,state):
    command=render(t['command'],state); env=os.environ.copy()
    for k,v in (t.get('env') or {}).items(): env[str(k)]=render(v,state)
    cp=subprocess.run(command,cwd=workspace,env=env,shell=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=float(t.get('timeout_seconds',900)))
    if cp.returncode: raise subprocess.CalledProcessError(cp.returncode,command,output=cp.stdout,stderr=cp.stderr)
    return {'command':command,'returncode':cp.returncode,'stdout':cp.stdout,'stderr':cp.stderr}

def execute(workspace,t,state,noninteractive):
    a=t['action']
    if a=='shell': return run_shell(workspace,t,state)
    if a=='write_file':
        p=resolve(workspace,render(t['path'],state)); data=render(t.get('content',''),state).encode(); atomic(p,data); return {'path':str(p),'bytes':len(data),'sha256':sha(data)}
    if a=='assert_file_contains':
        p=resolve(workspace,render(t['path'],state)); n=render(t['needle'],state)
        if n not in p.read_text(): raise AssertionError(f'{p} lacks {n!r}')
        return {'path':str(p),'needle':n,'matched':True}
    if a=='assert_file_exists':
        p=resolve(workspace,render(t['path'],state));
        if not p.exists(): raise FileNotFoundError(p)
        return {'path':str(p),'exists':True}
    if a=='checkpoint':
        msg=render(t.get('message',t['id']),state); emit('checkpoint',transaction_id=t['id'],message=msg); return {'message':msg}
    if a=='ai_prompt':
        prompt=render(t['prompt'],state)
        emit('prompt',transaction_id=t['id'],prompt=prompt,expected_command={'schema':CMD,'op':'answer','text':f'<available later as ${{ai.{t["id"]}}}>'})
        cmd=ai_read(noninteractive)
        if cmd.get('op')!='answer': raise AiAbort('ai_prompt requires op=answer')
        answer=str(cmd.get('text','')); state.setdefault('ai',{})[t['id']]=answer; return {'answer':answer}
    raise PlanError(a)

def debug_apply(workspace,t,state,cmd):
    op=cmd.get('op')
    if op in ('retry','skip'): return op
    if op=='abort': raise AiAbort(str(cmd.get('reason','AI requested abort')))
    if op=='replace_transaction':
        x=cmd.get('transaction');
        if not isinstance(x,dict): raise PlanError('replacement transaction required')
        x=dict(x); x.setdefault('id',t['id']); validate({'schema':PLAN,'transactions':[x]}); t.clear(); t.update(x); return 'retry'
    if op=='patch_file':
        p=resolve(workspace,cmd['path']); old=str(cmd['old']); new=str(cmd['new']); text=p.read_text()
        if old not in text: raise AssertionError(f'patch old text absent: {p}')
        atomic(p,text.replace(old,new,1).encode()); return 'retry'
    if op=='shell':
        r=run_shell(workspace,{'command':cmd['command'],'timeout_seconds':cmd.get('timeout_seconds',900)},state)
        emit('debug_action_result',transaction_id=t['id'],op='shell',result=r); return cmd.get('then','retry')
    raise PlanError(f'unsupported debugger op {op!r}')

def run(plan_path,state_path,workspace,fresh=False,noninteractive=False,max_rounds=32):
    plan=load(plan_path); validate(plan); plan_hash=sha(plan_path.read_bytes())
    if state_path.exists() and not fresh:
        state=load(state_path)
        if state.get('schema')!=STATE or state.get('plan_sha256')!=plan_hash: raise PlanError('state/plan mismatch; use --fresh intentionally')
    else:
        state={'schema':STATE,'plan_sha256':plan_hash,'session_id':plan.get('session_id',''),'next_index':0,'status':'RUNNING','ai':{},'receipts':[]}; save(state_path,state)
    tx=[dict(x) for x in plan['transactions']]
    emit('start',session_id=plan.get('session_id',''),workspace=str(workspace),next_index=state['next_index'],transactions=[x['id'] for x in tx])
    for i in range(int(state['next_index']),len(tx)):
        t=tx[i]; rounds=0
        while True:
            started=int(time.time()*1000)
            try:
                result=execute(workspace,t,state,noninteractive)
                receipt={'id':t['id'],'action':t['action'],'status':'PASS','started_at_ms':started,'finished_at_ms':int(time.time()*1000),**result}
                state['receipts'].append(receipt); state['next_index']=i+1; save(state_path,state); emit('transaction_pass',**receipt); break
            except (KeyboardInterrupt,SystemExit): raise
            except BaseException as e:
                rounds+=1; failure={'id':t.get('id',str(i)),'action':t.get('action',''),'status':'EXCEPTION','exception_type':type(e).__name__,'exception':str(e),'traceback':traceback.format_exc(),'debug_round':rounds}
                state['receipts'].append(failure); state['next_index']=i; save(state_path,state)
                emit('exception',**failure,transaction=t,continuation_prompt='Inspect this failure and return one AI_DEBUG_COMMAND/1 JSON command. Prefer repair then retry. Valid ops: retry, skip, abort, replace_transaction, patch_file, shell.')
                if rounds>max_rounds: raise AiAbort('debugger round limit exceeded')
                try: disposition=debug_apply(workspace,t,state,ai_read(noninteractive))
                except BaseException as de:
                    emit('debugger_command_exception',transaction_id=t.get('id',''),exception_type=type(de).__name__,exception=str(de),traceback=traceback.format_exc(),continuation_prompt='The debugger command failed. Return a corrected AI_DEBUG_COMMAND/1 JSON command.'); continue
                if disposition=='skip': state['receipts'].append({'id':t['id'],'action':t['action'],'status':'SKIPPED_BY_AI'}); state['next_index']=i+1; save(state_path,state); break
                if disposition!='retry': raise PlanError(f'bad disposition {disposition}')
    state['status']='COMPLETE'; state['next_index']=len(tx); save(state_path,state); emit('complete',session_id=plan.get('session_id',''),transactions=len(tx),state_path=str(state_path)); return 0

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('plan'); ap.add_argument('--state'); ap.add_argument('--workspace'); ap.add_argument('--fresh',action='store_true'); ap.add_argument('--noninteractive',action='store_true'); ap.add_argument('--max-debug-rounds',type=int,default=32); ns=ap.parse_args()
    pp=pathlib.Path(ns.plan).resolve(); pre=load(pp); ws=pathlib.Path(ns.workspace or pre.get('workspace') or os.getcwd()).resolve(); sp=pathlib.Path(ns.state or str(pp)+'.state.json').resolve()
    try: return run(pp,sp,ws,ns.fresh,ns.noninteractive,ns.max_debug_rounds)
    except AiAbort as e: emit('aborted',reason=str(e),state_path=str(sp)); return 3
    except BaseException as e: emit('fatal',exception_type=type(e).__name__,exception=str(e),traceback=traceback.format_exc(),state_path=str(sp)); return 2
if __name__=='__main__': raise SystemExit(main())
