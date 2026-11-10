// Tiny isolated controls; real Portfolio acceptance uses in/portfolio separately.
const literal=s=>'('+s.replaceAll('\\','\\\\').replaceAll('(','\\(').replaceAll(')','\\)')+')';
export function portfolioFixture({collection=true,view='D',initial,files=[['zeta.txt','zeta payload',10],['alpha.txt','alpha payload',2]],extra='',outside=false,links=[],attachments=[],pages=1,folderThumbnail=false,openAction=''}={}){
 const objects=['','','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 240] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'];
 const stream='BT /F1 15 Tf 20 200 Td (Portfolio fixture cover) Tj ET';objects.push(`<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`,'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
 const names=[],fileRefs=[];let outsideRef;
 for(const [i,[name,content,score]] of files.entries()){
  const ref=objects.length+1;fileRefs.push(ref);const text=Buffer.isBuffer(content)?content.toString('latin1'):content;
  objects.push(`<< /Type /Filespec /F ${literal(name)} /UF ${literal(name)} /CI << /Score ${score} /Topic << /D (topic ${i}) /P (Re: ) >> >> /EF << /F ${ref+1} 0 R >> >>`);
  objects.push(`<< /Type /EmbeddedFile /Subtype /text#2Fplain /Params << /Size ${Buffer.byteLength(text,'latin1')} >> /Length ${Buffer.byteLength(text,'latin1')} >>\nstream\n${text}\nendstream`);
  if(outside&&i===files.length-1)outsideRef=ref;else names.push(`${literal(name)} ${ref} 0 R`);
 }
 const pageRefs=[3];for(let i=1;i<pages;i++){pageRefs.push(objects.length+1);objects.push(objects[2]);}
 const annotationRefs=[];
 for(const a of attachments){annotationRefs.push(objects.length+1);objects.push(`<< /Type /Annot /Subtype /FileAttachment /Rect [20 20 40 40] /NM ${literal(a.name)} /FS ${fileRefs[a.fileIndex]} 0 R >>`);}
 for(const [i,link] of links.entries()){annotationRefs.push(objects.length+1);objects.push(`<< /Type /Annot /Subtype /Link /Rect [20 ${150-i*30} 280 ${175-i*30}] /Border [0 0 1] /A << /S /GoToE /D ${link.dest??'(chapter)'} /T ${link.target} ${link.extra??''} >> >>`);}
 if(annotationRefs.length)objects[2]=objects[2].replace('/Contents 4 0 R',`/Contents 4 0 R /Annots [${annotationRefs.map(r=>r+' 0 R').join(' ')}]`);
 let outline='';if(links.length){const ref=objects.length+1;objects.push(`<< /Type /Outlines /First ${ref+1} 0 R /Last ${ref+1} 0 R /Count 1 >>`,`<< /Title (Embedded chapter) /Parent ${ref} 0 R /A << /S /GoToE /D ${links[0].dest??'(chapter)'} /T ${links[0].target} >> >>`);outline=`/Outlines ${ref} 0 R`;}
 if(folderThumbnail){const ref=objects.length+1;objects.push(`<< /ID 0 /Name (Root) /Child ${ref+1} 0 R >>`,`<< /ID 1 /Name (Illustrated folder) /Parent ${ref} 0 R /Thumb ${ref+2} 0 R >>`,`<< /Type /XObject /Subtype /Image /Width 2 /Height 1 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Length 6 >>\nstream\n\xff\x00\x00\x00\xff\x00\nendstream`);extra+=` /Folders ${ref} 0 R`;}
 const spec=collection?`/Collection << /Schema << /Name << /N (File name) /Subtype /F /O 0 >> /Score << /N (Score) /Subtype /N /O 1 >> /Topic << /N (Topic) /Subtype /S /O 2 >> >> /Sort << /S /Score /A false >> /View /${view} ${initial===undefined?'':`/D ${literal(initial)}`} ${extra} >>`:'';
 objects[0]=`<< /Type /Catalog /Pages 2 0 R ${openAction.replaceAll('{lastPage}',pageRefs.at(-1)+' 0 R')} ${spec} ${outline} /Names << /Dests << /Names [(chapter) [${pageRefs.at(-1)} 0 R /Fit]] >> /EmbeddedFiles << /Names [${names.join(' ')}] >> >> ${outsideRef?`/AF [${outsideRef} 0 R]`:''} >>`;objects[1]=`<< /Type /Pages /Kids [${pageRefs.map(r=>r+' 0 R').join(' ')}] /Count ${pageRefs.length} >>`;
 let text='%PDF-1.7\n',offsets=[0];for(let i=0;i<objects.length;i++){offsets.push(Buffer.byteLength(text,'latin1'));text+=`${i+1} 0 obj\n${objects[i]}\nendobj\n`;}
 const xref=Buffer.byteLength(text,'latin1');text+=`xref\n0 ${objects.length+1}\n0000000000 65535 f \n`+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('')+`trailer\n<< /Size ${objects.length+1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;return Buffer.from(text,'latin1');
}
