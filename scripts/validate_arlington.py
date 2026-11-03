#!/usr/bin/env python3
"""Run the pinned standalone Java Arlington checker on PDFs, without modifying them."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor,as_completed
from collections import Counter
import argparse,csv,hashlib,json,os,subprocess,tempfile,time,zipfile
ROOT=Path(__file__).resolve().parents[1]

def digest(path):
    with path.open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()

def save(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_suffix(path.suffix+'.tmp');temporary.write_text(json.dumps(value,indent=2)+'\n');temporary.replace(path)

def summarize(document,exit_code):
    """A completed upstream run can report deviations; missing checks are never PASS."""
    report=document['report'];batch=report['batchSummary'];jobs=report['jobs']
    if exit_code not in (0,1):raise ValueError('Checker did not exit normally')
    if len(jobs)!=1 or batch['totalJobs']!=1:raise ValueError('Expected one complete file report')
    if any(batch.get(key,0) for key in ('outOfMemory','veraExceptions','failedEncryptedJobs','failedParsingJobs')):raise ValueError('Checker reports incomplete processing')
    if batch['validationSummary']['successfulJobCount']!=1 or batch['validationSummary']['failedJobCount']!=0:raise ValueError('Validation job did not complete')
    results=jobs[0]['arlingtonResult']
    if len(results)!=1 or results[0]['jobEndStatus']!='normal':raise ValueError('Arlington result did not complete normally')
    result=results[0];details=result['details'];rules=[];count=0;deprecations=0
    for rule in details.get('ruleSummaries',[]):
        if rule.get('ruleStatus')!='FAILED':continue
        checks=rule.get('checks',[]);failed=rule['failedChecks']
        if len(checks)!=failed:raise ValueError('Finding details were truncated')
        deprecated=' is deprecated since PDF ' in rule.get('description','')
        count+=failed
        if deprecated:deprecations+=failed
        rules.append({'clause':rule['clause'],'test_number':rule['testNumber'],'category':'DEPRECATION' if deprecated else 'DEVIATION','occurrences':failed,'description':rule['description'],'example':checks[0].get('errorMessage','') if checks else ''})
    if count!=details['failedChecks'] or len(rules)!=details['failedRules']:raise ValueError('Incomplete rule/finding totals')
    if bool(result['compliant'])!=(count==0):raise ValueError('Contradictory checker result')
    return {'status':'DEVIATIONS' if count>deprecations else 'WARNINGS_ONLY' if count else 'PASS','checker_compliant':result['compliant'],'profile':result['profileName'],'extensions':result.get('extensions',''),'failed_checks':count,'deprecation_occurrences':deprecations,'other_deviation_occurrences':count-deprecations,'passed_checks':details['passedChecks'],'rules':rules}

def run_file(source,relative,args,pin,jar):
    folder=args.reports/'files'/relative.parent;folder.mkdir(parents=True,exist_ok=True)
    report=folder/(relative.name+'.json');log=folder/(relative.name+'.log')
    before=digest(source);row={'file':str(source),'relative_file':str(relative),'bytes':source.stat().st_size,'sha256':before,'report':str(report),'log':str(log)}
    started=time.monotonic()
    try:
        # Scratch files are task-owned and removed even after timeout or failure.
        staging=ROOT/'.generator_web_tmp';staging.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='arlington-',dir=staging) as scratch:
            command=['java',f'-Xmx{args.heap_mb}m','-XX:ActiveProcessorCount=2',f'-Djava.io.tmpdir={scratch}','-Djava.awt.headless=true','-cp',str(jar.parent.parent/'model-corrections')+os.pathsep+str(jar.parent.parent/'unlimited-reporting')+os.pathsep+str(jar),'org.verapdf.apps.GreenfieldCliWrapper','--format','json','--addlogs','--extensions',','.join(pin['extensions']),'--maxfailures','-1','--maxfailuresdisplayed','-1',str(source)]
            row['command']=command
            with report.open('w') as stdout,log.open('w') as stderr:
                process=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=args.timeout)
            row['exit_code']=process.returncode
            row.update(summarize(json.loads(report.read_text()),process.returncode))
    except Exception as error:
        row.update(status='INCOMPLETE',reason=str(error))
    row['source_unchanged']=digest(source)==before
    if not row['source_unchanged']:row.update(status='INCOMPLETE',reason='Input bytes changed during checking')
    row['seconds']=round(time.monotonic()-started,2);save(report.with_suffix('.summary.json'),row)
    return row

def write_summary(args,pin,results,expected,complete=False):
    summary={'scope':'Top-level PDF files under the input directory; embedded PDF payloads are not separately opened by this runner. This is Arlington checking, not EA-PDF or PDF/A certification.','tool':pin,'completed':len(results),'expected':expected,'complete':complete,'status_counts':dict(Counter(r['status'] for r in results)),'resource_settings':{'concurrent_files':args.jobs,'heap_mb_per_file':args.heap_mb,'timeout_seconds_per_file':args.timeout,'maxfailures':-1,'maxfailuresdisplayed':-1},'results':sorted(results,key=lambda r:r['relative_file'])}
    save(args.reports/'summary.json',summary)
    return summary

def write_rule_summary(reports,results):
    grouped={}
    for row in results:
        for rule in row.get('rules',[]):
            key=(row.get('profile',''),rule['clause'],rule['test_number'],rule['category'])
            item=grouped.setdefault(key,{'profile':key[0],'clause':key[1],'test_number':key[2],'category':key[3],'occurrences':0,'files':set(),'description':rule['description'],'example':rule['example']})
            item['occurrences']+=rule['occurrences'];item['files'].add(row['relative_file'])
    rows=[]
    for item in grouped.values():
        item['affected_files']=len(item.pop('files'));rows.append(item)
    rows.sort(key=lambda item:(item['category']=='DEPRECATION',-item['affected_files'],-item['occurrences'],item['clause']))
    save(reports/'findings.json',rows)
    fields=['profile','clause','test_number','category','affected_files','occurrences','description','example']
    with (reports/'findings.csv').open('w',newline='') as output:
        writer=csv.DictWriter(output,fieldnames=fields);writer.writeheader();writer.writerows(rows)
    return rows

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input',nargs='?',type=Path,default=ROOT/'out')
    parser.add_argument('--reports',type=Path,default=ROOT/'output/arlington')
    parser.add_argument('--jobs',type=int,default=2)
    parser.add_argument('--heap-mb',type=int,default=4096)
    parser.add_argument('--timeout',type=int,default=1800)
    args=parser.parse_args();args.input=args.input.resolve();args.reports=args.reports.resolve()
    if min(args.jobs,args.heap_mb,args.timeout)<1:parser.error('Resource settings must be positive')
    pin=json.loads((ROOT/'config/arlington.json').read_text());jar=ROOT/'dist/tools'/('arlington-'+pin['version'])/'bin'/('cli-arlington-'+pin['version']+'.jar')
    if not jar.exists():parser.error('Install the checker first: python3 scripts/install_arlington.py')
    if digest(jar)!=pin['jar_sha256']:parser.error('Arlington JAR checksum mismatch')
    with zipfile.ZipFile(jar) as archive:
        if hashlib.sha256(archive.read('org/verapdf/pdfa/validation/ARLINGTON2-0.xml')).hexdigest()!=pin['pdf20_profile_sha256']:parser.error('Arlington profile checksum mismatch')
    from arlington_adapter import verify as verify_adapter
    try:pin['report_adapter']['installed']=verify_adapter(jar.parent.parent/'unlimited-reporting',pin['report_adapter'])
    except (OSError,ValueError,RuntimeError) as error:parser.error('Install/verify the reporting adapter first: '+str(error))
    from arlington_semantics import verify as verify_semantics
    try:pin['model_adapter']['installed']=verify_semantics(jar.parent.parent/'model-corrections',pin['model_adapter'])
    except (OSError,ValueError,RuntimeError) as error:parser.error('Install/verify the model adapter first: '+str(error))
    files=[args.input] if args.input.is_file() else sorted(p for p in args.input.rglob('*') if p.is_file() and p.suffix.lower()=='.pdf')
    if not files:parser.error('No PDF inputs found')
    args.reports.mkdir(parents=True,exist_ok=True)
    inventory=[{'file':str(p),'bytes':p.stat().st_size,'sha256':digest(p)} for p in files];save(args.reports/'input-files.json',inventory)
    results=[]
    # Start the largest PDF early while smaller files use the other worker.
    ordered=sorted(files,key=lambda p:p.stat().st_size,reverse=True)
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures=[pool.submit(run_file,p,Path(p.name) if args.input.is_file() else p.relative_to(args.input),args,pin,jar) for p in ordered]
        for future in as_completed(futures):
            row=future.result();results.append(row);write_summary(args,pin,results,len(files));print(json.dumps({'completed':len(results),'expected':len(files),'file':row['relative_file'],'status':row['status']}),flush=True)
    changed=[r['file'] for r in inventory if digest(Path(r['file']))!=r['sha256']]
    for row in results:
        if row['file'] in changed:row.update(status='INCOMPLETE',reason='Input changed since inventory')
    summary=write_summary(args,pin,results,len(files),True)
    write_rule_summary(args.reports,summary['results'])
    columns=['relative_file','status','other_deviation_occurrences','deprecation_occurrences','seconds','sha256']
    with (args.reports/'files.csv').open('w',newline='') as output:
        writer=csv.DictWriter(output,fieldnames=columns,extrasaction='ignore');writer.writeheader();writer.writerows(summary['results'])
    lines=['# Arlington validation results','',summary['scope'],'','Checker '+pin['version']+'; model revision `'+pin['model_revision']+'`; EA-PDF extension enabled. Finding limits are disabled. Counts are reported occurrences, not unique PDF objects. Deprecations remain failures in the raw upstream report; this summary lists them separately.','','| File | Result | Other deviations | Deprecations |','| --- | --- | ---: | ---: |']
    for row in summary['results']:lines.append(f"| {row['relative_file']} | {row['status']} | {row.get('other_deviation_occurrences','—')} | {row.get('deprecation_occurrences','—')} |")
    (args.reports/'RESULTS.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps({'complete':True,'status_counts':summary['status_counts'],'changed_inputs':changed}),flush=True)
    return 2 if any(r['status']=='INCOMPLETE' for r in results) else 1 if any(r['status']!='PASS' for r in results) else 0
if __name__=='__main__':raise SystemExit(main())
