#!/usr/bin/env python3
"""Hash real archives and create isolated scaling controls; writes only in /tmp."""
from pathlib import Path
import os, tempfile, subprocess
import json,hashlib,struct,zlib,pikepdf
root=Path(__file__).resolve().parents[2];work=Path(os.environ.get('PDFJS_COMPARISON_WORK',str(Path(tempfile.gettempdir())/'eapdf-pdfjs-comparison'))).resolve();assert work.is_relative_to(Path(tempfile.gettempdir()));fixtures=work/'fixtures';fixtures.mkdir(exist_ok=True)
base=root/'browser-viewer/tests/fixtures/ordinary.pdf'
for mode in ['dense-classic','dense-compressed']:
 with pikepdf.open(base) as pdf:
  pdf.Root.Benchmark=pikepdf.Array(pdf.make_indirect(pikepdf.Dictionary(Ordinal=i,Value='item-'+str(i))) for i in range(150000))
  pdf.save(fixtures/(mode+'.pdf'),object_stream_mode=pikepdf.ObjectStreamMode.generate if mode.endswith('compressed') else pikepdf.ObjectStreamMode.disable)
with pikepdf.Pdf.new() as pdf:
 pages=[pdf.add_blank_page(page_size=(320,240)) for i in range(2500)]
 tree=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPartRoot))
 node=pdf.make_indirect(pikepdf.Dictionary(Parent=tree));tree.DPartRootNode=node;pdf.Root.DPartRoot=tree
 node.DParts=pikepdf.Array(pdf.make_indirect(pikepdf.Dictionary(Parent=node,Start=page.obj,DPM=pikepdf.Dictionary(Mail_GUID='message-'+str(i)))) for i,page in enumerate(pages))
 pdf.save(fixtures/'flat-pages.pdf',object_stream_mode=pikepdf.ObjectStreamMode.generate)
# Sparse high object IDs test storage independently of file length.
b=bytearray(b'%PDF-2.0\n');offsets={}
def obj(n,v):offsets[n]=len(b);b.extend(f'{n} 0 obj\n'.encode()+v+b'\nendobj\n')
obj(1,b'<< /Type /Catalog /Pages 2 0 R >>');obj(2,b'<< /Type /Pages /Count 1 /Kids [10000000 0 R] >>');obj(10000000,b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 240] >>');offsets[20000000]=len(b)
data=b''.join(bytes([0 if n==0 else 1])+struct.pack('>QH',offsets.get(n,0),65535 if n==0 else 0) for n in [0,1,2,10000000,20000000]);compressed=zlib.compress(data)
obj(20000000,b'<< /Type /XRef /Size 20000001 /Root 1 0 R /W [1 8 2] /Index [0 3 10000000 1 20000000 1] /Filter /FlateDecode /Length '+str(len(compressed)).encode()+b' >>\nstream\n'+compressed+b'\nendstream');b.extend(b'startxref\n'+str(offsets[20000000]).encode()+b'\n%%EOF\n');(fixtures/'sparse.pdf').write_bytes(b)
# A compact file whose valid dense xref decodes beyond the ordinary 64-MiB cap.
# This isolates xref layout allowances and storage; it is not a real-mail capacity claim.
b=bytearray(b'%PDF-2.0\n');offsets={}
obj(1,b'<< /Type /Catalog /Pages 2 0 R >>');obj(2,b'<< /Type /Pages /Count 1 /Kids [3 0 R] >>');obj(3,b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 240] >>');offsets[4]=len(b)
count=7000000
rows=bytearray(count*11)
rows[9:11]=b'\xff\xff'
for n in [1,2,3,4]:rows[n*11:(n+1)*11]=b'\x01'+struct.pack('>QH',offsets[n],0)
compressed=zlib.compress(rows)
obj(4,b'<< /Type /XRef /Size '+str(count).encode()+b' /Root 1 0 R /W [1 8 2] /Filter /FlateDecode /Length '+str(len(compressed)).encode()+b' >>\nstream\n'+compressed+b'\nendstream');b.extend(b'startxref\n'+str(offsets[4]).encode()+b'\n%%EOF\n');(fixtures/'wide-xref.pdf').write_bytes(b)
# This diagnostic isolates repeated private Flate decoding under the same budget.
with pikepdf.open(base) as pdf:
 payload=bytes([71])*(16*1024**2)
 stream=pdf.make_stream(zlib.compress(payload));stream.Filter=pikepdf.Name.FlateDecode
 stream.Type=pikepdf.Name.EmbeddedFile;stream.Subtype=pikepdf.Name('/application/octet-stream');stream.Params=pikepdf.Dictionary(Size=len(payload))
 spec=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,F='payload.bin',UF='payload.bin',EF=pikepdf.Dictionary(F=stream,UF=stream),AFRelationship=pikepdf.Name.Data))
 pdf.Root.AF=pikepdf.Array([spec]);pdf.Root.Names=pikepdf.Dictionary(EmbeddedFiles=pikepdf.Dictionary(Names=pikepdf.Array(['payload.bin',spec])))
 pdf.save(fixtures/'compressed-payload.pdf')
