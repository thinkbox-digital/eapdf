// Original edge controls: byte-exact downloads, page anchors, GoToE and damaged declarations.
import {readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
export async function makeAssetFixture(root,directory){
 const child=await readFile(join(root,'tests/fixtures/ordinary.pdf'));
 const payloads={'notes.txt':Buffer.from('Attachment text — 東京\n'),'archive.bin':Buffer.from([0,1,0,255,67]),'two-pages.pdf':child,'broken.bin':Buffer.from('broken checksum'),'over-limit.bin':Buffer.from('small'),'empty.bin':Buffer.alloc(0),'variant-f.bin':Buffer.from('F stream'),'variant-uf.bin':Buffer.from('UF stream')};
 const objects=[],add=value=>(objects.push(Buffer.isBuffer(value)?value:Buffer.from(value)),objects.length),ref=id=>`${id} 0 R`;
 const stream=(bytes,extra='')=>Buffer.concat([Buffer.from(`<< /Length ${bytes.length} ${extra} >>\nstream\n`),bytes,Buffer.from('\nendstream')]);
 const catalog=add(''),pages=add(''),page1=add(''),page2=add(''),font=add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
 const files={};
 for(const [name,key]of [['notes.txt','notes.txt'],['archive.bin','archive.bin'],['two-pages.pdf','two-pages.pdf'],['broken.bin','broken.bin'],['over-limit.bin','over-limit.bin'],['empty.bin','empty.bin']]){
  const bytes=payloads[key],mime=name.endsWith('.pdf')?'application#2Fpdf':'application#2Foctet-stream';
  const checksum=name==='broken.bin'?'00000000000000000000000000000000':createHash('md5').update(bytes).digest('hex');
  const data=add(stream(bytes,`/Type /EmbeddedFile /Subtype /${mime} /Params << /Size ${name==='over-limit.bin'?16*1024**2+1:bytes.length} /CheckSum <${checksum}> >>`));
  files[name]=add(`<< /Type /Filespec /F (${name}) /UF (${name}) /AFRelationship /Data /EF << /F ${ref(data)} >> >>`);
 }
 const f=add(stream(payloads['variant-f.bin'],'/Type /EmbeddedFile /Params << /Size 8 >>'));
 const uf=add(stream(payloads['variant-uf.bin'],'/Type /EmbeddedFile /Params << /Size 9 >>'));
 files['variants.bin']=add(`<< /Type /Filespec /F (variants.bin) /UF (variants.bin) /EF << /F ${ref(f)} /UF ${ref(uf)} >> >>`);
 const note=add(`<< /Type /Annot /Subtype /FileAttachment /Rect [30 100 54 124] /FS ${ref(files['notes.txt'])} >>`);
 const pdf=add(`<< /Type /Annot /Subtype /FileAttachment /Rect [30 140 54 164] /FS ${ref(files['two-pages.pdf'])} >>`);
 const link=(name,y)=>add(`<< /Type /Annot /Subtype /Link /Rect [30 ${y} 210 ${y+20}] /A << /S /GoToE /T << /R /C /N (${name}) >> /D [0 /Fit] >> >>`);
 const textLink=link('notes.txt',80),pdfLink=link('two-pages.pdf',50),missingLink=link('missing.bin',20);
 const content=add(stream(Buffer.from('BT /F1 16 Tf 30 210 Td (Attachment link controls) Tj ET')));
 objects[catalog-1]=Buffer.from(`<< /Type /Catalog /Pages ${ref(pages)} /AF [${Object.values(files).map(ref).join(' ')}] /Names << /EmbeddedFiles << /Names [${Object.entries(files).sort().map(([name,id])=>`(${name}) ${ref(id)}`).join(' ')}] >> >> >>`);
 objects[pages-1]=Buffer.from(`<< /Type /Pages /Kids [${ref(page1)} ${ref(page2)}] /Count 2 >>`);
 const page=annotations=>Buffer.from(`<< /Type /Page /Parent ${ref(pages)} /MediaBox [0 0 320 260] /Resources << /Font << /F1 ${ref(font)} >> >> /Contents ${ref(content)} /Annots [${annotations.map(ref).join(' ')}] >>`);
 objects[page1-1]=page([pdf,textLink,pdfLink,missingLink]);objects[page2-1]=page([note]);
 const chunks=[Buffer.from('%PDF-2.0\n')],offsets=[0];let size=chunks[0].length;
 for(const [index,value]of objects.entries()){offsets.push(size);const bytes=Buffer.concat([Buffer.from(`${index+1} 0 obj\n`),value,Buffer.from('\nendobj\n')]);chunks.push(bytes);size+=bytes.length;}
 chunks.push(Buffer.from(`xref\n0 ${offsets.length}\n0000000000 65535 f \n`+offsets.slice(1).map(offset=>String(offset).padStart(10,'0')+' 00000 n \n').join('')+`trailer\n<< /Size ${offsets.length} /Root ${ref(catalog)} >>\nstartxref\n${size}\n%%EOF\n`));
 const path=join(directory,'attachment-control.pdf');await writeFile(path,Buffer.concat(chunks));return {path,payloads};
}
