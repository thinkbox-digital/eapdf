import assert from 'node:assert/strict';
export async function checkTextFragments(evaluate){
 // Real text-layer regression: adjacent font runs must not acquire a synthetic space.
 const fragmentCheck=await evaluate(async()=>{
  const {pdfjs}=await import('./pdfjs-adapter.js'),{PageText}=await import('./page-text.js'),{pdfSearchText,textMatches}=await import('./document-search.js');
  const commands='BT /F1 12 Tf 40 700 Td (con) Tj /F2 12 Tf (tract) Tj 0 -30 Td /F1 12 Tf (annual) Tj 0 -18 Td (report) Tj 0 -30 Td (con-) Tj 0 -18 Td (tract) Tj ET BT /F1 12 Tf 344 500 Td (Belmonte) Tj ET BT /F2 12 Tf 145 500 Td (mailto:someone@example.org) Tj ET BT /F1 12 Tf 40 450 Td (email) Tj ET';
  const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>',`<< /Length ${commands.length} >>\nstream\n${commands}\nendstream`];
  let pdf='%PDF-1.7\n',offsets=[0];objects.forEach((o,i)=>{offsets.push(pdf.length);pdf+=`${i+1} 0 obj\n${o}\nendobj\n`;});const xref=pdf.length;pdf+='xref\n0 7\n0000000000 65535 f \n'+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('')+`trailer << /Size 7 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  const task=pdfjs.getDocument({data:new TextEncoder().encode(pdf)}),doc=await task.promise,p=await doc.getPage(1),container=document.createElement('div');container.className='textLayer';document.body.append(container);const layer=new PageText(container);
  try{
   const content=await p.getTextContent(),text=pdfSearchText(content.items);
   const streamText=p.streamTextContent.bind(p);
   p.streamTextContent=(...args)=>streamText(...args).pipeThrough(new TransformStream({transform(chunk,controller){for(const item of chunk.items)controller.enqueue({...chunk,items:[item]});}}));
   await layer.render(p,1);
   const counts=['contract','annual report','email','Belmonte mailto'].map(query=>({query,scan:textMatches(text,query).length,highlight:layer.highlight(query),marks:[...container.querySelectorAll('mark')].map(m=>m.textContent)}));
   const raw=content.items.filter(i=>'str'in i).map(i=>i.str+(i.hasEOL?'\n':'')).join('');
   layer.clear();return {counts,oldEmailCount:(raw.toLowerCase().match(/email/g)??[]).length,cleared:container.childNodes.length===0};
  }finally{layer.clear();container.remove();p.cleanup();await task.destroy();}
 });
 assert.deepEqual(fragmentCheck.counts.map(({scan,highlight})=>({scan,highlight})),[{scan:2,highlight:2},{scan:1,highlight:1},{scan:1,highlight:1},{scan:1,highlight:1}]);assert.ok(fragmentCheck.cleared);
 assert.equal(fragmentCheck.oldEmailCount,2,'fixture reproduces the false cross-run word');
 assert.deepEqual(fragmentCheck.counts[2].marks,['email']);
 assert.deepEqual(fragmentCheck.counts[3].marks,['Belmonte','mailto']);
 assert.deepEqual(fragmentCheck.counts[0].marks,['con','tract','con-','tract']);
 return fragmentCheck;
}
