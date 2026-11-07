import type {BrowserDocument,BrowserSession} from './browser-session.js';
import type {CollectionMatch,SearchStep} from './collection-search.js';
import {decodeEmbeddedGzip} from './embedded-index.js';
import {sha256Blob} from './blob-hash.js';
import {sha256} from './domain/hashes.js';
import {fileLabel} from './navigation.js';
import {textMatches,normalizeSearchText} from './document-search.js';
export const FULLTEXT_LIMITS={compressed:16*1024**2,decoded:128*1024**2,documents:100000,pages:200000,terms:250000,postings:5000000,characters:32000000,row:16*1024**2,retained:256*1024**2};
type IndexedDoc={route:string[][];md5:string;size:number;pages:number};
type IndexedPage={doc:number;page:number;text:number;guid:string};
export type FullTextIndex={documents:IndexedDoc[];pages:IndexedPage[];texts:string[];folded:string[];terms:Map<string,Uint32Array>};
type ReadResult={index?:FullTextIndex;diagnostic?:string};
const cache=new WeakMap<BrowserDocument,Promise<ReadResult>>();
export function forgetFullTextIndex(doc:BrowserDocument):void{cache.delete(doc);}
export function invalidateFullTextIndex(doc:BrowserDocument):void{cache.set(doc,Promise.resolve({diagnostic:'Full-text index disagrees with the selected PDF page; using PDF.js search.'}));}
function requireValue(value:unknown,message:string):asserts value{if(!value)throw Error(message);}
const integer=(value:unknown,max:number)=>Number.isSafeInteger(value)&&Number(value)>=0&&Number(value)<=max;
export async function verifyFileBinding(blob:Blob,digest:string,check:()=>void):Promise<void>{
 requireValue(/^[a-f0-9]{64}$/.test(digest),'missing file binding');
 const marker='<eapdfindex:fileDigest>';let tail='',found=-1;
 for(let start=0;start<blob.size;start+=1024*1024){
  check();const chunk=new TextDecoder('latin1').decode(await blob.slice(start,start+1024*1024).arrayBuffer()),text=tail+chunk;
  for(let from=0;;){const at=text.indexOf(marker,from);if(at<0)break;const position=start-tail.length+at+marker.length;
   if(position!==found){requireValue(found<0,'ambiguous file binding');found=position;}from=at+marker.length;
  }tail=text.slice(-(marker.length-1));
 }
 requireValue(found>=0,'file binding not stored unfiltered');
 requireValue(await blob.slice(found,found+64).text()===digest,'file binding differs from XMP');
 const masked=new Blob([blob.slice(0,found),'0'.repeat(64),blob.slice(found+64)]);
 requireValue(await sha256Blob(masked,check)===digest,'PDF changed after index generation');
}
export async function readFullTextIndex(session:BrowserSession,doc:BrowserDocument,check:()=>void):Promise<ReadResult>{
 if(doc.fullTextIndex===undefined)return {};
 let pending=cache.get(doc);
 if(!pending){pending=(async()=>{
  const deadline=performance.now()+120000,guard=()=>{check();requireValue(performance.now()<deadline,'index loading time limit');};
  requireValue(typeof doc.indexFileDigest==='string','missing/ambiguous file binding');
  await verifyFileBinding(await session.sourceBlob(doc),doc.indexFileDigest,guard);
  const bytes=await decodeEmbeddedGzip(doc.fullTextIndex,FULLTEXT_LIMITS,guard);
  return {index:await parseFullTextIndex(bytes,doc,guard)};
 })().catch(error=>{check();return {diagnostic:`Full-text index unavailable: ${error instanceof Error?error.message:String(error)}. Using PDF.js search.`};});cache.set(doc,pending);}
 try{const result=await pending;check();return result;}catch(error){cache.delete(doc);throw error;}
}
export async function parseFullTextIndex(bytes:Uint8Array,root:BrowserDocument,check:()=>void):Promise<FullTextIndex>{
 const first=bytes.indexOf(10),decoder=new TextDecoder('utf-8',{fatal:true});
 requireValue(bytes.length<=FULLTEXT_LIMITS.decoded&&first>0&&first<=1024&&bytes.at(-1)===10,'index framing/size');
 const header=JSON.parse(decoder.decode(bytes.subarray(0,first)));
 requireValue(header.format==='eapdf-search-index'&&header.version===3&&header.engine==='pdfbox-nfkd-space-fold-v2','unsupported full-text format/engine');
 requireValue(header.complete===true,'incomplete indexed coverage');
 requireValue(header.profile==='1'+root.inspection.identification.declaredProfile.value&&['1m','1mi','1c','1ci'].includes(header.profile),'profile mismatch');
 for(const key of ['documents','pages','terms','postings'] as const)requireValue(integer(header[key],FULLTEXT_LIMITS[key]),key+' limit');
 requireValue(header.documents>0&&header.pages>=root.pdf.numPages&&integer(header.texts,FULLTEXT_LIMITS.pages)&&header.texts>0,'text/page count limit');
 requireValue(await sha256(bytes.subarray(first+1))===header.sha256,'payload integrity mismatch');check();
 const index:FullTextIndex={documents:[],pages:[],texts:[],folded:[],terms:new Map()},routes=new Set<string>();
 let phase=0,start=first+1,characters=0,retained=0,expectedPostings=0,actualPostings=0,rows=0;const deadline=performance.now()+20000;
 const pageCounts:number[]=[];
 while(start<bytes.length){
  if(++rows%250===0){check();requireValue(performance.now()<deadline,'index parsing time limit');await new Promise(r=>setTimeout(r,0));check();}
  const end=bytes.indexOf(10,start);requireValue(end>=start&&end-start<=FULLTEXT_LIMITS.row,'index row limit');
  const row=JSON.parse(decoder.decode(bytes.subarray(start,end)));start=end+1;
  requireValue(Array.isArray(row)&&row.length===2,'invalid index row');const [type,value]=row;
  const next=['D','P','T','W'].indexOf(type);requireValue(next>=phase,'unordered/unknown section');phase=next;
  if(type==='D'){
   requireValue(Array.isArray(value)&&value.length===4&&index.documents.length<header.documents,'invalid document');const [route,md5,size,pages]=value;
   requireValue(Array.isArray(route)&&route.length<=8&&route.every((keys:unknown)=>Array.isArray(keys)&&keys.length>0&&keys.length<=256&&keys.every(k=>typeof k==='string'&&k.length<=16384)&&JSON.stringify(keys)===JSON.stringify([...new Set(keys)].sort())),'invalid document route');
   const identity=JSON.stringify(route);requireValue(!routes.has(identity),'duplicate document route');routes.add(identity);
   requireValue(integer(pages,FULLTEXT_LIMITS.pages)&&pages>0&&typeof md5==='string'&&integer(size,512*1024**2),'invalid document properties');
   if(index.documents.length===0)requireValue(route.length===0&&pages===root.pdf.numPages&&md5===''&&size===0,'root page count mismatch');
   else requireValue(route.length>0&&/^[a-f0-9]{32}$/.test(md5)&&size>0&&routes.has(JSON.stringify(route.slice(0,-1))),'missing parent/child identity');
   index.documents.push({route,md5,size,pages});pageCounts.push(0);retained+=identity.length*2+128;
  }else if(type==='P'){
   requireValue(index.documents.length===header.documents&&Array.isArray(value)&&value.length===4&&index.pages.length<header.pages,'invalid page');const [doc,page,text,guid]=value;
   requireValue(integer(doc,index.documents.length-1)&&integer(page,index.documents[doc]!.pages)&&page===pageCounts[doc]!+1&&integer(text,header.texts-1)&&typeof guid==='string'&&guid.length<=1024,'invalid page destination');
   pageCounts[doc]!++;index.pages.push({doc,page,text,guid});retained+=64+guid.length*2;
  }else if(type==='T'){
   requireValue(index.pages.length===header.pages&&typeof value==='string'&&value.length<=1000000&&index.texts.length<header.texts,'invalid page text');
   characters+=value.length;requireValue(characters<=FULLTEXT_LIMITS.characters,'text character limit');
   const folded=normalizeSearchText(value);requireValue(value===folded,'noncanonical page text');index.texts.push(value);index.folded.push(folded);retained+=(value.length+folded.length)*2;
   for(const _ of folded.matchAll(/[^ ]+/g))expectedPostings++;
   requireValue(expectedPostings<=FULLTEXT_LIMITS.postings,'positional record limit');
  }else{
   requireValue(index.texts.length===header.texts&&Array.isArray(value)&&value.length===2&&index.terms.size<header.terms,'invalid term');const [term,delta]=value;
   requireValue(typeof term==='string'&&term.length>0&&!term.includes(' ')&&term.length<=1000000&&!index.terms.has(term)&&Array.isArray(delta)&&delta.length>0&&delta.length%2===0&&delta.length/2<=FULLTEXT_LIMITS.postings-actualPostings,'invalid posting list');
   const positions=new Uint32Array(delta.length);let text=0,position=0;
   for(let i=0;i<delta.length;i+=2){
    if((actualPostings+i/2)%16384===0){check();requireValue(performance.now()<deadline,'index parsing time limit');await new Promise(r=>setTimeout(r,0));}
    requireValue(integer(delta[i],header.texts-1)&&integer(delta[i+1],2000000),'invalid posting delta');
    const previousText=text,previousPosition=position;text+=delta[i];position=delta[i+1]+(delta[i]===0?position:0);
    const content=index.folded[text];requireValue(content!==undefined&&content.slice(position,position+term.length)===term&&(position===0||content[position-1]===' ')&&(position+term.length===content.length||content[position+term.length]===' ')&&(i===0||text>previousText||position>previousPosition),'posting disagrees with text');
    positions[i]=text;positions[i+1]=position;
   }
   actualPostings+=positions.length/2;index.terms.set(term,positions);retained+=term.length*2+positions.byteLength+64;
  }
  requireValue(retained<=FULLTEXT_LIMITS.retained,'retained index memory limit');
 }
 requireValue(index.documents.length===header.documents&&index.pages.length===header.pages&&index.texts.length===header.texts&&index.terms.size===header.terms&&actualPostings===header.postings&&actualPostings===expectedPostings&&pageCounts.every((n,i)=>n===index.documents[i]!.pages),'incomplete index inventory');
 const members=new Set(root.inspection.collection.value?.entries.map(e=>e.fileId)??[]),indexedMembers=new Set<string>(),direct=collectionSteps(root),filesById=new Map(root.inspection.files.map(f=>[f.id,f]));
 requireValue([...members].every(id=>filesById.has(id)),'missing collection file');
 for(const doc of index.documents.filter(d=>d.route.length===1)){
  const file=direct.get(JSON.stringify(doc.route[0]))?.step;requireValue(file&&members.has(file.fileId),'child not in collection');
  const actual=filesById.get(file.fileId)!;
  requireValue(actual.streams.length===1&&actual.streams[0]!.declaredMd5.value===doc.md5&&actual.streams[0]!.declaredSize.value===doc.size,'child fingerprint mismatch');indexedMembers.add(file.fileId);
 }
 if(root.inspection.collection.value){requireValue(root.inspection.coverage.associatedFiles==='present','collection inventory unavailable');for(const file of root.inspection.files)if(members.has(file.id)&&(file.streams.some(s=>s.mimeType.value==='application/pdf')||/\.pdf$/i.test(fileLabel(file))))requireValue(indexedMembers.has(file.id),'unindexed child');}
 const owners=fullTextPageOwners(root);
 for(const page of index.pages.filter(p=>p.doc===0))requireValue(page.guid===(owners.get(page.page)??''),'page owner mismatch');
 check();return index;
}
function collectionSteps(doc:BrowserDocument):Map<string,{step:SearchStep;name:string}>{
 const keys=new Map<string,string[]>();
 for(const entry of doc.inspection.collection.value?.entries??[]){const list=keys.get(entry.fileId)??[];list.push(entry.nameTreeKey.value??'');keys.set(entry.fileId,list);}
 const result=new Map<string,{step:SearchStep;name:string}>();
 for(const file of doc.inspection.files){const names=keys.get(file.id);if(names&&file.streams.length===1)result.set(JSON.stringify(names.sort()),{step:{fileId:file.id,streamPath:file.streams[0]!.locator.path},name:fileLabel(file)});}
 return result;
}
export function resolveStep(doc:BrowserDocument,keys:readonly string[]):SearchStep|undefined{return collectionSteps(doc).get(JSON.stringify(keys))?.step;}
export async function queryFullTextIndex(index:FullTextIndex,root:BrowserDocument,query:string,check:()=>void):Promise<CollectionMatch[]>{
 const needle=normalizeSearchText(query);if(!needle)return [];
 const first=needle.split(' ')[0]!,candidates=new Set<number>();let work=0,visitedPostings=0;const deadline=performance.now()+2000;
 for(const [term,positions] of index.terms){
  if(++work%1000===0){check();await new Promise(r=>setTimeout(r,0));requireValue(performance.now()<deadline,'indexed query time limit');}
  if(!term.includes(first))continue;
  for(let n=0;n<positions.length;n+=2){if(visitedPostings++%32768===0){check();requireValue(performance.now()<deadline,'indexed query time limit');await new Promise(r=>setTimeout(r,0));}candidates.add(positions[n]!);}
 }
 const hits=new Map<number,{count:number;excerpt:string;hash:string}>();
 for(const id of candidates){check();requireValue(performance.now()<deadline,'indexed query time limit');const text=index.texts[id]!,matches=textMatches(text,query,1000001);requireValue(matches.length<=1000000,'match count limit');if(matches.length){const at=matches[0]!;hits.set(id,{count:matches.length,excerpt:text.slice(Math.max(0,at.start-60),at.end+120),hash:await sha256(new TextEncoder().encode(text))});}}
 const matches:CollectionMatch[]=[];let retained=0;const direct=collectionSteps(root);
 for(const [ordinal,page] of index.pages.entries()){if(ordinal%1000===0){check();requireValue(performance.now()<deadline,'indexed query time limit');await new Promise(r=>setTimeout(r,0));}const hit=hits.get(page.text);if(!hit)continue;const doc=index.documents[page.doc]!,route=doc.route;
  const target=route.length?direct.get(JSON.stringify(route[0])):undefined;const path=target?[target.step]:[];
  const document=root.name+route.map((keys,n)=>' / '+(n===0?target!.name:keys[0]!.replace(/^<\d+>/,''))).join('');
  retained+=document.length+hit.excerpt.length+JSON.stringify(route).length;requireValue(retained<=10000000,'search result memory limit');
  matches.push({path,document,page:page.page,count:hit.count,excerpt:hit.excerpt,indexed:{route,guid:page.guid,textHash:hit.hash,md5:doc.md5,size:doc.size}});
 }
 check();return matches;
}

export function fullTextPageOwners(doc:BrowserDocument):Map<number,string>{
 const owners=new Map<number,string>();
 for(const part of doc.inspection.parts){
  if(part.pageRange.state!=='present'||part.messageIds.value?.length!==1)continue;
  const message=doc.inspection.messages.find(m=>m.id===part.messageIds.value![0]);if(!message)continue;
  const {start,end}=part.pageRange.value;
  requireValue(start>=0&&end<doc.pdf.numPages&&end>=start,'invalid ownership page range');
  for(let page=start+1;page<=end+1;page++){const guid=message.mailGuid.value??'';requireValue(!owners.has(page)||owners.get(page)===guid,'ambiguous page owner');owners.set(page,guid);}
 }
 return owners;
}
