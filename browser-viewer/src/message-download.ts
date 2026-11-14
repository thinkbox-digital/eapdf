import type {BrowserDocument,BrowserSession} from './browser-session.js';
import type {MailMessage} from './domain/inspection.js';
import {messagePages} from './navigation.js';
export type MessageDownload={name:string;pages:number[]|null};
/** Copy originals only with complete standalone scope; otherwise export proven body pages. */
export function messageDownload(doc:BrowserDocument,message?:MailMessage,wholeDocument=false):MessageDownload|undefined {
  if(wholeDocument)return {name:doc.name,pages:null};
  if(doc.inspection.collection.value)return;
  message??=doc.inspection.messages.length===1?doc.inspection.messages[0]:undefined;
  if(!message)return;
  if(message.partIds.state!=='present')return;
  const parts=doc.inspection.parts.filter(p=>message.partIds.value?.includes(p.id)&&p.dpm.Mail_ContentSetType?.value==='BodyRendering');
  if(!parts.length||parts.some(p=>p.pageRange.state!=='present'||p.pageRange.value.start<0||p.pageRange.value.end>=doc.pdf.numPages||p.pageRange.value.start>p.pageRange.value.end||p.messageIds.value?.length!==1||p.messageIds.value[0]!==message.id))return;
  const pages=messagePages(doc.inspection,message);if(!pages.length)return;
  const inspection=doc.inspection,profile=inspection.identification.declaredProfile;
  if(profile.state==='present'&&['s','si'].includes(profile.value)&&inspection.messages.length===1&&
    inspection.collection.state==='missing'&&inspection.coverage.collection==='missing'&&
    inspection.coverage.metadata==='present'&&inspection.coverage.documentParts==='present'&&
    inspection.coverage.associatedFiles==='present'&&!inspection.diagnostics.length&&
    inspection.pageCount===doc.pdf.numPages&&pages.length===doc.pdf.numPages&&
    inspection.parts.filter(p=>p.dpm.Mail_ContentSetType?.value==='BodyRendering').every(p=>parts.includes(p)))
    return {name:doc.name,pages:null};
  return {name:(doc.details.get(message.metadataRecordId)?.Subject||'Message')+'.pdf',pages};
}
export async function messagePdf(session:BrowserSession,doc:BrowserDocument,plan:MessageDownload,check:()=>void):Promise<Blob>{
  if(plan.pages===null){const blob=await session.sourceBlob(doc);check();return blob;}
  await session.ensure(doc);check();
  const data=await doc.run(doc.pdf.extractPages([{document:null,includePages:plan.pages.map(page=>page-1)}]));check();
  if(!data?.length)throw new Error('The message pages could not be exported.');
  return new Blob([new Uint8Array(data)],{type:'application/pdf'});
}
