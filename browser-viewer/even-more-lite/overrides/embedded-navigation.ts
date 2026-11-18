import type {BrowserDocument,BrowserSession} from './browser-session.js';
import type {AssociatedFile,EmbeddedStream} from './domain/inspection.js';
import {isPdf} from './attachment-types.js';
export type EmbeddedAction={steps:{relationship:'P'|'C';nameHex?:string;page?:number|string;annotation?:number|string}[];destination:string|unknown[]|null;error:string|null};
export async function destinationPage(doc:BrowserDocument,destination:unknown):Promise<number>{
  if(destination===null)return 1;
  const dest=typeof destination==='string'?await doc.run(doc.pdf.getDestination(destination)):destination;
  if(!Array.isArray(dest)||!dest.length)throw Error('Link destination is unavailable.');
  const index=typeof dest[0]==='number'?dest[0]:await doc.run(doc.pdf.getPageIndex(dest[0]));
  if(!Number.isInteger(index)||index<0||index>=doc.pdf.numPages)throw Error('Link destination is outside this PDF.');
  return index+1;
}
type Target={doc:BrowserDocument;page:number;file?:AssociatedFile;stream?:EmbeddedStream};
export async function resolveEmbeddedLink(session:BrowserSession,source:BrowserDocument,action:EmbeddedAction,check:()=>void):Promise<Target>{
  if(action.error)throw Error(action.error);
  if(!Array.isArray(action.steps)||!action.steps.length||action.steps.length>32)throw Error('Embedded link target is invalid.');
  let doc=source;
  for(const [index,step] of action.steps.entries()){
    check();await session.ensure(doc);check();
    if(step.relationship==='P'){if(!doc.parent)throw Error('This PDF has no open parent document.');doc=doc.parent;continue;}
    if(step.relationship!=='C')throw Error('Embedded link relationship is invalid.');
    let files;
    if(step.nameHex!==undefined)files=doc.inspection.files.filter(file=>file.origins.some(origin=>origin.kind==='name-tree'&&origin.nameTreeKey.raw.some(raw=>raw.bytesHex===step.nameHex)));
    else{
      const page=typeof step.page==='number'?step.page:await destinationPage(doc,step.page)-1;
      if(step.annotation===undefined)throw Error('Attachment annotation is unavailable.');
      const result=await doc.run(doc.pdf.getDocumentAssets({embeddedTarget:{page,annotation:step.annotation}}));check();
      files=doc.inspection.files.filter(file=>file.origins.some(origin=>origin.kind==='annotation'&&origin.locator.path===result.target));
    }
    if(files.length!==1)throw Error('Embedded link file is missing or ambiguous.');
    const file=files[0]!,stream=file.streams.find(stream=>stream.efKeys.includes('/UF'))??file.streams[0];
    if(!stream)throw Error('Embedded link file has no readable content.');
    if(!isPdf(file,stream)){
      if(index!==action.steps.length-1)throw Error('An intermediate link target is not a PDF.');
      return {doc,page:doc.page,file,stream};
    }
    doc=await session.child(doc,file,stream,false);check();
  }
  await session.ensure(doc);check();const page=await destinationPage(doc,action.destination);check();return {doc,page};
}
