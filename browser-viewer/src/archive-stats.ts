import type {BrowserDocument} from './browser-session.js';
const descriptions=new WeakMap<BrowserDocument,Promise<string>>();
export async function archiveStats(doc:BrowserDocument):Promise<string|undefined> {
  const profile=doc.inspection.identification.declaredProfile.value;
  if(!profile||!['c','ci','m','mi'].includes(profile))return;
  let description=descriptions.get(doc);
  if(!description){description=doc.run(doc.pdf.getMetadata()).then(data=>String(data.metadata?.get('dc:description')??'')).catch(()=>'');descriptions.set(doc,description);}
  const summary=await description;
  const declared=(name:string):number|undefined=>{
    if(!summary.startsWith('EA-PDF email archive;'))return;
    const match=summary.match(new RegExp('; '+name+'=(\\d+)(?:;|$)'));const number=match?Number(match[1]):NaN;
    return Number.isSafeInteger(number)&&number>=0?number:undefined;
  };
  const messages=doc.inspection.messages,collection=doc.inspection.collection.value;
  const records=new Map(doc.inspection.metadataRecords.map(r=>[r.id,r]));let attachments=0,known=messages.length>0;
  for(const message of messages){
    const count=records.get(message.metadataRecordId)?.fields.NumberAttachments;
    if(count?.state!=='present'||!/^\+?\d+$/.test(count.value??'')||!Number.isSafeInteger(Number(count.value)))known=false;
    else attachments+=Number(count.value);
  }
  const folderCount=declared('folders')??(collection?collection.folders.filter(f=>f.parentId!==null||f.name.value!=='Collection').length:undefined);
  const format=(value:number|undefined)=>value===undefined?'Unknown':value.toLocaleString();
  return `${format(messages.length||declared('messages'))} messages · ${format(folderCount)} folders · ${format(known?attachments:declared('attachments'))} attachments`;
}
