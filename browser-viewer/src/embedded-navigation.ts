import type {BrowserDocument,BrowserSession} from './browser-session.js';
export type EmbeddedAction={steps:{relationship:'P'|'C';nameHex?:string;page?:number|string;annotation?:number|string}[];destination:string|unknown[]|null;error:string|null};
export async function destinationPage(doc:BrowserDocument,destination:unknown):Promise<number>{
  const dest=typeof destination==='string'?await doc.run(doc.pdf.getDestination(destination)):destination;
  if(!Array.isArray(dest)||!dest.length)throw new Error('Link destination is unavailable.');
  const index=typeof dest[0]==='number'?dest[0]:await doc.run(doc.pdf.getPageIndex(dest[0]));
  if(!Number.isInteger(index)||index<0||index>=doc.pdf.numPages)throw new Error('Link destination is outside this PDF.');
  return index+1;
}
export async function resolveEmbeddedLink(session:BrowserSession,source:BrowserDocument,action:EmbeddedAction,check:()=>void):Promise<{doc:BrowserDocument;page:number}>{
  if(action.error)throw new Error(action.error);
  if(!Array.isArray(action.steps)||!action.steps.length||action.steps.length>32)throw new Error('Embedded link target is invalid.');
  let doc=source;
  for(const step of action.steps){
    check();await session.ensure(doc);check();
    if(step.relationship==='P'){if(!doc.parent)throw new Error('This PDF has no open parent document.');doc=doc.parent;continue;}
    if(step.relationship!=='C')throw new Error('Embedded link relationship is invalid.');
    let files;
    if(step.nameHex!==undefined)files=doc.inspection.files.filter(f=>f.origins.some(o=>o.kind==='name-tree'&&o.nameTreeKey.raw.some(raw=>raw.bytesHex===step.nameHex)));
    else{
      const page=typeof step.page==='number'?step.page:await destinationPage(doc,step.page)-1;
      if(step.annotation===undefined)throw new Error('Attachment annotation is unavailable.');
      const result=await doc.run(doc.pdf.getDocumentAssets({embeddedTarget:{page,annotation:step.annotation}}));check();
      files=doc.inspection.files.filter(f=>f.origins.some(o=>o.kind==='annotation'&&o.locator.path===result.target));
    }
    if(files.length!==1)throw new Error('Embedded link file is missing or ambiguous.');
    const file=files[0]!,stream=file.streams.find(s=>s.efKeys.includes('/UF'))??file.streams[0];
    if(!stream)throw new Error('Embedded link file has no readable content.');
    doc=await session.child(doc,file,stream,false);check();
  }
  await session.ensure(doc);check();const page=await destinationPage(doc,action.destination);check();return {doc,page};
}
