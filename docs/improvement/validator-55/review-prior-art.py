"""Independent old-runtime content observations; no new Art checker is called."""
import hashlib,importlib.util,json,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('old_art',ROOT/'.eapdf-verification-v54/validate_eapdf.py');v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
assert hashlib.sha256(Path(v.__file__).read_bytes()).hexdigest()==hashlib.sha256((HERE/'before-validate_eapdf.py').read_bytes()).hexdigest()
CODE='MAIL_CONTENTSET_ART_ONLY';rows=[]
prior=json.loads((ROOT/'docs/improvement/validator-54/custom-regressions.json').read_text())
for original in prior:
 path=ROOT/original['file']; assert hashlib.sha256(path.read_bytes()).hexdigest()==original['sha256']
 with pikepdf.open(path) as pdf:
  _,_,nodes=v.get_dpart_nodes(pdf);leaves=[n for n in nodes if not n['child_count']]
  page_map=v.page_objgen_map(pdf);ranges=[v.page_range_from_dpart(n,page_map) if isinstance(n["node"],pikepdf.Dictionary) else None for n in leaves]
  elements=[]; st=pdf.Root.get('/StructTreeRoot')
  if st is not None:
   k=st.get('/K');k=k[0] if isinstance(k,pikepdf.Array) and len(k)==1 else k
   if isinstance(k,pikepdf.Dictionary):v.walk_struct_elems(k,elements,class_map=st.get('/ClassMap'))
  arts=[e for e in elements if e['type'] in ('Art','Mail_ContentSet')]
  rolemap=v.normalize_role_map(st.get('/RoleMap')) if st else {}
  aliases=[e for e in elements if e['type'] not in ('Art','Mail_ContentSet') and rolemap.get(e['type'])=='Art']
  refs=v.structure_content_dpm_assessments(pdf,arts,nodes) if arts else []
  observations=[];global_unknown=bool(arts) and (not ranges or any(b is None for b in ranges) or 'FAIL' in original['observed_levels'].get('STRUCTURE_CORRESPONDENCE',[]))
  identities=[e['objgen'] for e in arts];aliased=len(set(identities))!=len(identities)
  elem_by={e['objgen']:e for e in arts}
  for ref in refs:
   elem=elem_by[ref['objgen']]; namespace=elem['elem'].get('/NS');uri=None
   if namespace is not None:
    try:uri=str(namespace.get('/NS')) if isinstance(namespace.get('/NS'),pikepdf.String) else ''
    except (UnicodeError,ValueError,pikepdf.PdfError):uri=''
   supported=namespace is None or uri==('https://pdfa.org/ns/ea-pdf/mail-1' if elem['type']=='Mail_ContentSet' else 'http://iso.org/pdf/ssn')
   connected=[];unknown=bool(ref['unavailable']) or not ref['references'] or aliased or not supported
   for connection in ref['references'] if supported else []:
    choices=set();verified=bool(connection['pages'])
    for page in connection['pages']:
     candidates=[i for i,b in enumerate(ranges,1) if b is not None and b[0]<=page<=b[1]]
     links=[i for i,n in enumerate(leaves,1) if v.pdf_object_identity(n['node'])==v.pdf_object_identity(pdf.pages[page-1].obj.get('/DPart'))]
     if len(candidates)!=1 or links!=candidates:verified=False
     choices.update(candidates)
    if verified and len(choices)==1:connected.extend(choices)
    else:unknown=True
   known=sorted(set(connected));level='WARN' if global_unknown else 'FAIL' if len(known)>1 else 'WARN' if unknown or len(known)!=1 else 'PASS'
   observations.append(dict(objgen=ref['objgen'],type=elem['type'],namespace=uri,pages=[r['pages'] for r in ref['references']],known_counterparts=known,unavailable=unknown,expected=level))
  level='N/A' if not arts and not aliases else 'WARN' if global_unknown else 'FAIL' if any(r['expected']=='FAIL' for r in observations) else 'WARN' if aliases or any(r['expected']=='WARN' for r in observations) else 'PASS'
  failures=sorted((set(original['after']['failures'])-{CODE})|({CODE} if level=='FAIL' else set()))
  status='FAIL' if failures else 'INCOMPLETE' if level=='WARN' or 'WARN' in original['observed_levels'].get('VALIDATION_INCOMPLETE',[]) else 'PASS'
  rows.append(dict(file=original['file'],sha256=original['sha256'],before=original['after'],after=dict(status=status,failures=failures),before_art=original['observed_levels'][CODE],expected_art=level,global_unavailable=global_unknown,articles=observations,unresolved_aliases=[e['objgen'] for e in aliases]))
 if len(rows)%100==0:print('Reviewed',len(rows),'native Art observations',flush=True)
summary=dict(cases=len(rows),changed_outcomes=sum(r['before']!=r['after'] for r in rows),changed_art_levels=sum(r['before_art']!=[r['expected_art']] for r in rows),levels={x:sum(r['expected_art']==x for r in rows) for x in ['PASS','FAIL','WARN','N/A']})
(HERE/'prior-art-review.json').write_text(json.dumps(dict(authority='EA9.7 pp29,77; independent old-runtime native connection receipts; no new Art checker called',summary=summary,cases=rows),indent=2)+'\n');print(summary,flush=True)
