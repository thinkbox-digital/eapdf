"""Collect newly reached findings and independently inspect native DPart ranges."""
import hashlib,json,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v38'
sys.path.insert(0,str(SNAPSHOT));import validate_eapdf as v
prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-37/custom-regressions.json').read_text())};rows=[]
for name,old in prior.items():
 path=SNAPSHOT/name;assert hashlib.sha256(path.read_bytes()).hexdigest()==old['sha256']
 report=v.validate(str(path));failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'})
 if failures!=old['after']['failures']:
  with pikepdf.open(path) as pdf:
   page_numbers={p.obj.objgen:i for i,p in enumerate(pdf.pages,1)};ranges=[]
   def visit(node):
    if '/DParts' in node:
     for group in node.DParts:
      for child in group:visit(child)
    else:ranges.append([page_numbers[node.Start.objgen],page_numbers[node.get('/End',node.Start).objgen]])
   visit(pdf.Root.DPartRoot.DPartRootNode)
   overlapping=[i for i in range(1,len(pdf.pages)+1) if sum(a<=i<=b for a,b in ranges)>1]
   out_of_order=[i for i,(a,b) in enumerate(zip(ranges,ranges[1:]),1) if b[0]<=a[1]]
  assert set(failures)-set(old['after']['failures'])=={'PAGE_DPART_PRESENT'}
  assert overlapping or out_of_order
  rows.append(dict(file=name,sha256=old['sha256'],before=old['after'],after=dict(status=report.summary()['status'],failures=failures),independent_native_ranges=ranges,overlapping_pages=overlapping,out_of_order_range_indices=out_of_order))
  print(rows[-1],flush=True)
 if (list(prior).index(name)+1)%50==0:print('Inspected',list(prior).index(name)+1,flush=True)
(HERE/'prior-range-review.json').write_text(json.dumps(dict(authority='ISO32000-2:2020 14.12.2/3; native object inspection independent of validator range helpers',rows=rows),indent=2)+'\n')
print('Reviewed',len(rows),'newly reached prior findings.',flush=True)
