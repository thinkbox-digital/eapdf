#!/usr/bin/env python3
"""Compare outputs first, then summarize paired experimental measurements."""
import json, os, statistics, tempfile, itertools
from pathlib import Path
work=Path(os.environ.get('PDFJS_COMPARISON_WORK',str(Path(tempfile.gettempdir())/'eapdf-pdfjs-comparison')))
data=json.loads((work/os.environ.get('PDFJS_COMPARISON_RESULTS','measurements.json')).read_text());rows=data['results'];groups={};differences=[];failures=[]
for row in rows:
 if row['status']!='PASS':failures.append({k:row.get(k) for k in ['engine','trial','case','variant','error']});continue
 groups.setdefault((row['engine'],row['case']),[]).append(row)
 if row['result']['rounds'][0]['common']!=row['result']['rounds'][1]['common']:differences.append({'engine':row['engine'],'case':row['case'],'variant':row['variant'],'trial':row['trial'],'mismatch':'cold/warm common'})
 if row['result']['rounds'][0]['custom']!=row['result']['rounds'][1]['custom']:differences.append({'engine':row['engine'],'case':row['case'],'variant':row['variant'],'trial':row['trial'],'mismatch':'cold/warm custom'})
summary=[]
for (engine,case),values in groups.items():
 common=next((r for r in values if r['variant']=='stock'),values[0])['result']['rounds'][0]['common']
 custom=next((r['result']['rounds'][0]['custom'] for r in values if r['variant']=='full'),None)
 for row in values:
  for result in row['result']['rounds']:
   for key,expected in [('common',common),('custom',custom)]:
    if key=='custom' and (row['variant']=='stock' or expected is None):continue
    if result[key]!=expected:differences.append({'engine':engine,'case':case,'variant':row['variant'],'trial':row['trial'],'round':result['round'],'mismatch':key})
 variants={}
 for variant in sorted({r['variant'] for r in values}):
  v=[r for r in values if r['variant']==variant]
  metrics={'openMs':[r['result']['openMs'] for r in v],'totalMs':[r['result']['totalMs'] for r in v],'peakPssKiB':[r['peakBrowserPssKiB'] for r in v],'retainedPssKiB':[r['retainedDocumentPssKiB'] for r in v],'afterDestroyPssKiB':[r['afterDestroyPssKiB'] for r in v]}
  for round,label in [(0,'cold'),(1,'warm')]:
   for field in ['standardMs','inspectionMs','firstRenderMs','totalMs']:metrics[label+field[0].upper()+field[1:]]=[r['result']['rounds'][round][field] for r in v]
  variants[variant]={'successfulTrials':len(v),'metrics':{name:{'median':statistics.median(measures),'min':min(measures),'max':max(measures)} for name,measures in metrics.items()}}
 paired={}
 full={r['trial']:r for r in values if r['variant']=='full'}
 for variant in variants:
  if variant=='full':continue
  samples={}
  for row in [r for r in values if r['variant']==variant and r['trial'] in full]:
   reference=full[row['trial']]
   fields={'openMs':(row['result']['openMs'],reference['result']['openMs']),
           'coldStandardMs':(row['result']['rounds'][0]['standardMs'],reference['result']['rounds'][0]['standardMs'])}
   if variant!='stock':
    fields.update({'totalMs':(row['result']['totalMs'],reference['result']['totalMs']),
                   'peakPssKiB':(row['peakBrowserPssKiB'],reference['peakBrowserPssKiB']),
                   'retainedPssKiB':(row['retainedDocumentPssKiB'],reference['retainedDocumentPssKiB'])})
   for field,(baseline,actual) in fields.items():
    if baseline:samples.setdefault(field,[]).append((baseline-actual)/baseline*100)
  paired[variant]={name:{'count':len(v),'medianBenefitOfFullPercent':statistics.median(v),'min':min(v),'max':max(v)} for name,v in samples.items()}
 summary.append({'engine':engine,'case':case,'variants':variants,'pairedFullBenefits':paired})
axes=data.get('execution',{})
engines=axes.get('engines',sorted({r['engine'] for r in rows}))
cases=axes.get('cases',sorted({r['case'] for r in rows}))
variants=axes.get('variants',sorted({r['variant'] for r in rows}))
trials=range(axes.get('trials',max((r['trial'] for r in rows),default=-1)+1))
expected=set(itertools.product(engines,trials,cases,variants))
keys=[(r['engine'],r['trial'],r['case'],r['variant']) for r in rows]
coverage={'observedTrials':len(rows),'expectedCombinations':len(expected),'missingCombinations':sorted(expected-set(keys)),'duplicateCombinations':len(keys)-len(set(keys))}
paired=any(len({r['variant'] for r in values})>1 for values in groups.values())
correctness='FAIL' if differences else 'UNAVAILABLE' if not groups else 'PASS' if paired else 'SELF_CONSISTENT'
result={'coverage':coverage,'correctness':correctness,'differences':differences,'explicitFailures':failures,'scope':'Standard API outputs compared against stock where successful. Custom outputs compare feature-equivalent variants. Selected pages: first/middle/last, not every page. Per-browser equality; no cross-engine pixel-equality claim.','summaries':summary}
(work/os.environ.get('PDFJS_COMPARISON_SUMMARY','summary.json')).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'correctness':result['correctness'],'differences':differences,'failedTrials':len(failures),'successfulTrials':sum(r['status']=='PASS' for r in rows)},indent=2))
raise SystemExit(bool(differences) or not groups)
