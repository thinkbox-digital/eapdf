"""Independent before-runtime native witness for typed unique parent MessageID binding."""
import collections,hashlib,importlib.util,json
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('before_owner',ROOT/'.eapdf-verification-v59/validate_eapdf.py');v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
rows=[];allrows=json.loads((ROOT/'docs/improvement/validator-59/custom-regressions.json').read_text())
for prior in allrows:
 p=ROOT/prior['file']
 with pikepdf.open(p) as pdf:
  report=v.Report(str(p));xroot,_,_=v.parse_xmp(v.read_metadata_stream(pdf),report)
  attachments=v.flatten_attachments(xroot) if xroot is not None else []
  if not attachments:continue
  records=v.flatten_email_records(xroot);counts=collections.Counter()
  for rec in records:
   mid=v.first_present(rec,'Message-ID','MessageID','messageid','Mail_MessageID')
   if v.is_xmp_text(mid) and v.norm_text(mid):counts[v.norm_text(mid)]+=1
  _,_,nodes=v.get_dpart_nodes(pdf);bodies=v.collect_body_content_sets(pdf,nodes)
  changed=[]
  for index,att in enumerate(attachments):
   attid=v.identity_fields(att);owners=v.attachment_email_owners(att,records)
   if not attid['Mail_GUID'] or len(owners)!=1:continue
   mid=v.first_present(owners[0],'Message-ID','MessageID','messageid','Mail_MessageID')
   if not v.is_xmp_text(mid) or counts[v.norm_text(mid)]!=1:continue
   old=[b for b in bodies if v.identities_match(att,b['identity'])]
   new=[b for b in bodies if v.identities_match(owners[0],b['identity'])]
   if [b['objgen'] for b in old]==[b['objgen'] for b in new]:continue
   annots=v.collect_file_attachment_annotations(pdf);sources=v.summarize_source_emails(v.get_af_specs(pdf));proof=[]
   for b in new:
    checksum=v.attachment_source_checksum(att,b,records,sources)
    matching=[a for a in annots if a['page'] in b['pages'] and v.attachment_record_matches_annotation(att,a,checksum)]
    good=[a for a in matching if a.get('within_page_box') and a.get('visible_ok')]
    proof.append(dict(body_objgen=b['objgen'],identity=b['identity'],pages=b['pages'],visible_matching_annotations=[a['objgen'] for a in good],native_sizes=[a['file']['size'] for a in good]))
   changed.append(dict(attachment=index,attachment_identity=attid,unique_parent_identity=v.identity_fields(owners[0]),old_body_nodes=[b['objgen'] for b in old],new_body_nodes=[b['objgen'] for b in new],proof=proof))
  if changed:rows.append(dict(file=prior['file'],sha256=hashlib.sha256(p.read_bytes()).hexdigest(),before=prior['after'],before_annotation=prior['observed_levels']['ATTACHMENT_ANNOTATIONS_PRESENT'],changes=changed))
(HERE/'prior-census.json').write_text(json.dumps(dict(cases_reviewed=len(allrows),changed_cases=len(rows),cases=rows),indent=2)+'\n')
print('Typed unique parent MessageID changes annotation body membership in',len(rows),'historical files.',flush=True)
