"""Development-only attachment controls and independent real-file expectations."""
from pathlib import Path
from io import BytesIO
import hashlib,json,struct,zlib
import pikepdf
from PIL import Image,ImageOps
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/attachments'
OUT.mkdir(exist_ok=True)
def sha(b): return hashlib.sha256(b).hexdigest()
def image_bytes(fmt):
 out=BytesIO();Image.new('RGB',(32,24),(20,100,210)).save(out,format=fmt);return out.getvalue()
ordinary=(ROOT/'tests/fixtures/ordinary.pdf').read_bytes()
# Original synthetic content; no real email metadata is copied here.
files={
 'two-pages.pdf':(ordinary,'application/pdf'),
 'blue.png':(image_bytes('PNG'),'image/png'),
 'blue.gif':(image_bytes('GIF'),'image/gif'),
 'blue.jpg':(image_bytes('JPEG'),'image/jpeg'),
 'plain.txt':('Attachment text: café\nSecond line.\n'.encode(),'text/plain; charset=utf-8'),
 'markup.html':(b'<script>globalThis.attachmentExecuted=true</script><img src="https://example.invalid/tracker"><h1>Untrusted attachment</h1>','text/html'),
 'vector.svg':(b'<svg xmlns="http://www.w3.org/2000/svg" onload="globalThis.attachmentExecuted=true"><image href="https://example.invalid/tracker"/></svg>','image/svg+xml'),
 'empty.pdf':(b'','application/pdf'),
 'broken.pdf':(b'%PDF-1.7\nbroken','application/pdf'),
 'binary.exe':(b'MZ\x00\x01\x00do not execute','application/octet-stream'),
 'huge.png':(b'\x89PNG\r\n\x1a\n'+b'\x00\x00\x00\rIHDR'+struct.pack('>II',100000,100000),'image/png'),
 'large.txt':(b'X'*(2*1024*1024+100),'text/plain'),
}
with pikepdf.open(BytesIO(ordinary)) as doc:
 pairs=[]
 for name,(data,mime) in files.items():
  stream=doc.make_stream(data);stream.Type=pikepdf.Name('/EmbeddedFile');stream.Subtype=pikepdf.Name('/'+mime)
  stream.Params=pikepdf.Dictionary(Size=len(data))
  spec=doc.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name('/Filespec'),F=name,UF=name,EF=pikepdf.Dictionary(F=stream)))
  pairs.extend([name,spec])
 doc.Root.Names=pikepdf.Dictionary(EmbeddedFiles=pikepdf.Dictionary(Names=pairs))
 doc.save(OUT/'viewing.pdf',deterministic_id=True)
entries=[]
for source,names in [('tests/attachments/viewing.pdf',list(files)),('tests/attachments/source/msg_sample_1s.pdf',['SampleAttachment.pdf']),('tests/attachments/source/pst_sample_1m.pdf',['Color 72dpi.jpg']),('tests/inspection/source-examples/eml/eml_sample_1m.pdf',['attachment.txt'])]:
 path=ROOT/source
 with pikepdf.open(path) as pdf:
  found={}
  for obj in pdf.objects:
   if isinstance(obj,pikepdf.Dictionary) and obj.get('/Type')==pikepdf.Name('/Filespec'):
    name=str(obj.get('/UF',obj.get('/F','')))
    if name not in names:continue
    ef=obj.get('/EF',{});stream=ef.get('/UF',ef.get('/F'))
    data=stream.read_bytes();item={'name':name,'sha256':sha(data),'size':len(data)}
    if name.lower().endswith(('.png','.jpg','.gif')) and name!='huge.png':
     image=ImageOps.exif_transpose(Image.open(BytesIO(data))).convert('RGB');item.update(width=image.width,height=image.height,pixel=list(image.getpixel((0,0))))
    if name in ['two-pages.pdf','SampleAttachment.pdf']:
     with pikepdf.open(BytesIO(data)) as child:
      item['pages']=len(child.pages)
      item['pageWidths']=[]
      for page in child.pages:
       box=page.obj.get('/CropBox',page.obj['/MediaBox']);axis=1 if int(page.obj.get('/Rotate',0))%180 else 0
       item['pageWidths'].append(float(box[axis+2]-box[axis])*float(page.obj.get('/UserUnit',1)))
    found[name]=item
  assert set(found)==set(names),(source,set(names)-set(found))
 entries.append({'sourcePath':source,'sha256':sha(path.read_bytes()),'attachments':[found[n] for n in names]})
(OUT/'manifest.json').write_text(json.dumps({'license':'Synthetic viewing.pdf: Apache-2.0 original content; real samples referenced in place.','reference':{'pikepdf':pikepdf.__version__,'Pillow':Image.__version__},'cases':entries},indent=2)+'\n')
print('Wrote original attachment controls and independently hashed real attachment expectations.')
