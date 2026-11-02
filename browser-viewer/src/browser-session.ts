import {sourceMessageId} from './source-email.js';
import {loadManagedPdf} from './pdfjs-adapter.js';
import {sha256Blob} from './blob-hash.js';
import {md5Responsive,sha256} from './domain/hashes.js';
import {DEFAULT_LIMITS,interpret,type StreamFacts} from './domain/interpret.js';
import type {InspectedDocument} from './inspection-session.js';
import type {AssociatedFile,EmbeddedStream,InspectionLimits} from './domain/inspection.js';
import type {DocumentAssets} from './pdfjs-assets.js';
import type {DocumentPartsInspection} from './pdfjs-document-parts.js';

export type BrowserDocument = InspectedDocument & {
  name:string; parent:BrowserDocument|null; parentFileId?:string; parentStreamPath?:string;
  ancestorIds:readonly string[]; active:boolean;
};
type Entry={managed?:ReturnType<typeof loadManagedPdf>;blob?:Blob;parts:DocumentPartsInspection;assets:DocumentAssets;facts:Map<string,StreamFacts>;header:string|null;size:number;lastUse:number};
export class BrowserSession {
  readonly limits:InspectionLimits;
  readonly extractionLimit:number;
  private decoded=0;
  private childCount=0;
  private readonly extracted=new Map<string,Uint8Array>();
  private cachedBytes=0;
  private readonly cacheLimit=32*1024**2;
  constructor(overrides:Partial<InspectionLimits>={}) {
    const ceilings={...DEFAULT_LIMITS,maxFileBytes:512*1024**2,maxStreamBytes:32*1024**2,maxDecodedBytes:128*1024**2,maxMetadataBytes:16*1024**2,maxChildDepth:8,maxChildren:128};
    const limits={...ceilings};
    for(const key of Object.keys(overrides) as (keyof InspectionLimits)[]){
      const value=overrides[key]!;
      if(!Number.isSafeInteger(value)||value<0||value>ceilings[key])throw new Error(`Invalid inspection limit: ${key}`);
      limits[key]=value;
    }
    this.limits=limits;
    this.extractionLimit=this.limits.maxStreamBytes;
  }
  private readonly entries=new Map<BrowserDocument,Entry>();
  private readonly pending=new Set<ReturnType<typeof loadManagedPdf>>();
  private closed=false;
  private clock=0;
  private check=()=>{if(this.closed)throw new Error('Document processing cancelled.');};
  close():void {this.closed=true;for(const managed of this.pending)managed.close();for(const [doc,entry]of this.entries){entry.managed?.close();doc.payloads.clear();doc.active=false;}this.entries.clear();this.pending.clear();this.extracted.clear();this.cachedBytes=0;}
  async open(blob:Blob,name:string,progress?:(fraction:number)=>void,parent:BrowserDocument|null=null,fileId?:string,streamPath?:string,preview?:(doc:Pick<BrowserDocument,'pdf'|'run'>)=>Promise<void>):Promise<BrowserDocument>{
    this.check();
    if(blob.size>this.limits.maxFileBytes)throw new Error('PDF exceeds the configured input byte limit.');
    if(this.pending.size>=3)throw new Error('Too many PDF operations are already pending.');
    if(parent&&parent.ancestorIds.length+1>this.limits.maxChildDepth)throw new Error('Embedded document depth limit reached.');
    if(parent&&this.childCount>=this.limits.maxChildren)throw new Error('Embedded document count limit reached. Use Reopen PDF to start again.');
    const managed=loadManagedPdf(blob);this.pending.add(managed);
    const hashing=sha256Blob(blob,()=>{this.check();if(managed.closed)throw new Error('Document is closed.');},progress);void hashing.catch(()=>{});
    try{
      const pdf=await managed.run(managed.task.promise,60000);this.check();
      if(preview)await preview({pdf,run:managed.run});this.check();
      const hash=await hashing;this.check();
      const id='sha256:'+hash,ancestors=parent?[...parent.ancestorIds,parent.inspection.documentId]:[];
      if(ancestors.includes(id))throw new Error('This embedded PDF points back to a parent document.');
      if(parent){if(this.childCount>=this.limits.maxChildren)throw new Error('Embedded document count limit reached. Use Reopen PDF to start again.');this.childCount++;}
      const [parts,assets]=await managed.run(Promise.all([pdf.getDocumentParts(),pdf.getDocumentAssets({maxObjects:Math.max(1,this.limits.maxObjects),maxMetadataBytes:Math.max(1,this.limits.maxMetadataBytes)})]),60000);
      const header=/%PDF-(\d\.\d)/.exec(new TextDecoder('latin1').decode(await blob.slice(0,1024).arrayBuffer()))?.[1]??null;
      this.check();
      const entry:Entry={managed,blob:parent?undefined:blob,parts,assets,facts:new Map(),header,size:blob.size,lastUse:++this.clock};
      const interpreted=interpret({documentId:id,byteLength:blob.size,headerVersion:header,pageCount:pdf.numPages,parts,assets,streams:entry.facts,limits:this.limits,parseXml:xml=>new DOMParser().parseFromString(xml,'application/xml')});
      const doc:BrowserDocument={pdf,...interpreted,payloads:new Map(),children:new Map(),run:managed.run,name,parent,parentFileId:fileId,parentStreamPath:streamPath,ancestorIds:ancestors,active:true};
      this.entries.set(doc,entry);this.pending.delete(managed);this.trim(doc);return doc;
    }catch(error){managed.close();this.pending.delete(managed);throw error;}
  }
  async associateFiles(doc:BrowserDocument):Promise<void>{
    for(const file of doc.inspection.files){
      if(file.owners.value||!['Source','Supplement'].includes(file.afRelationship.value??''))continue;
      for(const stream of file.streams){
        if(!/\.(eml|txt)$/i.test(file.unicodeFilename.value??file.filename.value??'')&&!/^(message\/(rfc822|global)|text\/plain)/i.test(stream.mimeType.value??''))continue;
        if(this.entries.get(doc)?.facts.has(stream.locator.path))continue;
        try{await this.bytes(doc,file,stream);}catch(error){
          this.check();this.entries.get(doc)?.facts.set(stream.locator.path,{state:'unavailable',reason:String(error)});
        }
      }
    }
    const missing=doc.inspection.files.filter(f=>!f.owners.value&&['Source','Supplement'].includes(f.afRelationship.value??'')&&doc.inspection.messages.length>0);
    doc.inspection={...doc.inspection,diagnostics:[...doc.inspection.diagnostics.filter(d=>d.code!=='FILE_EMAIL_ASSOCIATION_MISSING'),...missing.map(f=>({code:'FILE_EMAIL_ASSOCIATION_MISSING',state:'unavailable' as const,message:`The PDF does not provide a unique, readable email association for ${f.unicodeFilename.value??f.filename.value??'an embedded file'}.`,evidence:[f.locator]}))]};
  }
  private trim(keep:BrowserDocument):void {
    const active=[...this.entries].filter(([doc])=>doc.active).sort((a,b)=>a[1].lastUse-b[1].lastUse);
    while(active.length>3){const [doc,entry]=active.shift()!;if(doc===keep)continue;entry.managed?.close();entry.managed=undefined;doc.active=false;doc.payloads.clear();}
  }
  async sourceBlob(doc:BrowserDocument):Promise<Blob>{
    this.check();const entry=this.entries.get(doc);if(!entry)throw new Error('Document is no longer open.');
    if(entry.blob)return entry.blob;
    const parent=doc.parent,file=parent?.inspection.files.find(f=>f.id===doc.parentFileId);
    const stream=file?.streams.find(s=>s.locator.path===doc.parentStreamPath);
    if(!parent||!file||!stream)throw new Error('Original file is unavailable.');
    return new Blob([new Uint8Array(await this.bytes(parent,file,stream))]);
  }
  async ensure(doc:BrowserDocument):Promise<void>{
    this.check();const entry=this.entries.get(doc);if(!entry)throw new Error('Document is no longer open.');
    if(entry.managed?.closed){doc.active=false;entry.managed=undefined;}
    if(doc.active){entry.lastUse=++this.clock;return;}
    let blob=entry.blob;
    if(!blob&&doc.parent){
      const file=doc.parent.inspection.files.find(f=>f.id===doc.parentFileId),stream=file?.streams.find(s=>s.locator.path===doc.parentStreamPath);
      if(!file||!stream)throw new Error('Parent file is unavailable.');
      blob=new Blob([new Uint8Array(await this.bytes(doc.parent,file,stream))]);
    }
    if(!blob)throw new Error('Original file is no longer available.');
    const managed=loadManagedPdf(blob);this.pending.add(managed);
    try{
      const pdf=await managed.run(managed.task.promise,60000),assets=await managed.run(pdf.getDocumentAssets({maxObjects:Math.max(1,this.limits.maxObjects),maxMetadataBytes:Math.max(1,this.limits.maxMetadataBytes)}),60000);this.check();
      entry.assets=assets;entry.managed=managed;entry.lastUse=++this.clock;doc.pdf=pdf;doc.run=managed.run;doc.active=true;this.trim(doc);
    }catch(error){managed.close();throw error;}finally{this.pending.delete(managed);}
  }
  async bytes(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream):Promise<Uint8Array>{
    await this.ensure(doc);const entry=this.entries.get(doc)!;this.check();
    const key=doc.inspection.documentId+'|'+stream.locator.path;
    const cached=this.extracted.get(key);
    if(cached){this.extracted.delete(key);this.extracted.set(key,cached);return cached.slice();}
    const allowance=Math.min(this.extractionLimit,this.limits.maxDecodedBytes-this.decoded);
    if(allowance<=0)throw new Error('Session decoded-byte limit reached. Use Reopen PDF to start again.');
    // Reserve before awaiting, so concurrent reads cannot oversubscribe the budget.
    // A failed decode keeps its reservation: it may already have performed work.
    this.decoded+=allowance;
    const data=await doc.run(doc.pdf.getAssociatedFileBytes({documentId:entry.assets.documentId,fileId:file.locator.path,streamId:stream.locator.path,maxBytes:allowance}),60000);
    this.decoded-=allowance-data.length;
    this.check();
    // Cache only successful bounded reads; failed attempts retain their work reservation.
    if(data.length<=this.cacheLimit){
      while(this.cachedBytes+data.length>this.cacheLimit&&this.extracted.size){
        const oldest=this.extracted.keys().next().value!;this.cachedBytes-=this.extracted.get(oldest)!.length;this.extracted.delete(oldest);
      }
      this.extracted.set(key,data.slice());this.cachedBytes+=data.length;
    }
    entry.facts.set(stream.locator.path,{state:'present',size:data.length,md5:await md5Responsive(data,this.check),sha256:await sha256(data),sourceMessageId:['Source','Supplement'].includes(file.afRelationship.value??'')?sourceMessageId(data,file.afRelationship.value==='Supplement'):undefined});
    const interpreted=interpret({documentId:doc.inspection.documentId,byteLength:entry.size,headerVersion:entry.header,pageCount:doc.pdf.numPages,parts:entry.parts,assets:entry.assets,streams:entry.facts,limits:this.limits,parseXml:xml=>new DOMParser().parseFromString(xml,'application/xml')});
    doc.inspection=interpreted.inspection;doc.details=interpreted.details;return data;
  }
  async child(parent:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream):Promise<BrowserDocument>{
    const key=file.id+'|'+stream.locator.path,previous=parent.children.get(key) as BrowserDocument|undefined;
    if(previous){await this.ensure(previous);return previous;}
    if(parent.ancestorIds.length+1>this.limits.maxChildDepth)throw new Error('Embedded document depth limit reached.');
    if(this.childCount>=this.limits.maxChildren)throw new Error('Embedded document count limit reached. Use Reopen PDF to start again.');
    const bytes=await this.bytes(parent,file,stream);
    if(!/%PDF-\d\.\d/.test(new TextDecoder('latin1').decode(bytes.subarray(0,1024))))throw new Error('The embedded file is not a readable PDF.');
    const doc=await this.open(new Blob([new Uint8Array(bytes)]),file.unicodeFilename.value??file.filename.value??'Embedded PDF',undefined,parent,file.id,stream.locator.path);
    parent.children.set(key,doc);return doc;
  }
}
