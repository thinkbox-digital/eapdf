// Synthetic malformed archive isolates a recognized message beside unrelated content.
export function downloadScopeFixture(profile='m',{partial=true,standalone=false}={}){
 const xmp=`<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:id="http://www.pdfa.org/eapdf/ns/id/" xmlns:m="http://www.pdfa.org/eapdf/ns/meta/"><id:version>1</id:version><id:conformance>${profile}</id:conformance>${standalone?'':'<m:PrivateNote>ARCHIVE METADATA SENTINEL</m:PrivateNote>'}<m:email><rdf:Bag><rdf:li rdf:parseType="Resource"><m:Mail_GUID>selected</m:Mail_GUID><m:Subject>Selected email</m:Subject></rdf:li></rdf:Bag></m:email></rdf:Description></rdf:RDF></x:xmpmeta>`;
 const stream=(s,extra='')=>`<< /Length ${Buffer.byteLength(s)} ${extra} >>\nstream\n${s}\nendstream`;
 const text=s=>stream(`BT /F1 18 Tf 20 150 Td (${s}) Tj ET`);
 const objects=[
  `<< /Type /Catalog /Pages 2 0 R /Metadata 8 0 R /DPartRoot 9 0 R ${standalone?'':'/AF [12 0 R] /Names << /EmbeddedFiles << /Names [(private.eml) 12 0 R] >> >>'} >>`,
  `<< /Type /Pages /Kids [3 0 R ${standalone?'':'5 0 R'}] /Count ${standalone?1:2} >>`,
  `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 240] /Resources << /Font << /F1 7 0 R >> ${standalone?'':'/Properties << /MailData 15 0 R >>'} >> /Contents 4 0 R /DPart 10 0 R ${standalone?'':'/AF [12 0 R] /Annots [14 0 R] /Metadata 8 0 R /PieceInfo << /Private (PIECE INFO SENTINEL) >>'} >>`,text('SELECTED BODY'),
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 240] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>',text(standalone?'UNUSED FIXTURE OBJECT':'UNRELATED PRIVATE MESSAGE'),
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',stream(xmp,'/Type /Metadata /Subtype /XML'),
  '<< /Type /DPartRoot /DPartRootNode 11 0 R >>',
  '<< /Type /DPart /Parent 11 0 R /Start 3 0 R /End 3 0 R /DPM << /Mail_GUID (selected) /Mail_ContentSetType /BodyRendering >> >>',
  `<< /Type /DPart /Parent 9 0 R /DParts [10 0 R ${partial?'10 0 R 17 0 R':''}] >>`,
 ];
 if(!standalone)objects.push('<< /Type /Filespec /F (private.eml) /EF << /F 13 0 R >> >>',stream('PRIVATE SOURCE SENTINEL','/Type /EmbeddedFile'),'<< /Type /Annot /Subtype /FileAttachment /Rect [0 0 20 20] /FS 12 0 R /Contents (PRIVATE ANNOTATION SENTINEL) >>','<< /AF [12 0 R] /Metadata 8 0 R >>','<< /Title (PRIVATE TITLE SENTINEL) >>','<< /Type /DPart /Parent 11 0 R /Start 5 0 R /End 5 0 R /DPM << /Mail_GUID (private) /Mail_ContentSetType /BodyRendering >> >>');
 let data='%PDF-2.0\n',offsets=[0];for(const [i,o]of objects.entries()){offsets.push(Buffer.byteLength(data));data+=`${i+1} 0 obj\n${o}\nendobj\n`;}
 const start=Buffer.byteLength(data);data+=`xref\n0 ${offsets.length}\n0000000000 65535 f \n`+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('')+`trailer\n<< /Size ${offsets.length} /Root 1 0 R ${standalone?'':'/Info 16 0 R'} >>\nstartxref\n${start}\n%%EOF\n`;
 return Buffer.from(data);
}
