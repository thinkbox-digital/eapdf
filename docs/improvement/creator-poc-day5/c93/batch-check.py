from pathlib import Path
import subprocess,os,json,hashlib,shutil,time
root=Path.cwd();base=Path('/tmp/eapdf-creator-core-review/batch');shutil.rmtree(base,ignore_errors=True);base.mkdir();inputs=base/'inputs';inputs.mkdir(exist_ok=True)
for name in ('a','b'):(inputs/(name+'.eml')).write_bytes(('From: sender@example.org\r\nDate: Fri, 23 Oct 2026 12:00:00 +0000\r\nSubject: Same subject\r\n\r\nMessage '+name).encode())
(inputs/'notes.txt').write_text('Not an email')
config=base/'config.json';config.write_text(json.dumps({'branding':{'enabled':False},'premis':{'enabled':False}}));env=dict(os.environ,EAPDF_CONFIG=str(config));jar=root/'dist/emailtopdfa.jar';receipt={}
def cmd(output,jobs=1,source=inputs):return ['java','-Djava.awt.headless=true','-jar',str(jar),'--jobs',str(jobs),'--out',str(output),str(source)]
def run(name,output,jobs=1):
 p=subprocess.run(cmd(output,jobs),capture_output=True,text=True,env=env,timeout=90);(base/(name+'.log')).write_text(p.stdout+p.stderr);return p
for jobs in (1,2):
 output=base/('jobs-'+str(jobs));p=run('first-'+str(jobs),output,jobs);assert p.returncode==0,p.stdout+p.stderr
 files={str(q.name):hashlib.sha256(q.read_bytes()).hexdigest() for q in output.glob('*.pdf')};assert len(files)==2
 p=run('repeat-'+str(jobs),output,jobs);assert p.returncode==0,p.stdout+p.stderr
 assert all(hashlib.sha256((output/name).read_bytes()).hexdigest()==h for name,h in files.items());assert len(list(output.glob('*.pdf')))==4
 assert 'skipped: 1 completed: 2 failed: 0 not processed: 0' in p.stdout,p.stdout
 receipt['jobs-'+str(jobs)]={'initial_files':2,'after_repeat':4,'originals_unchanged':True,'summary_correct':True}
# Fail after a good message was staged: no partial batch may be published.
(inputs/'z-bad.eml').write_text('From: sender@example.org\r\nSubject: No sent date\r\n\r\nBody')
for jobs in (1,2):
 output=base/('fail-'+str(jobs));output.mkdir(exist_ok=True);sentinel=output/'keep.pdf';sentinel.write_bytes(b'previous output');p=run('failure-'+str(jobs),output,jobs)
 assert p.returncode!=0;assert sentinel.read_bytes()==b'previous output';assert list(output.glob('*.pdf'))==[sentinel];assert not list(output.glob('*.xlsx'));assert not list(output.glob('*_premis.xml'))
 assert 'done. inputs:' not in p.stdout
 receipt['failure-'+str(jobs)]={'failed_exit':p.returncode,'previous_output_unchanged':True,'no_partial_publication':True}
# Terminate a real supervised conversion while its child is reading/rendering.
source=base/'long.eml';source.write_text('From: sender@example.org\r\nDate: Fri, 23 Oct 2026 12:00:00 +0000\r\nSubject: Stop run\r\n\r\n'+'Body text for an interrupted conversion.\n'*40000)
output=base/'cancel';output.mkdir(exist_ok=True);sentinel=output/'keep.pdf';sentinel.write_bytes(b'previous output')
log=base/'cancel.log'
with log.open('w') as stream:
 p=subprocess.Popen(cmd(output,1,source),stdout=stream,stderr=subprocess.STDOUT,env=env)
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  if 'input:' in log.read_text():break
  assert p.poll() is None,'Conversion ended before cancellation';time.sleep(.05)
 else:raise AssertionError('Worker did not start')
 p.terminate();p.wait(timeout=20)
assert p.returncode!=0;assert sentinel.read_bytes()==b'previous output';assert list(output.glob('*.pdf'))==[sentinel];assert not list(output.glob('*.xlsx'));assert not list(output.glob('*_premis.xml'))
receipt['cancellation']={'exit':p.returncode,'previous_output_unchanged':True,'no_partial_publication':True}
(base/'results.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
