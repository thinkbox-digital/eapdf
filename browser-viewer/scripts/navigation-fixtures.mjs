// Original synthetic PDFs for size/recursion controls; not a runtime PDF parser.
import {readFile,writeFile,mkdtemp} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
function pdf(payload,name,padding=0,rotation=0){
 const content=Buffer.from('0 g BT /F1 18 Tf 40 150 Td (Navigation test document) Tj ET\n');
 const stream=(bytes,extra='')=>Buffer.concat([Buffer.from(`<< /Length ${bytes.length} ${extra} >>\nstream\n`),bytes,Buffer.from('\nendstream')]);
 const objects=[Buffer.from('<< /Type /Catalog /Pages 2 0 R >>'),Buffer.from('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'),
  Buffer.from(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 260] /Rotate ${rotation} /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R ${payload?'/Annots [8 0 R]':''} >>`),stream(content),Buffer.from('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')];
 if(payload){objects.push(stream(payload,'/Type /EmbeddedFile /Subtype /application#2Foctet-stream /Params << /Size '+payload.length+' >>'),Buffer.from(`<< /Type /Filespec /F (${name}) /UF (${name}) /EF << /F 6 0 R >> >>`),Buffer.from('<< /Type /Annot /Subtype /FileAttachment /Rect [0 0 20 20] /FS 7 0 R >>'));}
 if(padding)objects.push(stream(Buffer.alloc(padding,88)));
 const chunks=[Buffer.from('%PDF-1.7\n')],offsets=[0];let size=chunks[0].length;
 objects.forEach((object,i)=>{offsets.push(size);const b=Buffer.concat([Buffer.from(`${i+1} 0 obj\n`),object,Buffer.from('\nendobj\n')]);chunks.push(b);size+=b.length;});
 chunks.push(Buffer.from(`xref\n0 ${offsets.length}\n0000000000 65535 f \n`+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('')+`trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${size}\n%%EOF\n`));return Buffer.concat(chunks);
}
export async function makeNavigationFixtures(){
 const directory=await mkdtemp(join(tmpdir(),'eapdf-navigation-cases-'));
 for(const rotation of [90,180,270])await writeFile(join(directory,`rotated-${rotation}.pdf`),pdf(null,'',0,rotation));
 await writeFile(join(directory,'large.pdf'),pdf(null,'',40*1024*1024));
 let nested=await readFile('tests/fixtures/ordinary.pdf');for(let i=7;i>=1;i--)nested=pdf(nested,`level-${i}.pdf`);
 await writeFile(join(directory,'nested.pdf'),nested);
 await writeFile(join(directory,'large-embedded.pdf'),pdf(pdf(null,'',20*1024*1024),'large-child.pdf'));
 return directory;
}
