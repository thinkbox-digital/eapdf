"""Bounded real-input profile matrix; invoked by eapdf-regenerate."""
import argparse
from contextlib import ExitStack
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import random
import shutil
import subprocess
import sys
import tempfile
from run_real_world_corpus import available_memory, execute

ROOT = Path(__file__).resolve().parents[1]
KINDS = ('eml', 'msg', 'mbox', 'pst')
ARCHIVE_PROFILES = ('1m', '1mi', '1c', '1ci')
ARCHIVE_GROUP_SIZE = 20
MAX_PST_BYTES = 250_000_000  # 250 MB (decimal), inclusive.


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inventory(root):
    return sorted((p for p in (root/'in').rglob('*') if p.is_file()), key=lambda p: str(p))


def oversized_pst(path):
    return path.suffix.lower() == ".pst" and path.stat().st_size > MAX_PST_BYTES


def tasks_for(paths, mode):
    selected = []
    for kind in KINDS:
        candidates = [p for p in paths if p.suffix.lower() == '.'+kind and not oversized_pst(p)]
        if mode == 'sample':
            candidates = sorted(candidates, key=lambda p: (p.stat().st_size, str(p)))[:2]
        selected.extend(candidates)
    tasks = []
    for path in selected:
        kind = path.suffix.lower()[1:]
        slug=re.sub(r'[^\w.-]+', '_', path.stem)[:70]
        while len(slug.encode())>120: slug=slug[:-1]
        ident = kind+'_'+slug+'_'+hashlib.sha256(str(path.relative_to(ROOT)).encode()).hexdigest()[:10]
        profiles = ARCHIVE_PROFILES if kind in ('pst','mbox') else ('1s','1si')
        tasks.extend({'id':ident+'_'+profile,'type':kind,'profile':profile,'sources':[path]} for profile in profiles)
    for kind in ('eml','msg'):
        group = [p for p in selected if p.suffix.lower() == '.'+kind]
        if group:
            random.SystemRandom().shuffle(group)
            # Balance the final groups so a remainder of one does not create a
            # single-message archive when there are enough sources to share.
            count = (len(group)+ARCHIVE_GROUP_SIZE-1)//ARCHIVE_GROUP_SIZE
            for index in range(count):
                members = group[index::count]
                tasks.extend({'id':f'{kind}_group_{index+1:03d}_{profile}',
                              'type':kind,'profile':profile,'sources':members}
                             for profile in ARCHIVE_PROFILES)
    # Keep each input type together, with grouped archives before its standalone
    # PDFs. EML/MSG work precedes the longer whole-mailbox jobs.
    tasks.sort(key=lambda task: (KINDS.index(task['type']),
                                 task['profile'] not in ARCHIVE_PROFILES))
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


def prepare_output(root):
    output = root/'out'
    if output.is_symlink() or output.resolve() != root.resolve()/'out':
        raise RuntimeError('Refusing a symlinked or out-of-tree output directory.')
    output.mkdir(exist_ok=True)
    for kind in KINDS:
        folder = output/kind
        if folder.is_symlink():
            raise RuntimeError('Refusing a symlinked output directory: '+str(folder))
        folder.mkdir(exist_ok=True)


def publish_run(results):
    """Publish verified PDFs using a hard link as an atomic no-replace operation."""
    prepare_output(ROOT)
    for result in sorted(results, key=lambda entry: entry['id']):
        for document in result['documents']:
            source = Path(document['staged'])
            destination = ROOT/document['path']
            stem, profile = destination.stem.rsplit('_', 1)
            if digest(source) != document['sha256']:
                raise RuntimeError('Staged PDF changed before publication: '+str(source))
            output = publish_pdf(source, destination, stem, profile, document['sha256'])
            document['report_basename'] = destination.name
            document['path'] = str(output.relative_to(ROOT))
            document['published'] = True


