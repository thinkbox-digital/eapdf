import type {BrowserDocument} from './browser-session.js';
import type {IndexedMessage} from './message-index.js';
import {fileLabel} from './navigation.js';
import {sha256} from './domain/hashes.js';

export const INDEX_LIMITS=Object.freeze({compressed:4*1024*1024,decoded:8*1024*1024,messages:100_000,row:128*1024,characters:8_000_000});
export const SEARCH_FIELDS=['Subject','From','Raw-From','To','Raw-To','Cc','Raw-Cc','Bcc','Raw-Bcc','Sent'] as const;
export const metadataSearchText=(data:Record<string,string>)=>SEARCH_FIELDS.map(k=>data[k]??'').join(' ').toLowerCase();
export type EmbeddedIndexResult={messages?:IndexedMessage[];diagnostic?:string};
const cache=new WeakMap<BrowserDocument,Promise<EmbeddedIndexResult>>();
export function forgetEmbeddedIndex(doc:BrowserDocument):void{cache.delete(doc);}
export async function readEmbeddedIndex(doc:BrowserDocument,check:()=>void):Promise<EmbeddedIndexResult>{
  if(doc.embeddedIndex===undefined)return {};
  let pending=cache.get(doc);
  if(!pending){pending=decodeIndex(doc,check).then(messages=>({messages})).catch(error=>{check();return {diagnostic:`Embedded metadata index unavailable: ${error instanceof Error?error.message:String(error)}. Reading child PDFs instead.`};});cache.set(doc,pending);}
  try{const result=await pending;check();return result;}catch(error){cache.delete(doc);throw error;}
}
function requireIndex(value:unknown,message:string):asserts value{if(!value)throw new Error(message);}
function same(a:unknown,b:unknown):boolean{return JSON.stringify(a)===JSON.stringify(b);}
const sorted=(values:readonly string[])=>[...new Set(values)].sort();

