import type { PDFDocumentProxy } from 'pdfjs-dist';
import { loadManagedPdf } from './pdfjs-adapter.js';
import { DEFAULT_LIMITS, interpret, type StreamFacts } from './domain/interpret.js';
import { md5, sha256 } from './domain/hashes.js';
import type { ChildDocument, InspectionLimits, InspectionResult } from './domain/inspection.js';

export type InspectedDocument = {
  embeddedIndex?:string|null;
  fullTextIndex?:string|null;indexFileDigest?:string|null;
  pdf:PDFDocumentProxy;inspection:InspectionResult;details:Map<string,Record<string,string>>;
  payloads:Map<string,Uint8Array>;children:Map<string,InspectedDocument>;
  run:<T>(operation:Promise<T>,milliseconds?:number)=>Promise<T>;
};
export class InspectionSession {
  readonly limits:InspectionLimits;
  private readonly documents=new Set<ReturnType<typeof loadManagedPdf>>();
  private closed=false;
  private decoded=0;
  private childCount=0;
  constructor(options:Partial<InspectionLimits>={}){
    const limits={...DEFAULT_LIMITS};
    for(const key of Object.keys(limits) as (keyof InspectionLimits)[]){
      // Retired content-count settings are accepted for compatibility, but ignored.
      if(['maxObjects','maxParts','maxTreeDepth','maxChildDepth','maxChildren'].includes(key))continue;
      const n=options[key]??limits[key];if(!Number.isSafeInteger(n)||n<0||n>limits[key])throw new Error('Invalid inspection limit: '+key);limits[key]=n;
    }
    this.limits=Object.freeze(limits);
  }
  close():void{this.closed=true;for(const document of this.documents)document.close();this.documents.clear();}
  async open(data:Uint8Array,ancestors:readonly string[]=[],depth=0):Promise<InspectedDocument>{
    if(this.closed)throw new Error('Inspection cancelled.');
    if(data.length>this.limits.maxFileBytes)throw new Error('PDF exceeds the configured input byte limit.');
    const byteLength=data.length,hash=await sha256(data),documentId='sha256:'+hash;
    if(this.closed)throw new Error('Inspection cancelled.');
    if(ancestors.includes(documentId))throw new Error('Collection ancestor cycle.');
    const headerVersion=/%PDF-(\d\.\d)/.exec(new TextDecoder('latin1').decode(data.subarray(0,1024)))?.[1]??null;
    const managed=loadManagedPdf(data);this.documents.add(managed);
    try{
      const pdf=await managed.run(managed.task.promise);
      const [parts,assets]=await managed.run(Promise.all([pdf.getDocumentParts(),pdf.getDocumentAssets({maxObjects:Math.max(1,this.limits.maxObjects),maxMetadataBytes:Math.max(1,this.limits.maxMetadataBytes),maxStreamBytes:Math.max(1,this.limits.maxStreamBytes)})]));
      const payloads=new Map<string,Uint8Array>(),facts=new Map<string,StreamFacts>();
      for(const file of assets.files)for(const stream of file.streams){
        if(facts.has(stream.locator.path))continue;
        if(this.closed)throw new Error('Inspection cancelled.');
        try{
          const allowance=Math.min(this.limits.maxStreamBytes,this.limits.maxDecodedBytes-this.decoded);
          const bytes=await managed.run(pdf.getAssociatedFileBytes({documentId:assets.documentId,fileId:file.locator.path,streamId:stream.locator.path,maxBytes:allowance}));
          this.decoded+=bytes.length;payloads.set(stream.locator.path,bytes);
          facts.set(stream.locator.path,{state:'present',size:bytes.length,md5:md5(bytes),sha256:await sha256(bytes)});
        }catch(error){facts.set(stream.locator.path,{state:'unavailable',reason:String(error)});}
      }
      const interpreted=interpret({documentId,byteLength,headerVersion,pageCount:pdf.numPages,parts,assets,streams:facts,limits:this.limits,
        parseXml:xml=>new DOMParser().parseFromString(xml,'application/xml')});
      const result:InspectedDocument={pdf,...interpreted,payloads,children:new Map(),run:managed.run};
      const collection=result.inspection.collection;
      if(collection.state==='present'){
        const children:ChildDocument[]=[];
        for(const file of result.inspection.files){
          if(!file.origins.some(o=>o.kind==='name-tree'))continue;
          for(const stream of file.streams){
            if(stream.mimeType.value!=='application/pdf')continue;
            const bytes=payloads.get(stream.locator.path),childId=bytes?'sha256:'+await sha256(bytes):null;
            let child:ChildDocument;
            if(!bytes||[...ancestors,documentId].includes(childId??'')){
              child={fileId:file.id,stream:stream.locator,documentId:childId,state:'unavailable',reason:'Child bytes unavailable or collection resource/cycle limit reached.',inspection:null};
            }else{
              this.childCount++;
              try{
                const opened=await this.open(bytes.slice(),[...ancestors,documentId],depth+1);
                result.children.set(file.id+'|'+stream.locator.path,opened);
                child={fileId:file.id,stream:stream.locator,documentId:childId,state:'present',reason:null,inspection:opened.inspection};
              }catch(error){child={fileId:file.id,stream:stream.locator,documentId:childId,state:'invalid',reason:String(error),inspection:null};}
            }
            children.push(child);
          }
        }
        result.inspection={...result.inspection,collection:{...collection,value:{...collection.value,children}}};
      }
      if(this.closed)throw new Error('Inspection cancelled.');
      return result;
    }catch(error){managed.close();this.documents.delete(managed);throw error;}
  }
}
