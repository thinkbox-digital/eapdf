import {forgetFullTextIndex} from './fulltext-index.js';
import {forgetEmbeddedIndex} from './embedded-index.js';
import {indexMessages,type MessageIndex} from './message-index.js';
import {fileLabel} from './navigation.js';
import {SupplementLinks,contentKey} from './supplement-links.js';
import {observation} from './domain/observations.js';
import {sourceMessageId} from './source-email.js';
import {loadManagedPdf,createManagedWorker} from './pdfjs-adapter.js';
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
type Entry={messageIndex?:MessageIndex;needsRefresh?:boolean;restarting?:Promise<void>;managed?:ReturnType<typeof loadManagedPdf>;blob?:Blob;parts:DocumentPartsInspection;assets:DocumentAssets;facts:Map<string,StreamFacts>;header:string|null;size:number;lastUse:number;supplementLinks?:Map<string,readonly string[]>;supplementError?:string;supplementScan?:Promise<void>};
export class BrowserSession {
  readonly limits:InspectionLimits;
  readonly extractionLimit:number;
  private decoded=0;
  private childCount=0;
  private readonly extracted=new Map<string,Uint8Array>();
  private cachedBytes=0;
  private readonly cacheLimit=32*1024**2;
  private readonly exportReads=new WeakMap<BrowserDocument,number>();
  private readonly exportWorkers=new Map<number,{worker:ReturnType<typeof createManagedWorker>;used:number}>();
  constructor(overrides:Partial<InspectionLimits>={},private readonly options:{reuseWorker?:boolean}={}) {
    const ceilings={...DEFAULT_LIMITS,maxObjects:1_000_000,maxParts:16384,maxFileBytes:4*1024**3,maxStreamBytes:32*1024**2,maxDecodedBytes:512*1024**2,maxMetadataBytes:128*1024**2,maxChildDepth:8,maxChildren:128};
    const limits={...ceilings};
    for(const key of Object.keys(overrides) as (keyof InspectionLimits)[]){
      // Retired content-count settings are accepted for compatibility, but ignored.
      if(['maxObjects','maxParts','maxTreeDepth','maxChildDepth','maxChildren'].includes(key))continue;
      const value=overrides[key]!;
      if(!Number.isSafeInteger(value)||value<0||value>ceilings[key])throw new Error(`Invalid inspection limit: ${key}`);
      limits[key]=value;
    }
    this.limits=limits;
    this.extractionLimit=this.limits.maxStreamBytes;
  }
  private readonly entries=new Map<BrowserDocument,Entry>();
  private readonly pending=new Set<ReturnType<typeof loadManagedPdf>>();
  private readonly associationWorkers=new Set<ReturnType<typeof createManagedWorker>>();
  private closed=false;
  private clock=0;
  private check=()=>{if(this.closed)throw new Error('Document processing cancelled.');};
  close():void {this.closed=true;for(const {worker} of this.exportWorkers.values())worker.close();this.exportWorkers.clear();for(const worker of this.associationWorkers)worker.close();this.associationWorkers.clear();for(const managed of this.pending)managed.close();for(const [doc,entry]of this.entries){entry.managed?.close();doc.payloads.clear();forgetEmbeddedIndex(doc);forgetFullTextIndex(doc);doc.fullTextIndex=undefined;doc.indexFileDigest=undefined;doc.embeddedIndex=undefined;doc.active=false;}this.entries.clear();this.pending.clear();this.extracted.clear();this.cachedBytes=0;}
  async open(blob:Blob,name:string,progress?:(fraction:number)=>void,parent:BrowserDocument|null=null,fileId?:string,streamPath?:string,preview?:(doc:Pick<BrowserDocument,'pdf'|'run'>)=>Promise<void>,stage?:(message:string)=>void):Promise<BrowserDocument>{
    this.check();
    if(blob.size>this.limits.maxFileBytes)throw new Error('PDF exceeds the configured input byte limit.');
    // Inventory traverses the entire archive. Range gaps restart that traversal.
    // Transfer large files once; the range transport also allocates a full-size
    // worker buffer, so this avoids repeated parsing without raising byte limits.
    const input=blob.size>=16*1024**2?new Uint8Array(await blob.arrayBuffer()):blob;
    this.check();
    if(this.pending.size>=3)throw new Error('Too many PDF operations are already pending.');
    // Reuse child workers in small batches; their allocation accounting is cumulative.
    // Separate depths keep an active collection parent alive while visiting its children.
    let shared:ReturnType<typeof createManagedWorker>|undefined;
    if(this.options.reuseWorker&&parent){
      const depth=parent.ancestorIds.length+1;let batch=this.exportWorkers.get(depth);
      if(!batch||batch.used===16){batch?.worker.close();batch={worker:createManagedWorker(),used:0};this.exportWorkers.set(depth,batch);}
      batch.used++;shared=batch.worker;
    }
    const managed=loadManagedPdf(input,shared);this.pending.add(managed);
    const hashing=sha256Blob(blob,()=>{this.check();if(managed.closed)throw new Error('Document is closed.');},progress);void hashing.catch(()=>{});
    try{
      const pdf=await managed.run(managed.task.promise,60000);this.check();
      const initialPageTimeout=pdf.numPages>=10000?120000:30000;
      if(preview)await preview({pdf,run:operation=>managed.run(operation,initialPageTimeout)});this.check();
      const hash=await hashing;this.check();
      const id='sha256:'+hash,ancestors=parent?[...parent.ancestorIds,parent.inspection.documentId]:[];
      if(ancestors.includes(id))throw new Error('This embedded PDF points back to a parent document.');
      if(parent)this.childCount++;
      // Large flat archives require a complete page and associated-file inventory.
      const inspectionTimeout=pdf.numPages>=10000?900000:60000;
      const [parts,assets]=await managed.run((async()=>{
        // Sequential scans avoid evicting each other's compressed-object working sets.
        stage?.("Reading email structure…");
        const parts=await pdf.getDocumentParts();
        stage?.("Inspecting embedded files…");
        const assets=await pdf.getDocumentAssets({maxObjects:Math.max(1,this.limits.maxObjects),maxMetadataBytes:Math.max(1,this.limits.maxMetadataBytes)});
        return [parts,assets] as const;
      })(),inspectionTimeout);
      const header=/%PDF-(\d\.\d)/.exec(new TextDecoder('latin1').decode(await blob.slice(0,1024).arrayBuffer()))?.[1]??null;
      this.check();
      const entry:Entry={managed,blob:parent?undefined:blob,parts,assets,facts:new Map(),header,size:blob.size,lastUse:++this.clock};
      stage?.("Reading email metadata…");
      const interpreted=interpret({documentId:id,byteLength:blob.size,headerVersion:header,pageCount:pdf.numPages,parts,assets,streams:entry.facts,limits:this.limits,parseXml:xml=>new DOMParser().parseFromString(xml,'application/xml')});
      const doc:BrowserDocument={pdf,...interpreted,payloads:new Map(),children:new Map(),run:managed.run,name,parent,parentFileId:fileId,parentStreamPath:streamPath,ancestorIds:ancestors,active:true};
      this.entries.set(doc,entry);this.pending.delete(managed);this.trim(doc);return doc;
    }catch(error){managed.close();this.pending.delete(managed);throw error;}
  }
  cachedMessageIndex(doc:BrowserDocument):MessageIndex|undefined{return this.entries.get(doc)?.messageIndex;}
  rememberMessageIndex(doc:BrowserDocument,index:MessageIndex|undefined):void{const entry=this.entries.get(doc);if(entry)entry.messageIndex=index;}
  adoptAssociations(doc:BrowserDocument,source:BrowserSession,copy:BrowserDocument):void {
    this.check();source.check();
    if(doc.inspection.documentId!==copy.inspection.documentId)throw new Error('Association results belong to a different PDF.');
    const target=this.entries.get(doc),entry=source.entries.get(copy);
    if(!target||!entry)throw new Error('Association document is no longer open.');
    for(const [key,fact] of entry.facts)target.facts.set(key,fact);
    target.supplementLinks=entry.supplementLinks;target.supplementError=entry.supplementError;
    target.messageIndex=entry.messageIndex??target.messageIndex;
    target.supplementScan=Promise.resolve();
    this.refresh(doc);this.applySupplementLinks(doc);
  }

