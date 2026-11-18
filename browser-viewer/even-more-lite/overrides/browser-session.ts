// A reading-only session: retain the current ancestor chain, release each child on return.
import type {PDFDocumentProxy} from 'pdfjs-dist';
import type {AssociatedFile,EmbeddedStream,InspectionResult} from './domain/inspection.js';
import {DEFAULT_LIMITS,interpret} from './domain/interpret.js';
import {loadManagedPdf} from './pdfjs-adapter.js';
import {sha256,md5Responsive} from './domain/hashes.js';
import {fileLabel} from './navigation.js';

export type BrowserDocument={
  pdf:PDFDocumentProxy;run:<T>(operation:Promise<T>,milliseconds?:number)=>Promise<T>;
  inspection:InspectionResult;details:Map<string,Record<string,string>>;
  name:string;parent:BrowserDocument|null;ancestorIds:readonly string[];page:number;
  assetsId:string;parentFileId?:string;selectedMessage?:string;
  childLabels?:Map<string,Record<string,string>>;
};
export class BrowserSession {
  private entries=new Map<BrowserDocument,ReturnType<typeof loadManagedPdf>>();
  private pending=new Set<ReturnType<typeof loadManagedPdf>>();
  private closed=false;
  close():void {
    this.closed=true;
    for(const managed of [...this.pending,...this.entries.values()])void managed.close();
    this.pending.clear();this.entries.clear();
  }
  async ensure(doc:BrowserDocument):Promise<void>{
    if(this.closed||!this.entries.has(doc))throw Error('Document is closed. Reopen the PDF.');
  }
  async release(doc:BrowserDocument):Promise<void>{
    const managed=this.entries.get(doc);this.entries.delete(doc);await managed?.close();
  }
  async prune(keep:BrowserDocument):Promise<void>{
    const chain=new Set<BrowserDocument>();let cursor:BrowserDocument|null=keep;
    while(cursor){chain.add(cursor);cursor=cursor.parent;}
    for(const doc of this.entries.keys())if(!chain.has(doc))await this.release(doc);
  }
  async open(blob:Blob,name:string,parent:BrowserDocument|null=null):Promise<BrowserDocument>{
    if(this.closed)throw Error('Document is closed.');
    if(blob.size>DEFAULT_LIMITS.maxFileBytes)throw Error('PDF exceeds the 32 MiB input limit.');
    if(parent&&parent.ancestorIds.length>=DEFAULT_LIMITS.maxChildDepth)throw Error('Embedded PDF depth limit reached (4 levels).');
    const managed=loadManagedPdf(blob);this.pending.add(managed);
    try{
      const pdf=await managed.run(managed.task.promise,60000);
      if(pdf.numPages>2048)throw Error('Supports up to 2048 pages per PDF.');
      const id='sha256:'+await managed.run(sha256(new Uint8Array(await blob.arrayBuffer())));
      const ancestorIds=parent?[...parent.ancestorIds,parent.inspection.documentId]:[];
      if(ancestorIds.includes(id))throw Error('Embedded PDF points back to a parent.');
      const parts=await managed.run(pdf.getDocumentParts());
      const assets=await managed.run(pdf.getDocumentAssets({maxObjects:DEFAULT_LIMITS.maxObjects,maxMetadataBytes:DEFAULT_LIMITS.maxMetadataBytes}));
      const result=interpret({documentId:id,byteLength:blob.size,headerVersion:null,pageCount:pdf.numPages,parts,assets,
        streams:new Map(),limits:DEFAULT_LIMITS,parseXml:xml=>new DOMParser().parseFromString(xml,'application/xml')});
      if(this.closed||managed.closed)throw Error('Document opening cancelled.');
      const doc:BrowserDocument={pdf,run:managed.run,inspection:result.inspection,details:result.details,name,parent,ancestorIds,page:1,assetsId:assets.documentId};
      this.entries.set(doc,managed);return doc;
    }catch(error){void managed.close();throw error;}
    finally{this.pending.delete(managed);}
  }
  async bytes(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,check:()=>void=()=>{},strict=true):Promise<{bytes:Uint8Array;warnings:string[]}>{
    await this.ensure(doc);
    const limit=DEFAULT_LIMITS.maxStreamBytes;
    if(stream.declaredSize.value!==null&&stream.declaredSize.value>limit)throw Error('Embedded asset exceeds the 16 MiB limit.');
    check();const bytes=await doc.run(doc.pdf.getAssociatedFileBytes({documentId:doc.assetsId,fileId:file.locator.path,streamId:stream.locator.path,maxBytes:limit}));check();
    if(bytes.length>limit)throw Error('Embedded asset exceeds the 16 MiB limit.');
    const warnings:string[]=[];
    if(stream.declaredSize.value!==null&&stream.declaredSize.value!==bytes.length)warnings.push('Embedded asset size does not match its declaration.');
    if(stream.declaredMd5.value&&await md5Responsive(bytes,check)!==stream.declaredMd5.value)warnings.push('Embedded asset checksum does not match.');
    if(strict&&warnings.length)throw Error(warnings.join(' '));
    check();return {bytes,warnings};
  }
  async child(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,_retain=false):Promise<BrowserDocument>{
    const {bytes}=await this.bytes(doc,file,stream);
    const child=await this.open(new Blob([new Uint8Array(bytes)]),fileLabel(file),doc);
    const label=child.inspection.messages.length===1?child.details.get(child.inspection.messages[0]!.metadataRecordId):undefined;
    if(label)(doc.childLabels??=new Map()).set(file.id,Object.fromEntries(['Subject','From','Sent'].flatMap(key=>label[key]?[[key,label[key]!.slice(0,512)]]:[])));
    child.parentFileId=file.id;return child;
  }
}