cases=[('ordinary',base,'synthetic-control'),('real-archive-m',root/'browser-viewer/tests/attachments/source/pst_sample_1m.pdf','real-archive'),('real-archive-c',root/'browser-viewer/tests/attachments/source/pst_sample_1c.pdf','real-archive')]+[(n,fixtures/(n+'.pdf'),'synthetic-isolation') for n in ['dense-classic','dense-compressed','flat-pages','sparse','wide-xref','compressed-payload']]
manifest=[]
for name,path,kind in cases:
 if name=='wide-xref':pages=1
 elif name=='sparse':
  info=subprocess.check_output(['pdfinfo',str(path)],text=True);assert 'Pages:           1' in info;pages=1
 else:
  with pikepdf.open(path) as pdf:pages=len(pdf.pages)
 manifest.append({'name':name,'path':str(path),'kind':kind,'diagnosticOnly':name=='compressed-payload','bytes':path.stat().st_size,'pages':pages,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
(work/'corpus.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps([{k:v for k,v in c.items() if k!='path'} for c in manifest],indent=2))
protocol = {'trials': 5,
 'order': 'alternate forward/reverse variant order per trial; each case/variant uses a fresh '
          'browser process and one document worker',
 'workloads': ['cold standard API: open, metadata, lazy attachment listing, first/middle/last '
               'render and text; standard attachment bytes are a separate supplement',
               'cold custom API: complete assets/metadata/parts plus exact embedded bytes',
               'warm: repeat in the same document after PDF.js cleanup'],
 'memory': 'Browser-process-tree PSS sampled every 100ms, including workers/native/browser '
           'overhead; sampled maxima are not exact peaks. Retained memory is sampled while the '
           'document remains open and after destruction.',
 'budgets': {'timeoutSeconds': 90,
             'chromiumV8OldSpaceMiB': 512,
             'patchedDecodedBufferMiB': 64,
             'patchedAggregateDecodeMiB': 256,
             'workerCount': 1,
             'externalBrowserPssMiB': 1024,
             'firefox': 'same browser defaults across variants; no equivalent portable per-worker '
                        'V8 heap flag'},
 'acceptance': {'correctness': 'Exact per-browser equality for main text/pixels/metadata and '
                               'normalized custom inspection/embedded bytes; a separate supplement '
                               'compares named standard-API attachment bytes. Explicit failures '
                               'remain recorded.',
                'keepCandidate': 'at least 15% median latency or sampled PSS reduction on '
                                 'representative workloads, or avoids an explicit failure with '
                                 'identical output; check paired variability',
                'regressionReview': 'over 10% median latency/retained-PSS increase on small '
                                    'controls; no statistical significance or capacity guarantee '
                                    'from five trials',
                'decision': 'keep/simplify/remove recommendations only; production changes require '
                            'separate review'},
 'controlCaveat': 'Pre-0003 XRef plus later zero-width correction/resource-error typing/cleanup '
                  'API; metadata APIs and asset traversal remain shared for equivalence. Four '
                  'ablations isolate compact entries, parsed-cache/weak identity, decoder '
                  'reclamation and page-tree scans.'}
(work/'protocol.json').write_text(json.dumps(protocol,indent=2)+'\n')