  async associateFiles(doc:BrowserDocument):Promise<void>{
    this.check();let changed=false;
    for(const file of doc.inspection.files){
      if(file.owners.value||!['Source','Supplement'].includes(file.afRelationship.value??''))continue;
      for(const stream of file.streams){
        if(!/\.(eml|txt)$/i.test(file.unicodeFilename.value??file.filename.value??'')&&!/^(message\/(rfc822|global)|text\/plain)/i.test(stream.mimeType.value??''))continue;
        if(this.entries.get(doc)?.facts.has(stream.locator.path))continue;
        changed=true;
        try{await this.readBytes(doc,file,stream,false);}catch(error){
          this.check();this.entries.get(doc)?.facts.set(stream.locator.path,{state:'unavailable',reason:String(error)});
        }
      }
    }
    // Reinterpret once after collecting facts, not once per embedded email.
    if(changed||this.entries.get(doc)?.needsRefresh)this.refresh(doc);
    const entry=this.entries.get(doc)!;
    if(doc.inspection.collection.value&&doc.inspection.files.some(f=>f.afRelationship.value==='Supplement'&&!f.owners.value)){
      entry.supplementScan??=this.associateSupplements(doc);
      await entry.supplementScan;this.check();this.applySupplementLinks(doc);
    }
    const missing=doc.inspection.files.filter(f=>!f.owners.value&&['Source','Supplement'].includes(f.afRelationship.value??'')&&doc.inspection.messages.length>0);
    doc.inspection={...doc.inspection,diagnostics:[...doc.inspection.diagnostics.filter(d=>d.code!=='FILE_EMAIL_ASSOCIATION_MISSING'),...missing.map(f=>({code:'FILE_EMAIL_ASSOCIATION_MISSING',state:'unavailable' as const,message:`The PDF does not provide a unique, readable email association for ${f.unicodeFilename.value??f.filename.value??'an embedded file'}.`,evidence:[f.locator]}))]};
  }
  private async associateSupplements(doc:BrowserDocument):Promise<void>{
    const entry=this.entries.get(doc)!,index=new SupplementLinks(),targets=doc.inspection.files.filter(f=>f.afRelationship.value==='Supplement'&&!f.owners.value);
    const key=(file:AssociatedFile)=>file.streams.map(stream=>{
      const fact=entry.facts.get(stream.locator.path);
      if(fact?.state!=='present'||(stream.declaredMd5.value&&stream.declaredMd5.value!==fact.md5))throw new Error('File bytes or checksum could not be verified.');
      return contentKey(fact.size,fact.sha256);
    });
    const members=new Set(doc.inspection.collection.value!.entries.map(e=>e.fileId));
    const children=doc.inspection.files.filter(f=>members.has(f.id)&&f.afRelationship.value==='Data'&&f.streams.some(s=>s.mimeType.value==='application/pdf'||/\.pdf$/i.test(f.unicodeFilename.value??f.filename.value??'')));
    entry.supplementLinks=new Map();
    // Reuse only a complete, unambiguous scan of exactly the collection members.
    const childIds=new Set(children.map(file=>file.id)),availableIds=new Set(doc.inspection.files.map(file=>file.id));
    const pdfMembers=doc.inspection.files.filter(file=>members.has(file.id)&&(file.streams.some(stream=>/^application\/pdf(?:;|$)/i.test(stream.mimeType.value??''))||/\.pdf$/i.test(fileLabel(file))));
    let cacheable=doc.inspection.collection.state==='present'&&!doc.inspection.diagnostics.some(d=>/^XMP_.*LIMIT$/.test(d.code)||['XMP_RECORD_STRUCTURE','XMP_DISPLAY_VALUE_UNAVAILABLE','XMP_DISPLAY_VALUE_AMBIGUOUS'].includes(d.code))&&pdfMembers.length===children.length&&children.every(file=>file.streams.length===1)&&[...members].every(id=>availableIds.has(id))&&pdfMembers.every(file=>childIds.has(file.id));
    const indexed=new Map<string,MessageIndex['messages']>();let indexCharacters=0;
    try{
      if(doc.inspection.coverage.associatedFiles!=='present'||doc.inspection.coverage.metadata!=='present')throw new Error('Parent inventory is incomplete.');
      if(!children.length)throw new Error('No child PDFs are available for supplement association.');
      // All relevant children must be inspected before a unique owner is accepted.
      for(const source of doc.inspection.files.filter(f=>f.afRelationship.value==='Source')){
        for(const stream of source.streams)if(entry.facts.get(stream.locator.path)?.state!=='present')await this.readBytes(doc,source,stream,false);
        for(const content of key(source))index.source(content,source.owners.value??[]);
      }
      const deadline=performance.now()+120000;let next=0,failure:unknown;
      const inspect=async()=>{
        let worker=createManagedWorker(),processed=0;this.associationWorkers.add(worker);
        try{
          while(next<children.length&&!failure){
            this.check();if(performance.now()>deadline)throw new Error('Supplement association time limit reached.');
            if(processed++===16){worker.close();this.associationWorkers.delete(worker);worker=createManagedWorker();this.associationWorkers.add(worker);processed=1;}
            const file=children[next++]!;
            for(const stream of file.streams){
              const data=await this.readBytes(doc,file,stream,false);this.check();
              const byteLength=data.length;
              const fact=entry.facts.get(stream.locator.path);if(fact?.state!=='present')throw new Error('Child bytes unavailable.');
              const childId='sha256:'+fact.sha256;
              const managed=loadManagedPdf(data,worker);this.pending.add(managed);
              try{
                const pdf=await managed.run(managed.task.promise,60000);
                const [assets,parts]=await managed.run(Promise.all([pdf.getDocumentAssets({maxObjects:Math.max(1,this.limits.maxObjects),maxMetadataBytes:Math.max(1,this.limits.maxMetadataBytes)}),pdf.getDocumentParts()]),60000);this.check();
                const interpreted=interpret({documentId:childId,byteLength,headerVersion:null,pageCount:pdf.numPages,assets,parts,streams:new Map(),limits:this.limits,parseXml:xml=>new DOMParser().parseFromString(xml,'application/xml')});
                const child=interpreted.inspection;
                if(child.coverage.associatedFiles!=='present'||child.coverage.metadata!=='present'||parts.status==='partial')throw new Error('Child inspection is incomplete.');
                if(child.identification.declaredProfile.value!=='s'||child.messages.length!==1)throw new Error('Child is not an unambiguous single-email PDF.');
                if(cacheable){
                  const childDoc:BrowserDocument={pdf,...interpreted,run:managed.run,payloads:new Map(),children:new Map(),name:fileLabel(file),parent:doc,ancestorIds:[...doc.ancestorIds,doc.inspection.documentId],active:true};
                  const result=await indexMessages(this,childDoc,this.check,()=>{});
                  const path=[{fileId:file.id,streamPath:stream.locator.path}],name=doc.name+' / '+fileLabel(file);
                  const rows=result.messages.map(row=>({...row,path,document:name}));
                  indexCharacters+=rows.reduce((n,row)=>n+row.label.length+row.document.length+row.searchText.length,0);
                  if(result.incomplete.length||indexCharacters>8_000_000){cacheable=false;indexed.clear();}
                  else if(cacheable)indexed.set(file.id,rows);
                }
                const sources:string[]=[],supplements:string[]=[];
                for(const associated of child.files){
                  if(!['Source','Supplement'].includes(String(associated.afRelationship.value))||!associated.origins.some(o=>o.kind==='catalog-af'))continue;
                  if(associated.owners.value?.length!==1||associated.owners.value[0]!==child.messages[0]!.id)throw new Error('Child association has conflicting or unknown ownership.');
                  if(!associated.streams.length)throw new Error('Child association has no readable stream.');
                  for(const embedded of associated.streams){
                    const allowance=Math.min(this.extractionLimit,this.limits.maxDecodedBytes-this.decoded);
                    if(allowance<=0)throw new Error('Concurrent decoded-byte budget is occupied or too small during supplement association.');
                    this.decoded+=allowance;
                    try{
                    const bytes=await managed.run(pdf.getAssociatedFileBytes({documentId:assets.documentId,fileId:associated.locator.path,streamId:embedded.locator.path,maxBytes:allowance}),60000);
                    this.check();
                    if(embedded.declaredMd5.value&&embedded.declaredMd5.value.toLowerCase()!==await md5Responsive(bytes,this.check))throw new Error('Child embedded checksum mismatch.');
                    const content=contentKey(bytes.length,await sha256(bytes));this.check();
                    (associated.afRelationship.value==='Source'?sources:supplements).push(content);
                    }finally{this.decoded-=allowance;}
                  }
                }
                if(!index.child(sources,supplements))throw new Error('Child source does not identify one known parent email.');
              }finally{await managed.close();this.pending.delete(managed);}
            }
          }
        }catch(error){failure??=error;}finally{worker.close();this.associationWorkers.delete(worker);}
      };
      await Promise.all([inspect(),inspect()]);this.check();if(failure)throw failure;
      for(const file of targets){const owners=index.owners(key(file));if(owners)entry.supplementLinks.set(file.id,owners);}
      if(cacheable&&indexed.size===children.length)entry.messageIndex={messages:children.flatMap(file=>indexed.get(file.id)!),incomplete:[],notices:[],source:'scanned'};
    }catch(error){this.check();index.incomplete();entry.supplementLinks.clear();entry.supplementError=error instanceof Error?error.message:String(error);}
  }
  private applySupplementLinks(doc:BrowserDocument):void {
    const entry=this.entries.get(doc)!;
    doc.inspection={...doc.inspection,files:doc.inspection.files.map(file=>{
      const owners=entry.supplementLinks?.get(file.id);
      return owners&&!file.owners.value&&file.owners.state!=='ambiguous'?{...file,owners:observation(owners,{basis:'derived',evidence:[file.locator],reason:'Byte-identical source and supplement in the catalog associations of a single-email child PDF.'})}:file;
    })};
    if(entry.supplementError&&!doc.inspection.diagnostics.some(d=>d.code==='SUPPLEMENT_ASSOCIATION_INCOMPLETE'))doc.inspection={...doc.inspection,diagnostics:[...doc.inspection.diagnostics,{code:'SUPPLEMENT_ASSOCIATION_INCOMPLETE',state:'unavailable',message:entry.supplementError,evidence:[]}]};
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
      blob=new Blob([new Uint8Array(await this.readBytes(doc.parent,file,stream,false))]);
    }
    if(!blob)throw new Error('Original file is no longer available.');
    const input=blob.size>=16*1024**2?new Uint8Array(await blob.arrayBuffer()):blob;this.check();
    const managed=loadManagedPdf(input);this.pending.add(managed);
    try{
      const pdf=await managed.run(managed.task.promise,60000),assets=await managed.run(pdf.getDocumentAssets({maxObjects:Math.max(1,this.limits.maxObjects),maxMetadataBytes:Math.max(1,this.limits.maxMetadataBytes)}),pdf.numPages>=10000?900000:60000);this.check();
      entry.assets=assets;entry.managed=managed;entry.lastUse=++this.clock;doc.pdf=pdf;doc.run=managed.run;doc.active=true;this.trim(doc);
    }catch(error){managed.close();throw error;}finally{this.pending.delete(managed);}
  }
  async bytes(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream):Promise<Uint8Array>{
    return this.readBytes(doc,file,stream,true);
  }
  private async restartExtractionWorker(doc:BrowserDocument,failed:Entry['managed']):Promise<void>{
    this.check();const entry=this.entries.get(doc)!;
    if(entry.restarting)return entry.restarting;
    if(entry.managed!==failed)return this.ensure(doc);
    const restart=(async()=>{
      await failed?.close();this.check();entry.managed=undefined;doc.active=false;
      await this.ensure(doc);
    })();
    entry.restarting=restart;
    try{await restart;}finally{if(entry.restarting===restart)entry.restarting=undefined;}
  }
  private async readBytes(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,refresh:boolean):Promise<Uint8Array>{
    await this.entries.get(doc)?.restarting;
    await this.ensure(doc);const entry=this.entries.get(doc)!;this.check();
    const key=doc.inspection.documentId+'|'+stream.locator.path;
    const cached=this.extracted.get(key);
    if(cached){this.extracted.delete(key);this.extracted.set(key,cached);if(refresh&&entry.needsRefresh)this.refresh(doc);return cached.slice();}
    const allowance=Math.min(this.extractionLimit,this.limits.maxDecodedBytes-this.decoded);
    if(allowance<=0)throw new Error('Concurrent decoded-byte budget is occupied or too small.');
    // Reserve before awaiting, so concurrent reads cannot oversubscribe the budget.
    // Release the reservation when the read finishes; past work is not live memory.
    this.decoded+=allowance;
    try{
    const extract=()=>doc.run(doc.pdf.getAssociatedFileBytes({documentId:entry.assets.documentId,fileId:file.locator.path,streamId:stream.locator.path,maxBytes:allowance}),60000);
    const originalWorker=entry.managed;let data:Uint8Array;
    try{data=await extract();}catch(error){
      this.check();
      // Recover from allocation exhaustion without changing the stream limits.
      // Retry once in a fresh worker; keep all per-stream and worker limits intact.
      const exhausted=error instanceof Error&&error.message.includes('Worker decoded-stream allocation limit exceeded');
      if(!exhausted&&!entry.restarting&&entry.managed===originalWorker)throw error;
      await this.restartExtractionWorker(doc,originalWorker);this.check();data=await extract();
    }
    this.check();
    // Cache only successful reads within the separate retained-cache budget.
    if(data.length<=this.cacheLimit){
      const previous=this.extracted.get(key);if(previous){this.cachedBytes-=previous.length;this.extracted.delete(key);}
      while(this.cachedBytes+data.length>this.cacheLimit&&this.extracted.size){
        const oldest=this.extracted.keys().next().value!;this.cachedBytes-=this.extracted.get(oldest)!.length;this.extracted.delete(oldest);
      }
      this.extracted.set(key,data.slice());this.cachedBytes+=data.length;
    }
    entry.facts.set(stream.locator.path,{state:'present',size:data.length,md5:await md5Responsive(data,this.check),sha256:await sha256(data),sourceMessageId:['Source','Supplement'].includes(file.afRelationship.value??'')?sourceMessageId(data,file.afRelationship.value==='Supplement'):undefined});
    entry.needsRefresh=true;
    if(refresh)this.refresh(doc);
    return data;
    }finally{this.decoded-=allowance;}
  }
  private refresh(doc:BrowserDocument):void {
    const entry=this.entries.get(doc)!;
    const interpreted=interpret({documentId:doc.inspection.documentId,byteLength:entry.size,headerVersion:entry.header,pageCount:doc.pdf.numPages,parts:entry.parts,assets:entry.assets,streams:entry.facts,limits:this.limits,parseXml:xml=>new DOMParser().parseFromString(xml,'application/xml')});
    doc.inspection=interpreted.inspection;doc.details=interpreted.details;this.applySupplementLinks(doc);entry.needsRefresh=false;
  }
  /** Release a completed export child without retaining its inspection. */
  async release(doc:BrowserDocument):Promise<void> {
    if(doc.children.size)throw new Error('Release child documents before their parent.');
    const entry=this.entries.get(doc);await entry?.managed?.close();
    this.entries.delete(doc);doc.payloads.clear();forgetEmbeddedIndex(doc);forgetFullTextIndex(doc);doc.fullTextIndex=undefined;doc.indexFileDigest=undefined;doc.embeddedIndex=undefined;doc.active=false;
    if(doc.parent)doc.parent.children.delete(doc.parentFileId+'|'+doc.parentStreamPath);
  }
  async child(parent:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,refreshParent=true):Promise<BrowserDocument>{
    const key=file.id+'|'+stream.locator.path,previous=parent.children.get(key) as BrowserDocument|undefined;
    if(previous){await this.ensure(previous);return previous;}
    const bytes=await this.readBytes(parent,file,stream,refreshParent);
    if(this.options.reuseWorker){
      const reads=(this.exportReads.get(parent)??0)+1;this.exportReads.set(parent,reads%256);
      if(reads===256){
        // Extraction also charges the parent worker's cumulative allocation budget.
        // Reopen its PDF lazily, retaining the already interpreted metadata.
        const entry=this.entries.get(parent)!;await entry.managed?.close();entry.managed=undefined;parent.active=false;
      }
    }
    if(!/%PDF-\d\.\d/.test(new TextDecoder('latin1').decode(bytes.subarray(0,1024))))throw new Error('The embedded file is not a readable PDF.');
    const doc=await this.open(new Blob([new Uint8Array(bytes)]),file.unicodeFilename.value??file.filename.value??'Embedded PDF',undefined,parent,file.id,stream.locator.path);
    parent.children.set(key,doc);return doc;
  }
}
