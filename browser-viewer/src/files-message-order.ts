import type {AssociatedFile,InspectionResult} from './domain/inspection.js';
import type {IndexedMessage} from './message-index.js';
import {fileLabel} from './navigation.js';
/** Conceptual Messages tree order, including folders whose rows are not mounted. */
export function messageMemberOrder(inspection:InspectionResult):Map<string,number>{
 const collection=inspection.collection.value,ranks=new Map<string,number>();if(!collection)return ranks;
 const folders=[...collection.folders].sort((a,b)=>(a.name.value??'').localeCompare(b.name.value??'',undefined,{numeric:true,sensitivity:'base'})),byId=new Map(folders.map(f=>[f.id,f]));
 const children=new Map<string|null,string[]>();
 for(const folder of folders){let parent=folder.parentId,cursor=parent;const seen=new Set([folder.id]);while(cursor){if(seen.has(cursor)||!byId.has(cursor)){parent=null;break;}seen.add(cursor);cursor=byId.get(cursor)!.parentId;}const siblings=children.get(parent)??[];siblings.push(folder.id);children.set(parent,siblings);}
 const entries=new Map<string,typeof collection.entries[number][]>();for(const entry of collection.entries){const group=entries.get(entry.fileId)??[];group.push(entry);entries.set(entry.fileId,group);}
 const files=new Map<string|null,string[]>();
 for(const file of inspection.files){if(!file.streams.some(s=>s.mimeType.value?.split(';')[0]?.toLowerCase()==='application/pdf')&&!/\.pdf$/i.test(fileLabel(file)))continue;
  const memberships=entries.get(file.id)??[],ids=new Set(memberships.flatMap(e=>e.folderIds.value??[]));
  const targets=memberships.every(e=>e.folderIds.state==='present')&&ids.size?[...ids].map(id=>byId.has(id)?id:null):[null];
  for(const target of new Set(targets)){const group=files.get(target)??[];group.push(file.id);files.set(target,group);}
 }
 const visited=new Set<string>(),pending=(children.get(null)??[]).slice().reverse().map(id=>({id,exit:false}));
 const append=(folder:string|null)=>{for(const id of files.get(folder)??[])if(!ranks.has(id))ranks.set(id,ranks.size);};
 while(pending.length){const frame=pending.pop()!;if(frame.exit){append(frame.id);continue;}if(visited.has(frame.id))continue;visited.add(frame.id);pending.push({id:frame.id,exit:true});for(const id of (children.get(frame.id)??[]).slice().reverse())pending.push({id,exit:false});}
 append(null);return ranks;
}
export function filesInMessageOrder(inspection:InspectionResult,selected:readonly AssociatedFile[],category:(file:AssociatedFile)=>number,indexed:readonly IndexedMessage[]=[],details:ReadonlyMap<string,Record<string,string>>=new Map()):AssociatedFile[]{
 const members=messageMemberOrder(inspection),messageRanks=new Map(inspection.messages.map((m,i)=>[m.id,i]));
 if(members.size){const routes=new Map<string,number[]>();for(const item of indexed){const rank=item.path[0]&&members.get(item.path[0].fileId);if(rank===undefined||!item.mailGuid)continue;const values=routes.get(item.mailGuid)??[];values.push(rank);routes.set(item.mailGuid,values);}
  for(const message of inspection.messages){const ranks=routes.get(message.mailGuid.value??'');messageRanks.set(message.id,ranks?.length===1?ranks[0]!:members.size+messageRanks.get(message.id)!);}
 }
 // Use the owning email's Sent date, never the attachment filename or PDF timestamp.
 const memberDates=new Map<string,number>(),guidDates=new Map<string,number>(),messageDates=new Map<string,number>();
 const remember=(map:Map<string,number>,id:string,date:number)=>map.set(id,Math.min(map.get(id)??Infinity,date));
 for(const item of indexed){const parsed=Date.parse(item.sent??'');if(!Number.isFinite(parsed))continue;
  if(item.path[0])remember(memberDates,item.path[0].fileId,parsed);
  if(item.mailGuid)remember(guidDates,item.mailGuid,parsed);
  if(!item.path.length)remember(messageDates,item.messageId,parsed);
 }
 for(const message of inspection.messages){const parsed=Date.parse(details.get(message.metadataRecordId)?.Sent??'');if(Number.isFinite(parsed))remember(messageDates,message.id,parsed);const indexedDate=guidDates.get(message.mailGuid?.value??'');if(indexedDate!==undefined)remember(messageDates,message.id,indexedDate);}
 const date=(file:AssociatedFile)=>Math.min(memberDates.get(file.id)??Infinity,...(file.owners.value??[]).map(id=>messageDates.get(id)??Infinity));
 const rank=(file:AssociatedFile)=>members.get(file.id)??Math.min(...(file.owners.value??[]).map(id=>messageRanks.get(id)??Infinity));
 return selected.map((file,index)=>({file,index,rank:rank(file),date:date(file)})).sort((a,b)=>category(a.file)-category(b.file)||a.date-b.date||a.rank-b.rank||a.index-b.index).map(row=>row.file);
}
