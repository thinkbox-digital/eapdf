"""Independently inspect preserved parent/child data in native fixture edits."""
import hashlib,io,json,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'scripts'));from review_eapdf_operand_corpus import inspect
folder=ROOT/'tests/fixtures/eapdf/validator-collection-child-status'
sha=lambda data:hashlib.sha256(data).hexdigest()
def members(pdf):
 names=pdf.Root.Names.EmbeddedFiles.Names
 return {str(names[i]):names[i+1] for i in range(0,len(names),2)}
def page_bytes(page):
 streams=page.obj.get('/Contents',[])
 return [stream.read_bytes() for stream in streams] if isinstance(streams,pikepdf.Array) else [streams.read_bytes()]
def instructions(page):
 rows=[]
 for instruction in pikepdf.parse_content_stream(page):
  args=list(instruction.operands)
  if str(instruction.operator)=='BDC':args=args[1:]
  rows.append((str(instruction.operator),[value.unparse().decode('latin1') if isinstance(value,pikepdf.Object) else repr(value) for value in args]))
 return rows
checks=[]
for case in json.loads((folder/'expectations.json').read_text())['cases']:
 with pikepdf.open(ROOT/case['parent']) as before,pikepdf.open(folder/case['file']) as after:
  assert before.Root.Metadata.read_bytes()==after.Root.Metadata.read_bytes()
  assert [page_bytes(p) for p in before.pages]==[page_bytes(p) for p in after.pages]
  old=members(before);new=members(after);assert set(old)==set(new)
  original_sources=[];corrected=[]
  for name,spec in new.items():
   data=spec.EF.F.read_bytes()
   if spec.get('/AFRelationship')==pikepdf.Name.Source:
    assert data==old[name].EF.F.read_bytes();original_sources.append(dict(filename=name,sha256=sha(data)))
   if spec.get('/CI') is None:continue
   assert int(spec.CI.Size)==len(data)
   assert {str(k):str(v) for k,v in spec.CI.items() if str(k)!='/Size'}=={str(k):str(v) for k,v in old[name].CI.items() if str(k)!='/Size'}
   try:
    with pikepdf.open(io.BytesIO(old[name].EF.F.read_bytes())) as child_before,pikepdf.open(io.BytesIO(data)) as child_after:
     if child_before.Root.Metadata.read_bytes()!=child_after.Root.Metadata.read_bytes():continue
     assert not inspect(child_after)
     assert [instructions(p) for p in child_before.pages]==[instructions(p) for p in child_after.pages]
     old_files=members(child_before);new_files=members(child_after);assert set(old_files)==set(new_files)
     for child_name,item in old_files.items():assert item.EF.F.read_bytes()==new_files[child_name].EF.F.read_bytes()
     corrected.append(dict(filename=name,metadata_sha256=sha(child_after.Root.Metadata.read_bytes()),retained_assets=len(old_files)))
   except pikepdf.PdfError:
    assert case['file'].endswith('-corrupt.pdf')
  assert corrected
  checks.append(dict(file=case['file'],parent_metadata_sha256=sha(after.Root.Metadata.read_bytes()),parent_page_contents_unchanged=True,
                     original_sources=original_sources,corrected_children_with_metadata_assets_and_non_tag_operands_preserved=corrected))
(HERE/'native-preservation-review.json').write_text(json.dumps(checks,indent=2)+'\n')
print('Ten parent metadata/page inventories and all corrected child metadata/assets/non-tag operands preserved.')
