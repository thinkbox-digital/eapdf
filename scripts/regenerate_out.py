"""Bounded real-input profile matrix; invoked by eapdf-regenerate."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from run_real_world_corpus import available_memory, execute

ROOT = Path(__file__).resolve().parents[1]
KINDS = ('eml', 'msg', 'mbox', 'pst')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inventory(root):
    return sorted((p for p in (root/'in').rglob('*') if p.is_file()), key=lambda p: str(p))


def tasks_for(paths, mode):
    selected = []
    for kind in KINDS:
        candidates = [p for p in paths if p.suffix.lower() == '.'+kind]
        if mode == 'sample':
            candidates = sorted(candidates, key=lambda p: (p.stat().st_size, str(p)))[:2]
        selected.extend(candidates)
    tasks = []
    for path in selected:
        kind = path.suffix.lower()[1:]
        slug=re.sub(r'[^\w.-]+', '_', path.stem)[:70]
        while len(slug.encode())>120: slug=slug[:-1]
        ident = kind+'_'+slug+'_'+hashlib.sha256(str(path.relative_to(ROOT)).encode()).hexdigest()[:10]
        profiles = ('1s','1si','1m','1mi','1c','1ci') if kind in ('pst','mbox') else ('1s','1si')
        tasks.extend({'id':ident+'_'+profile,'type':kind,'profile':profile,'sources':[path]} for profile in profiles)
    for kind in ('eml','msg'):
        group = [p for p in selected if p.suffix.lower() == '.'+kind]
        if group:
            tasks.extend({'id':kind+'_all_'+profile,'type':kind,'profile':profile,'sources':group} for profile in ('1m','1mi','1c','1ci'))
    return selected, tasks


def active_conversion(root):
    for path in Path('/proc').glob('[0-9]*/cmdline'):
        try:
            args = path.read_bytes().split(b'\0')
            if args and b'java' in Path(os.fsdecode(args[0])).name.encode() and any(os.fsencode(root/'out') == a or a.startswith(os.fsencode(root/'out')+b'/') for a in args):
                return path.parent.name
        except (OSError, ValueError):
            pass
    return None


def clear_output(root):
    output = root/'out'
    if output.is_symlink() or output.resolve() != root.resolve()/'out':
        raise RuntimeError('Refusing an out directory that is a symlink or resolves outside the project.')
    output.mkdir(exist_ok=True)
    for child in output.iterdir():
        if child.is_dir() and not child.is_symlink(): shutil.rmtree(child)
        else: child.unlink()
    for kind in KINDS: (output/kind).mkdir()


def publish_pdf(source, destination):
    # The final PDF name appears only after the complete copy is closed.
    checksum = hashlib.sha256()
    handle, pending = tempfile.mkstemp(prefix='.'+destination.stem+'-', suffix='.tmp', dir=destination.parent)
    try:
        with os.fdopen(handle, 'wb') as output, source.open('rb') as original:
            for block in iter(lambda: original.read(1024*1024), b''):
                output.write(block)
                checksum.update(block)
        shutil.copystat(source, pending)
        os.replace(pending, destination)
        return checksum.hexdigest()
    finally:
        Path(pending).unlink(missing_ok=True)


def run_task(task, args, evidence, workspace, config, hashes, heap):
    name, profile = task['id'], task['profile']
    folder = evidence/name; folder.mkdir()
    stage = workspace/name; stage.mkdir()
    generated = stage/'generated'; generated.mkdir()
    scratch = stage/'scratch'; scratch.mkdir()
    sources = task['sources']
    result = {'id':name,'profile':profile,'sources':[str(p.relative_to(ROOT)) for p in sources], 'status':'FAIL','documents':[]}
    try:
        if len(sources)==1: source=sources[0]
        else:
            source=stage/'inputs';source.mkdir()
            for n,path in enumerate(sources):
                parent=source/str(n);parent.mkdir();(parent/path.name).symlink_to(path)
        environment=dict(os.environ, EAPDF_CONFIG=str(config), EAPDF_WORKER_HEAP_BYTES=str(heap), VALIDATOR_CHILD_PROCESSES='1')
        command=['java','-XX:ActiveProcessorCount='+str(args.jobs),'-Djava.io.tmpdir='+str(scratch),'-jar',str(args.creator_jar),'--jobs',str(args.jobs),'--out',str(generated)]
        if profile=='1si': command+=['--omit-source']
        elif profile not in ('1s','1si'): command+=['--archive-profile',profile[1:],'--archive-output',str(generated/(name+'.pdf'))]
        command.append(str(source))
        result['command']=command
        result['conversion']=execute(command,folder/'conversion.log',args.timeout,environment)
        log=(folder/'conversion.log').read_text(errors='replace')
        if result['conversion']['exit_code']:
            if profile in ('1m','1mi') and 'require at least two emails' in log:
                result['status']='NOT_APPLICABLE';result['reason']='Input contains fewer than two emails; standalone and collection profiles cover it.'
            else: result['reason']='Conversion rejected or incomplete; see conversion.log.'
            return result
        pdfs=sorted(generated.rglob('*.pdf'))
        if not pdfs: raise RuntimeError('Creator reported success but produced no PDFs.')
        incomplete=bool(re.search(r'(?:skipped input:|source omitted|INCOMPLETE SOURCE|failed: [1-9]|not processed: [1-9])',log,re.I))
        result['status']='VALIDATING'
        for n,pdf in enumerate(pdfs,1):
            filename=(name if profile not in ('1s','1si') else name.rsplit('_',1)[0]+f'_{n:06d}_{profile}')+'.pdf'
            destination=ROOT/'out'/task['type']/filename
            checksum=publish_pdf(pdf,destination)
            result['documents'].append({'path':str(destination.relative_to(ROOT)),'sha256':checksum,'validation':'PENDING'})
            (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n')
            print('Converted: '+str(destination)+' (validation pending)',flush=True)
        # Publish every complete PDF from this creator job before validation starts.
        for pdf,document in zip(pdfs,result['documents']):
            filename=Path(document['path']).name
            report=folder/(filename+'.validation.json')
            validation=execute(['java','-Xmx'+str(max(256,heap//1024**2))+'m','-XX:ActiveProcessorCount='+str(args.jobs),'-Djava.io.tmpdir='+str(scratch),'-cp',str(args.validator_jar),'eapdf.JavaValidation',str(pdf),str(report)],folder/(filename+'.validation.log'),args.timeout,environment)
            status='UNAVAILABLE'
            if report.exists(): status=json.loads(report.read_text()).get('summary',{}).get('status','UNAVAILABLE')
            if status not in ('PASS','WARN') or validation['exit_code'] not in (0,2): result['status']='INCOMPLETE' if status in ('INCOMPLETE','UNAVAILABLE') else 'FAIL'
            document.update(validation=status,validation_execution=validation)
            (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n')
            print('Validated: '+document['path']+' — '+status,flush=True)
        if result['status']=='VALIDATING': result['status']='INCOMPLETE' if incomplete else 'PASS'
        if any(digest(p)!=hashes[str(p)] for p in sources): raise RuntimeError('Original source hash changed during conversion.')
    except Exception as error:
        result['status']='FAIL';result['reason']=str(error)
    finally:
        # Retain creator receipts/progress outside out, never mix them with published PDFs.
        for p in generated.rglob('*'):
            if p.is_file() and p.suffix.lower()!='.pdf':
                dest=folder/'creator-evidence'/p.relative_to(generated);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dest)
        (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


def snapshot_jars(args, evidence):
    records = {}
    for role in ('creator', 'validator'):
        source = getattr(args, role+'_jar').resolve()
        destination = evidence/'runtime'/role/source.name
        destination.parent.mkdir(parents=True)
        checksum = digest(source)
        shutil.copy2(source, destination)
        if digest(destination) != checksum:
            raise RuntimeError('JAR changed while preparing the run: '+str(source))
        setattr(args, role+'_jar', destination)
        records[role] = {'filename':source.name,'sha256':checksum}
    (evidence/'runtime-jars.json').write_text(json.dumps(records,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description='Clear project out/ and regenerate a sample or every supported in/ file. Logs and Java validation reports go under output/.',epilog='Examples: eapdf-regenerate sample; eapdf-regenerate everything --workers 4 --jobs 2. Use --dry-run to preview without clearing out/. EML/MSG archives group all selected sources; PST/MBOX are converted individually to all six profiles. Single-email 1m/1mi cases are reported not applicable.')
    parser.add_argument('mode',choices=('sample','everything'))
    parser.add_argument('--creator',default=os.environ.get('EAPDF_CREATOR'))
    parser.add_argument('--workers',type=int,help='Concurrent Java jobs (default: CPU/memory bounded auto)')
    parser.add_argument('--jobs',type=int,default=2,help='Creator threads per process (default: 2)')
    parser.add_argument('--timeout',type=int,default=86400,help='Seconds per conversion/validation (default: 86400)')
    parser.add_argument('--dry-run',action='store_true')
    version=(ROOT/'VERSION').read_text().strip()
    parser.add_argument('--creator-jar',type=Path,default=ROOT/'dist'/f'emailtopdfa-{version}.jar')
    parser.add_argument('--validator-jar',type=Path,default=ROOT/'dist'/f'eapdf-validator-{version}.jar')
    args=parser.parse_args()
    if args.jobs<1 or args.timeout<1 or (args.workers is not None and args.workers<1): parser.error('workers, jobs and timeout must be positive')
    memory=int(available_memory()*.65)
    args.workers=args.workers or max(1,min(max(1,(os.cpu_count() or 2)//args.jobs),memory//(3*1024**3)))
    heap=max(256*1024**2,memory//args.workers-1024**3)
    if memory//args.workers<1280*1024**2: parser.error('Too many workers for currently available memory; reduce --workers.')
    paths=inventory(ROOT);selected,tasks=tasks_for(paths,args.mode)
    if not selected: parser.error('No supported inputs found under in/.')
    print(f'{args.mode}: {len(selected)} sources, {len(tasks)} profile jobs; {args.workers} processes × {args.jobs} threads; {heap//1024**2} MiB heap per job.',flush=True)
    if args.dry_run:
        for task in tasks: print(task['id']+': '+', '.join(str(p.relative_to(ROOT)) for p in task['sources']))
        print('Dry run: out/ was not changed.');return 0
    config_path=Path(os.environ.get('EAPDF_CONFIG',ROOT/'config/eapdf_config.json'))
    config=json.loads(config_path.read_text());config['creator']=args.creator or config.get('creator') or 'Thinkbox.DIGITAL'
    if not config['creator'].strip(): config['creator']='Thinkbox.DIGITAL'
    for jar in (args.creator_jar,args.validator_jar):
        if not jar.is_file(): parser.error('Missing versioned JAR: '+str(jar))
    if not shutil.which('java'): parser.error('Java is not installed.')
    running=active_conversion(ROOT)
    if running: parser.error('Another Java conversion is using out/ (PID '+running+'). Wait for it to finish.')
    for key in ('branding','findingAid','premis'): config.pop(key,None)
    config.setdefault('accessibility',{})['addPdfUaFeatures']=True
    config['searchIndex']={'enabled':True,'fullTextEnabled':True}
    config.setdefault('signing',{})['enabled']=False
    (ROOT/'output').mkdir(exist_ok=True)
    with (ROOT/'output/.regenerate-out.lock').open('w') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: parser.error('Another regeneration run is active.')
        evidence=Path(tempfile.mkdtemp(prefix='regenerate-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-',dir=ROOT/'output'))
        snapshot_jars(args,evidence)
        hashes={str(p):digest(p) for p in selected}
        (evidence/'sources.json').write_text(json.dumps({'selected':[{'path':str(p.relative_to(ROOT)),'sha256':hashes[str(p)]} for p in selected],'not_selected':[str(p.relative_to(ROOT)) for p in paths if p not in selected],'unsupported':[str(p.relative_to(ROOT)) for p in paths if p.suffix.lower()[1:] not in KINDS]},indent=2)+'\n')
        config_file=evidence/'config.json';config_file.write_text(json.dumps(config,indent=2)+'\n')
        print('Clearing '+str(ROOT/'out')+'; evidence: '+str(evidence),flush=True)
        clear_output(ROOT)
        results=[]
        with tempfile.TemporaryDirectory(prefix='eapdf-regenerate-',dir='/tmp') as directory, ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures=[pool.submit(run_task,t,args,evidence,Path(directory),config_file,hashes,heap) for t in tasks]
            for future in as_completed(futures):
                result=future.result();results.append(result);print(result['status']+' '+result['id'],flush=True)
                (evidence/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
        changed=[str(p) for p in selected if digest(p)!=hashes[str(p)]]
        (evidence/'source-check.json').write_text(json.dumps({'unchanged':not changed,'changed':changed},indent=2)+'\n')
        failed=[r for r in results if r['status'] not in ('PASS','NOT_APPLICABLE')]
        print(f'Finished: {len(results)} profile jobs; {len(failed)} failed/incomplete. Reports: {evidence}',flush=True)
        return 1 if failed or changed else 0

if __name__=='__main__':
    sys.exit(main())