def publish_pdf(source, destination, stem, profile, expected_hash):
    # The final PDF name appears only after the complete copy is closed.
    checksum = hashlib.sha256()
    handle, pending = tempfile.mkstemp(prefix='.'+destination.stem+'-', suffix='.tmp', dir=destination.parent)
    try:
        with os.fdopen(handle, 'wb') as output, source.open('rb') as original:
            for block in iter(lambda: original.read(1024*1024), b''):
                output.write(block)
                checksum.update(block)
        shutil.copystat(source, pending)
        if checksum.hexdigest() != expected_hash:
            raise RuntimeError('Staged PDF changed while copying: '+str(source))
        candidate = destination
        copy = 0
        while True:
            try:
                os.link(pending, candidate)
                return candidate
            except FileExistsError:
                if not candidate.is_symlink() and candidate.is_file() and digest(candidate) == expected_hash:
                    return candidate
                copy += 1
                suffix = expected_hash[:12] + ('' if copy == 1 else f'-{copy}')
                candidate = destination.with_name(f'{stem}_{suffix}_{profile}.pdf')
    finally:
        Path(pending).unlink(missing_ok=True)



def validation_environment(environment, pdf, workers=1):
    """Share available disk between jobs; preserve explicit operator limits."""
    configured=dict(environment)
    share=max(1,shutil.disk_usage(pdf.parent).free//(2*max(1,workers)))
    configured.setdefault('VALIDATOR_MAX_WORK_FILE_BYTES',str(max(1,share//2)))
    configured.setdefault('VALIDATOR_MAX_SCRATCH_BYTES',str(share))
    return configured


def validation_status(report):
    # Current Java validators stream large reports and write a small summary sidecar.
    summary=Path(str(report)+'.summary.json')
    selected=summary if summary.is_file() else report
    if not selected.is_file(): return 'UNAVAILABLE'
    with selected.open() as stream:
        return json.load(stream).get('summary',{}).get('status','UNAVAILABLE')


def _run_task(task, args, evidence, workspace, config, hashes, heap, warning=False):
    name, profile = task['id'], task['profile']
    folder = evidence/name
    if warning:
        folder = folder/'warning-attempt'
    folder.mkdir(parents=warning)
    stage = workspace/(name+'-validation-warning' if warning else name); stage.mkdir()
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
        disk=validation_environment({},generated,getattr(args,'workers',1))
        environment.setdefault('EAPDF_MAX_TEMP_BYTES',disk['VALIDATOR_MAX_SCRATCH_BYTES'])
        environment.setdefault('EAPDF_MAX_OUTPUT_BYTES',disk['VALIDATOR_MAX_WORK_FILE_BYTES'])
        result['conversion_limits']={key:environment[key] for key in ('EAPDF_MAX_TEMP_BYTES','EAPDF_MAX_OUTPUT_BYTES')}
        command=['java','-XX:ActiveProcessorCount='+str(args.jobs),'-Djava.io.tmpdir='+str(scratch),'-jar',str(args.creator_jar),'--jobs',str(args.jobs),'--out',str(generated)]
        if profile=='1si': command+=['--omit-source']
        elif profile not in ('1s','1si'): command+=['--archive-profile',profile[1:],'--archive-output',str(generated/(name+'.pdf'))]
        command.append(str(source))
        result['command']=command
        result['conversion']=execute(command,folder/'conversion.log',args.timeout,environment)
        log=(folder/'conversion.log').read_text(errors='replace')
        if result['conversion']['exit_code']:
            if profile in ('1m','1mi') and 'require at least two emails' in log:
                result['status']='NOT_APPLICABLE';result['reason']='Input contains fewer than two emails; collection profiles cover it.'
            else: result['reason']='Conversion rejected or incomplete; see conversion.log.'
            return result
        if warning and 'VALIDATION WARNING BANNER:' not in log:
            raise RuntimeError('Creator did not confirm the validation banner; use the updated creator JAR.')
        pdfs=sorted(generated.rglob('*.pdf'))
        if not pdfs: raise RuntimeError('Creator reported success but produced no PDFs.')
        incomplete=bool(re.search(r'(?:skipped input:|source omitted|INCOMPLETE SOURCE|failed: [1-9]|not processed: [1-9])',log,re.I))
        if incomplete: result['reason']='Creator reported incomplete or omitted source data; see conversion.log.'
        result['status']='VALIDATING'
        for n,pdf in enumerate(pdfs,1):
            filename=(name if profile not in ('1s','1si') else name.rsplit('_',1)[0]+f'_{n:06d}_{profile}')+'.pdf'
            destination=ROOT/'out'/task['type']/filename
            result['documents'].append({'path':str(destination.relative_to(ROOT)),'staged':str(pdf),'sha256':digest(pdf),'validation':'PENDING'})
            (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n')
            print('Staged: '+filename+' (validation pending)',flush=True)
        # Validate the staged bytes before any output is published.
        for pdf,document in zip(pdfs,result['documents']):
            filename=Path(document['path']).name
            report=folder/(filename+'.validation.json')
            validation_env=validation_environment(environment,pdf,getattr(args,'workers',1))
            document['validation_limits']={key:validation_env[key] for key in ('VALIDATOR_MAX_WORK_FILE_BYTES','VALIDATOR_MAX_SCRATCH_BYTES')}
            validation=execute(['java','-Xmx'+str(max(256,heap//1024**2))+'m','-XX:ActiveProcessorCount='+str(args.jobs),'-Djava.io.tmpdir='+str(scratch),'-cp',str(args.validator_jar),'eapdf.JavaValidation',str(pdf),str(report)],folder/(filename+'.validation.log'),args.timeout,validation_env)
            status=validation_status(report)
            if status not in ('PASS','WARN') or validation['exit_code'] not in (0,2):
                outcome='INCOMPLETE' if status in ('INCOMPLETE','UNAVAILABLE','PENDING') else 'FAIL'
                if result['status']!='FAIL': result['status']=outcome
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




def run_task(task, args, evidence, workspace, config, hashes, heap):
    result=_run_task(task,args,evidence,workspace,config,hashes,heap)
    if not any(d.get('validation') in ('FAIL','INCOMPLETE','UNAVAILABLE') for d in result['documents']):
        return result
    folder=evidence/task['id']
    initial=folder/'initial-validation';initial.mkdir()
    # Preserve the first verdict and all diagnostic evidence before regenerating.
    for path in list(folder.iterdir()):
        if path==initial: continue
        if path.is_dir(): shutil.copytree(path,initial/path.name)
        else: shutil.copy2(path,initial/path.name)
    warning_config=json.loads(config.read_text())
    failed=any(d.get('validation')=='FAIL' for d in result['documents'])
    warning_config['validationWarning']=('One or more validation checks failed.' if failed else 'One or more validation checks could not be completed.')+' Conformance was not established by the initial check. Consult the initial and final validation reports.'
    config_path=folder/'validation-warning-config.json'
    config_path.write_text(json.dumps(warning_config,indent=2)+'\n')
    print('Adding validation warning: '+task['id'],flush=True)
    replacement=_run_task(task,args,evidence,workspace,config_path,hashes,heap,warning=True)
    replacement['validation_warning']=True
    replacement['initial_validation_status']=result['status']
    replacement['initial_validation_evidence']=str(initial)
    # Keep the first attempt in staging when the warning conversion fails.
    if not replacement['documents']:
        replacement['documents']=result['documents']
        replacement['validation_warning']=False
        replacement['reason']='Could not generate the validation-warning replacement; initial attempt kept in staging. '+replacement.get('reason','')
    for report in folder.glob('*.validation*'):
        report.unlink()
    for report in (folder/'warning-attempt').glob('*.validation*'):
        report.replace(folder/report.name)
    if (folder/'warning-attempt/conversion.log').is_file():
        shutil.copy2(folder/'warning-attempt/conversion.log', folder/'conversion.log')
    (folder/'result.json').write_text(json.dumps(replacement,indent=2)+'\n')
    return replacement


def final_summary(results, evidence, changed=()):
    """Print and retain the same concise completion report, including missing outputs."""
    counts={status:sum(r['status']==status for r in results)
            for status in ('PASS','FAIL','INCOMPLETE','NOT_APPLICABLE')}
    lines=[f"Finished: {len(results)} profile jobs; " + '; '.join(f"{n} {status}" for status,n in counts.items()) + '.']
    problems=sorted((r for r in results if r['status'] not in ('PASS','NOT_APPLICABLE')),key=lambda r:r['id'])
    if not problems: lines.append('No failed or incomplete files.')
    for result in problems:
        folder=evidence/result['id']
        lines.append(f"{result['status']}: {result['id']}")
        if result.get('reason'): lines.append('  Reason: '+result['reason'])
        lines.append('  Sources: '+', '.join(result['sources']))
        documents=result.get('documents',[])
        affected=[d for d in documents if d.get('validation') not in ('PASS','WARN')
                  or d.get('validation_execution',{}).get('exit_code',1) not in (0,2)]
        # Source omissions can leave a valid PDF but an incomplete conversion.
        for document in affected or documents:
            lines.append('  File: '+str(ROOT/document['path']) + (' (staged, unpublished)' if 'staged' in document and not document.get('published') else '')+' (validation: '+document.get('validation','UNAVAILABLE')+')')
            report=folder/(document.get('report_basename', Path(document['path']).name)+'.validation.json')
            if report.is_file(): lines.append('  Report: '+str(report))
            log=folder/(document.get('report_basename', Path(document['path']).name)+'.validation.log')
            if log.is_file(): lines.append('  Validation log: '+str(log))
        if not documents: lines.append('  No PDF produced.')
        lines.append('  Conversion log: '+str(folder/'conversion.log'))
    if changed:
        lines.append('SOURCE INTEGRITY FAIL: '+', '.join(changed))
    lines.append('Reports: '+str(evidence))
    text='\n'.join(lines)+'\n'
    (evidence/'summary.txt').write_text(text)
    print(text,end='',flush=True)
    return bool(problems or changed)



def working_directory():
    # Large mail archives cannot use a memory-backed /tmp for staging and scratch.
    root=ROOT/'.generator_web_tmp'
    root.mkdir(exist_ok=True)
    return tempfile.TemporaryDirectory(prefix='eapdf-regenerate-',dir=root)


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
    parser=argparse.ArgumentParser(description='Regenerate a sample or every supported in/ file. Logs and Java validation reports go under output/.',epilog='Examples: eapdf-regenerate sample; eapdf-regenerate everything --workers 4 --jobs 2. Use --dry-run to preview without writing to out/. EML/MSG sources are randomly grouped by type into archives of up to 20 messages, covering every selected source. Each PST/MBOX source produces only 1m, 1mi, 1c and 1ci archives; no individual-message extraction. PST files larger than 250 MB (250,000,000 bytes) are skipped. Single-email 1m/1mi cases are reported not applicable.')
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
    skipped=[{'path':str(p.relative_to(ROOT)),'size_bytes':p.stat().st_size,
              'reason':'PST exceeds 250 MB (250,000,000 bytes)'} for p in paths if oversized_pst(p)]
    for item in skipped: print(f"SKIP {item['path']}: {item['reason']} ({item['size_bytes']} bytes)",flush=True)
    if not selected: parser.error('No eligible supported inputs found under in/.')
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
    config['validationWarning']=''
    config.setdefault('signing',{})['enabled']=False
    (ROOT/'output').mkdir(exist_ok=True)
    with ExitStack() as resources:
        lock = os.open(ROOT, os.O_RDONLY | os.O_DIRECTORY)
        resources.callback(os.close, lock)
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: parser.error('Another regeneration run is active.')
        evidence=Path(tempfile.mkdtemp(prefix='regenerate-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-',dir=ROOT/'output'))
        snapshot_jars(args,evidence)
        hashes={str(p):digest(p) for p in selected}
        (evidence/'sources.json').write_text(json.dumps({'selected':[{'path':str(p.relative_to(ROOT)),'sha256':hashes[str(p)]} for p in selected],'skipped':skipped,'not_selected':[str(p.relative_to(ROOT)) for p in paths if p not in selected],'unsupported':[str(p.relative_to(ROOT)) for p in paths if p.suffix.lower()[1:] not in KINDS]},indent=2)+'\n')
        (evidence/'plan.json').write_text(json.dumps([{**task,'sources':[str(p.relative_to(ROOT)) for p in task['sources']]} for task in tasks],indent=2)+'\n')
        config_file=evidence/'config.json';config_file.write_text(json.dumps(config,indent=2)+'\n')
        print('Preparing verified outputs; evidence: '+str(evidence),flush=True)
        results=[]
        with working_directory() as directory, ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures=[pool.submit(run_task,t,args,evidence,Path(directory),config_file,hashes,heap) for t in tasks]
            for future in as_completed(futures):
                result=future.result();results.append(result);print(result['status']+' '+result['id'],flush=True)
                (evidence/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
            changed=[str(p) for p in selected if digest(p)!=hashes[str(p)]]
            if not changed and all(row['status'] in ('PASS','NOT_APPLICABLE') for row in results):
                publish_run(results)
                (evidence/'summary.json').write_text(json.dumps(results,indent=2)+'\n')
        (evidence/'source-check.json').write_text(json.dumps({'unchanged':not changed,'changed':changed},indent=2)+'\n')
        return 1 if final_summary(results,evidence,changed) else 0

if __name__=='__main__':
    sys.exit(main())
