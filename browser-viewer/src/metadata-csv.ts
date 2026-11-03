import type {BrowserDocument} from './browser-session.js';
import type {AssociatedFile,EmbeddedStream,InspectionResult,MailMessage} from './domain/inspection.js';
import {fileLabel} from './navigation.js';

// Quote every field, preserve Unicode/newlines, and neutralize spreadsheet formulas.
export function csvCell(value:string):string {
  if(/^[\s\uFEFF]*[=+@-]/u.test(value)||/^[\t\r\n]/.test(value))value="'"+value;
  return '"'+value.replaceAll('"','""')+'"';
}
// Folder names come from the collection tree, not from PDF filenames.
function memberFolders(inspection:InspectionResult,fileId:string):string[]{
  const collection=inspection.collection.value!;
  if(inspection.diagnostics.some(d=>d.code.includes('FOLDER')))throw new Error('Collection folder structure is incomplete; no CSV was downloaded.');
  const folders=new Map(collection.folders.map(f=>[f.id,f]));
  const paths=new Set<string>();
  for(const entry of collection.entries.filter(e=>e.fileId===fileId)){
    if(entry.folderIds.state!=='present'||entry.folderIds.value.length>1)throw new Error('Collection folder membership is ambiguous; no CSV was downloaded.');
    if(!entry.folderIds.value.length){paths.add('');continue;}
    let id:string|null=entry.folderIds.value[0]!,names:string[]=[];const seen=new Set<string>();
    while(id){
      if(seen.has(id)||seen.size>=64)throw new Error('Collection folder hierarchy is cyclic or too deep; no CSV was downloaded.');
      seen.add(id);const folder=folders.get(id);
      if(!folder||folder.name.state!=='present')throw new Error('A collection folder name is unavailable; no CSV was downloaded.');
      names.unshift(folder.name.value.replaceAll('\\','\\\\').replaceAll('/','\\/'));id=folder.parentId;
    }
    const path=names.filter(Boolean).join('/');
    if(path.length>16384)throw new Error('Collection folder path is too long; no CSV was downloaded.');
    paths.add(path);
  }
  if(!paths.size||paths.size>256)throw new Error('Collection folder memberships are unavailable or excessive; no CSV was downloaded.');
  return [...paths];
}
function attachmentIndex(inspection:InspectionResult):(message:MailMessage)=>[string,string]{
  const records=new Map(inspection.metadataRecords.map(r=>[r.id,r]));
  const namesByOwner=new Map<string,string[]>();
  for(const record of inspection.metadataRecords.filter(r=>r.kind==='attachment')){
    if(!record.owners.value?.length)throw new Error('Attachment ownership is unavailable; no CSV was downloaded.');
    const name=record.fields.Filename;
    if(name&&name.state!=='missing'&&name.state!=='present')throw new Error('An attachment name is ambiguous or unreadable; no CSV was downloaded.');
    for(const owner of record.owners.value){const names=namesByOwner.get(owner)??[];names.push(name?.value||'(name not recorded)');namesByOwner.set(owner,names);}
  }
  return message=>{
  const declared=records.get(message.metadataRecordId)?.fields.NumberAttachments;
  if(declared&&declared.state!=='missing'&&(declared.state!=='present'||!/^\+?\d+$/.test(declared.value)||!Number.isSafeInteger(Number(declared.value))))
    throw new Error('An attachment count is invalid; no CSV was downloaded.');
  const names=[...(namesByOwner.get(message.id)??[])];
  const count=declared?.value===null||!declared?'':String(Number(declared.value));
  if(count!==''&&Number(count)<names.length)throw new Error('The attachment count conflicts with its name list; no CSV was downloaded.');
  if(count!==''&&Number(count)>names.length)names.push(`(${Number(count)-names.length} attachment name(s) not recorded)`);
  return [count,names.join('\n')];
  };
}
export async function metadataCsv(root:BrowserDocument,
  child:(parent:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream)=>Promise<BrowserDocument>,
  check:()=>void,progress:(count:number)=>void):Promise<{blob:Blob;rows:number}> {
  const chunks:string[]=['\uFEFF'+['PDF','Folder paths','From','To','Cc','Bcc','Subject','Sent','Message-ID','Attachment count','Attachment names'].map(csvCell).join(',')+'\r\n'];
  let rows=0,documents=0,bytes=0;
  const encoder=new TextEncoder();
  async function visit(doc:BrowserDocument,path:string,folderPaths:string[]):Promise<void>{
    check();documents++;
    const inspection=doc.inspection;
    if(inspection.diagnostics.some(d=>/^XMP_.*LIMIT$/.test(d.code))||
      !['present','missing'].includes(inspection.coverage.metadata)||
      inspection.diagnostics.some(d=>d.code==='XMP_RECORD_STRUCTURE'))
      throw new Error(`Metadata could not be fully read in ${path}; no CSV was downloaded.`);
    const collection=inspection.collection;
    if(!['present','missing'].includes(collection.state))throw new Error(`Collection could not be fully read in ${path}; no CSV was downloaded.`);
    if(!collection.value&&inspection.diagnostics.some(d=>d.code==='XMP_DISPLAY_VALUE_UNAVAILABLE'||d.code==='XMP_DISPLAY_VALUE_AMBIGUOUS'))
      throw new Error(`An email metadata value could not be read unambiguously in ${path}; no CSV was downloaded.`);
    const attachments=collection.value?undefined:attachmentIndex(inspection);
    // Collection-level email records index the embedded messages. Export those
    // messages from their PDFs once, retaining repetitions in distinct children.
    for(const message of collection.value?[]:inspection.messages){
      const data=doc.details.get(message.metadataRecordId)??{};
      const values=[path,folderPaths.map(p=>p?'/'+p:'/').join('\n'),...['From','To','Cc','Bcc'].map(k=>data['Raw-'+k]||data[k]||''),
        data.Subject??'',data.Sent??'',message.internetMessageId.value??'',...attachments!(message)];
      const line=values.map(csvCell).join(',')+'\r\n';bytes+=encoder.encode(line).length;
      rows++;if(bytes>64*1024**2)throw new Error('Export exceeds the 64 MiB byte budget; no CSV was downloaded.');
      chunks.push(line);
      if(rows%250===0){progress(rows);await new Promise(resolve=>setTimeout(resolve,0));check();}
    }
    if(collection.value){
      if(inspection.coverage.associatedFiles!=='present')throw new Error(`Collection files are unavailable in ${path}.`);
      let members=0;
      for(const file of inspection.files){
        if(!collection.value.entries.some(e=>e.fileId===file.id))continue;
        if(!file.streams.length&&/\.pdf$/i.test(fileLabel(file)))throw new Error(`Missing embedded PDF: ${fileLabel(file)}.`);
        for(const stream of file.streams){
          if(stream.mimeType.value?.split(';')[0]?.toLowerCase()!=='application/pdf'&&!/\.pdf$/i.test(fileLabel(file)))continue;
          check();const nested=await child(doc,file,stream);check();
          members++;
          const localFolders=memberFolders(inspection,file.id),combined=new Set<string>();
          for(const parent of folderPaths)for(const local of localFolders){
            const path=[parent,local].filter(Boolean).join('/');
            if(path.length>16384)throw new Error('Nested folder path is too long; no CSV was downloaded.');
            combined.add(path);
            if(combined.size>256)throw new Error('Too many folder paths for one email; no CSV was downloaded.');
          }
          if([...combined].reduce((n,p)=>n+p.length,0)>256*1024)throw new Error('Folder path text exceeds export limits; no CSV was downloaded.');
          await visit(nested,path+' / '+nested.name,[...combined]);
        }
      }
      if(!members)throw new Error(`No readable collection PDFs in ${path}; no CSV was downloaded.`);
    }
    progress(rows);
  }
  await visit(root,root.name,['']);check();
  if(!rows)throw new Error('No email metadata found; no CSV was downloaded.');
  return {blob:new Blob(chunks,{type:'text/csv;charset=utf-8'}),rows};
}