export async function decodeIndex(doc:BrowserDocument,check:()=>void):Promise<IndexedMessage[]>{
  const bytes=await decodeEmbeddedGzip(doc.embeddedIndex,INDEX_LIMITS,check);
  const boundary=bytes.indexOf(10);requireIndex(boundary>0&&boundary<=1024,'invalid header');
  const utf8=new TextDecoder('utf-8',{fatal:true}),header=JSON.parse(utf8.decode(bytes.subarray(0,boundary)));
  requireIndex(header?.format==='eapdf-metadata-index'&&header.version===1,'unsupported format/version');
  requireIndex(header.profile==='1'+doc.inspection.identification.declaredProfile.value&&['1c','1ci'].includes(header.profile),'profile mismatch');
  requireIndex(Number.isInteger(header.messages)&&header.messages>=0&&header.messages<=INDEX_LIMITS.messages,'message count limit');
  requireIndex(typeof header.sha256==='string'&&/^[a-f0-9]{64}$/.test(header.sha256),'invalid integrity digest');
  requireIndex(await sha256(bytes.subarray(boundary+1))===header.sha256,'integrity mismatch');check();
  requireIndex(bytes.at(-1)===10,'truncated payload');
  const collection=doc.inspection.collection.value;
  requireIndex(collection&&doc.inspection.coverage.metadata==='present'&&doc.inspection.coverage.associatedFiles==='present','authoritative metadata unavailable');
  requireIndex(!doc.inspection.diagnostics.some(d=>/XMP_|METADATA_|FOLDER/.test(d.code)),'ambiguous or incomplete authoritative metadata');
  const authoritative=new Map<string,typeof doc.inspection.messages[number]>();
  for(const message of doc.inspection.messages){const guid=message.mailGuid.value;requireIndex(guid&&!authoritative.has(guid),'missing or ambiguous message identity');authoritative.set(guid,message);}
  requireIndex(authoritative.size===header.messages,'incomplete message inventory');
  const files=new Map(doc.inspection.files.map(f=>[f.id,f])),byKey=new Map<string,string>(),keysByFile=new Map<string,string[]>();
  const folderMap=new Map(collection.folders.map(f=>[f.id,f])),pathsByFile=new Map<string,string[]>();
  for(const entry of collection.entries){
    const key=entry.nameTreeKey.value;requireIndex(key&&!byKey.has(key),'ambiguous collection key');byKey.set(key,entry.fileId);
    const keys=keysByFile.get(entry.fileId)??[];keys.push(key);keysByFile.set(entry.fileId,keys);
    requireIndex(entry.folderIds.value,'unavailable folder membership');
    const paths=pathsByFile.get(entry.fileId)??[];
    if(!entry.folderIds.value.length)paths.push('');
    for(const folderId of entry.folderIds.value){let id:string|null=folderId;const names:string[]=[],seen=new Set<string>();
      while(id){requireIndex(!seen.has(id)&&seen.size<256,'folder cycle/depth limit');seen.add(id);const folder=folderMap.get(id);requireIndex(folder?.name.value!==null&&folder,'unavailable folder');names.unshift(folder.name.value!.replaceAll('\\','\\\\').replaceAll('/','\\/'));id=folder.parentId;}
      const path=names.filter(Boolean).join('/');requireIndex(path.length<=16384,'folder path limit');paths.push(path);
    }
    pathsByFile.set(entry.fileId,paths);
  }
  const rows=new Map<string,IndexedMessage>(),seenGuids=new Set<string>();let characters=0,count=0,start=boundary+1;
  while(start<bytes.length){
    if(count%250===0){check();await new Promise(resolve=>setTimeout(resolve,0));check();}
    const end=bytes.indexOf(10,start);requireIndex(end>=start&&end-start<=INDEX_LIMITS.row,'row size limit');
    const row=JSON.parse(utf8.decode(bytes.subarray(start,end)));start=end+1;count++;
    requireIndex(count<=header.messages&&Array.isArray(row)&&row.length===13,'invalid row/count');
    requireIndex(row.slice(0,8).every(v=>typeof v==='string'),'invalid metadata');
    const [guid,mid,subject,from,to,cc,bcc,sent,keys,md5,size,paths,page]=row;
    requireIndex(!seenGuids.has(guid)&&authoritative.has(guid),'duplicate or unknown message identity');seenGuids.add(guid);
    requireIndex(Array.isArray(keys)&&keys.length>0&&keys.length<=256&&keys.every(k=>typeof k==='string'),'invalid child keys');
    const fileId=byKey.get(keys[0]!),file=fileId&&files.get(fileId);requireIndex(fileId&&file&&!rows.has(file.id),'missing or ambiguous child');
    requireIndex(same(keys,sorted(keysByFile.get(file.id)??[]))&&keys.every(k=>byKey.get(k)===file.id),'collection membership mismatch');
    requireIndex(same(paths,sorted(pathsByFile.get(file.id)??[])),'folder membership mismatch');
    requireIndex(file.streams.length===1&&file.streams[0]!.mimeType.value==='application/pdf','child is not one PDF stream');
    const stream=file.streams[0]!;
    requireIndex(typeof md5==='string'&&/^[a-f0-9]{32}$/.test(md5)&&stream.declaredMd5.value===md5&&Number.isSafeInteger(size)&&size>0&&stream.declaredSize.value===size,'child identity/checksum mismatch');
    requireIndex(page===1,'unsupported page destination');
    const message=authoritative.get(guid)!,data=doc.details.get(message.metadataRecordId)??{};
    requireIndex(same([mid,subject,from,to,cc,bcc,sent],[message.internetMessageId.value??'',...['Subject','Raw-From','Raw-To','Raw-Cc','Raw-Bcc','Sent'].map(k=>data[k]??'')]),'metadata differs from catalog XMP');
    const label=data.Subject||fileLabel(file),document=doc.name+' / '+fileLabel(file),searchText=metadataSearchText(data);
    characters+=label.length+document.length+searchText.length;requireIndex(characters<=INDEX_LIMITS.characters,'retained metadata limit');
    rows.set(file.id,{path:[{fileId:file.id,streamPath:stream.locator.path}],messageId:guid,mailGuid:guid,label,document,searchText,sender:data.From||data['Raw-From'],sent:data.Sent});
  }
  requireIndex(count===header.messages,'incomplete index');
  for(const file of doc.inspection.files){
    if(!keysByFile.has(file.id))continue;
    if(file.streams.some(s=>/^application\/pdf(?:;|$)/i.test(s.mimeType.value??''))||/\.pdf$/i.test(fileLabel(file)))requireIndex(rows.has(file.id),'unindexed PDF member');
  }
  check();return doc.inspection.files.flatMap(file=>rows.has(file.id)?[rows.get(file.id)!]:[]);
}

export async function decodeEmbeddedGzip(encoded:string|null|undefined,limits:{compressed:number;decoded:number},check:()=>void):Promise<Uint8Array>{
  check();
  requireIndex(typeof encoded==='string'&&encoded.length>0,'missing or ambiguous payload');
  requireIndex(encoded.length<=4*Math.ceil(limits.compressed/3)&&encoded.length%4===0&&!/[^A-Za-z0-9+/=]/.test(encoded),'compressed size limit or invalid base64');
  const binary=atob(encoded);requireIndex(binary.length<=limits.compressed,'compressed size limit');
  const compressed=Uint8Array.from(binary,c=>c.charCodeAt(0));check();
  const reader=new Blob([compressed]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();
  const chunks:Uint8Array[]=[];let length=0;
  try{while(true){check();const {value,done}=await reader.read();check();if(done)break;length+=value.byteLength;
    requireIndex(length<=limits.decoded,'decompressed size limit');chunks.push(value);
  }}finally{await reader.cancel();reader.releaseLock();}
  const bytes=new Uint8Array(length);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}chunks.length=0;
  return bytes;
}
