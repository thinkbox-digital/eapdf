// Pure interpretation of generic API records. No PDF parser or PDF.js runtime import.
import type { DocumentAssets, AssetValue } from '../pdfjs-assets.js';
import type { DocumentPartsInspection, PartLocator } from '../pdfjs-document-parts.js';
import type { AssociatedFile, DeclaredProfile, DocumentPart, EmbeddedStream, InspectionDiagnostic, InspectionLimits, InspectionResult, MailMessage, MetadataRecord, NumberObservation, ObjectLocator, ProfileObservation, RawValue, TextObservation } from './inspection.js';
import { observation as obs, links, type Mutable } from './observations.js';
import { NS, parseXmp } from './xmp.js';

export const DEFAULT_LIMITS: InspectionLimits = Object.freeze({maxFileBytes:32*1024**2,maxStreamBytes:16*1024**2,maxDecodedBytes:64*1024**2,maxMetadataBytes:2*1024**2,maxObjects:100000,maxParts:2048,maxTreeDepth:32,maxChildDepth:4,maxChildren:32});
export type StreamFacts = {state:'present';size:number;md5:string;sha256:string;sourceMessageId?:string}|{state:'unavailable';reason:string};
export type InterpretationInput = { documentId:string;byteLength:number;headerVersion:string|null;pageCount:number;parts:DocumentPartsInspection;assets:DocumentAssets;
  streams:ReadonlyMap<string,StreamFacts>;parseXml:(source:string)=>Document;limits:InspectionLimits };
const field = (record: MetadataRecord, ...keys:string[]):TextObservation => {
  const found=keys.map(k=>record.fields[k]).filter((v):v is TextObservation=>!!v);
  if(!found.length)return obs();if(found.length===1)return found[0]!;
  if(found.every(f=>f.state==='present'&&f.value===found[0]!.value))return found[0]!;
  return obs(null,{state:'ambiguous',raw:found.flatMap(f=>f.raw),candidates:found.flatMap(f=>f.value===null?[]:[f.value]),reason:'Identity aliases disagree.'});
};
export function interpret(input:InterpretationInput):{inspection:InspectionResult;details:Map<string,Record<string,string>>}{
  const {documentId,assets,limits}=input;
  const loc=(l:PartLocator):ObjectLocator=>({documentId,objectNumber:l.ref?.num??null,generation:l.ref?.gen??null,path:l.path});
  const id=(l:PartLocator)=>documentId+'#'+l.path;
  const diagnostics:InspectionDiagnostic[]=[];
  const diagnostic=(code:string,state:InspectionDiagnostic['state'],message:string,where:ObjectLocator)=>diagnostics.push({code,state,message,evidence:[where]});
  const scalar=(v:AssetValue,where:ObjectLocator,expected:'string'|'name'='string'):TextObservation=>{
    if(v.kind==='missing')return obs(null,{evidence:[where]});
    const raw:RawValue={kind: v.kind==='string'?'pdf-string':v.kind==='name'?'pdf-name':v.kind==='number'?'pdf-real':v.kind==='boolean'?'pdf-boolean':'pdf-null',
      text:v.value===undefined?null:(v.kind==='name'?'/':'')+String(v.value),bytesHex:v.bytesHex===undefined?null:(v.kind==='name'?'2f':'')+v.bytesHex};
    return v.kind===expected&&typeof v.value==='string'?obs(v.value,{raw:[raw],evidence:[where]}):obs(null,{state:v.kind==='unavailable'?'unavailable':'invalid',raw:[raw],evidence:[where]});
  };
  const number=(v:AssetValue,where:ObjectLocator):NumberObservation=>v.kind==='missing'?obs<number>():
    v.kind==='number'&&typeof v.value==='number'&&Number.isSafeInteger(v.value)&&v.value>=0?obs(v.value,{evidence:[where],raw:[{kind:'pdf-integer',text:String(v.value),bytesHex:null}]}):obs<number>(null,{state:'invalid',evidence:[where]});
  const cat=assets.catalogMetadata,meta=assets.metadata.find(m=>m.locator.path===cat?.path);
  const metaLoc=cat?loc(cat):{documentId,objectNumber:null,generation:null,path:'catalog/Metadata'};
  const xmp=parseXmp(meta?.text??null,metaLoc,input.parseXml,meta?.state==='unavailable');
  diagnostics.push(...xmp.diagnostics);
  const messages:Mutable<MailMessage>[]=xmp.records.filter(r=>r.kind==='email').map(r=>({id:r.id,documentId,metadataRecordId:r.id,
    mailGuid:field(r,'Mail_GUID'),internetMessageId:field(r,'messageid','Message-ID'),partIds:obs<readonly string[]>(),
    sourceAssetIds:obs<readonly string[]>(null,{state:'not-evaluated',reason:'No original-email parser or implicit source association.'}),attachmentRecordIds:[]}));
  const byGuid=new Map<string,typeof messages>(),byMid=new Map<string,typeof messages>();
  for(const message of messages)for(const [key,index] of [[message.mailGuid.value,byGuid],[message.internetMessageId.value,byMid]] as const)
    if(key!==null){const group=index.get(key)??[];group.push(message);index.set(key,group);}
  const owner=(guid:TextObservation,mid:TextObservation,where:ObjectLocator,allowMid=false)=>{
    if(guid.state!=='present'&&guid.state!=='missing')return obs<readonly string[]>(null,{state:'unavailable',reason:'Unusable GUID blocks identity fallback.',evidence:[where]});
    const matches=guid.value?(byGuid.get(guid.value)??[]):guid.state==='missing'&&allowMid&&mid.value?(byMid.get(mid.value)??[]):[];
    const result=links(matches.map(m=>m.id),[where],true,'Exact case-sensitive identity matching; no sole-message guess.');
    if(result.state==='ambiguous')diagnostic('AMBIGUOUS_MESSAGE_IDENTITY','ambiguous','More than one message matches the declared identity.',where);
    return result;
  };
  const parts:Mutable<DocumentPart>[]=[];
  for(const generic of input.parts.parts){
    const where=loc(generic.locator),dpm:Record<string,TextObservation>=Object.create(null);
    for(const entry of generic.dpm.entries){
      const identity=['Mail_GUID','Mail_MessageID'].includes(entry.key);
      dpm[entry.key]=scalar(entry.value,{...where,path:where.path+'/DPM/'+entry.key},!identity&&entry.value.kind==='name'?'name':'string');
      if(identity&&dpm[entry.key]!.state==='invalid')diagnostic('INVALID_MESSAGE_IDENTITY','invalid','DPM identity must be a PDF string.',where);
    }
    const parent=parts.find(p=>p.id===(generic.parent?id(generic.parent):null));
    const guid=dpm.Mail_GUID??obs(),mid=dpm.Mail_MessageID??obs();
    let owners=guid.state!=='missing'||mid.state!=='missing'?owner(guid,mid,where):parent?.messageIds??obs<readonly string[]>();
    if(input.parts.diagnostics.some(d=>d.code==='PARENT_MISMATCH'&&d.locator.path===generic.locator.path))owners=obs<readonly string[]>(null,{state:'unavailable',reason:'DPart ancestry is inconsistent.'});
    const ref=(r:{ref:{num:number;gen:number}|null})=>obs<readonly string[]>(r.ref?[documentId+`#object:${r.ref.num}:${r.ref.gen}`]:null,{evidence:[where]});
    parts.push({id:id(generic.locator),locator:where,parentId:generic.parent?id(generic.parent):null,declaredParent:ref(generic.declaredParent),childIds:generic.children.map(id),dpm,
      startReference:ref(generic.start),endReference:ref(generic.end),pageRange:generic.pageRange.state==='present'?obs({indexBase:0 as const,endInclusive:true as const,start:generic.pageRange.start!,end:generic.pageRange.end!},{basis:'derived',evidence:[where]}):obs(null,{state:generic.pageRange.state,evidence:[where]}),messageIds:owners});
  }
  const files:Mutable<AssociatedFile>[]=assets.files.map(f=>{
    const where=loc(f.locator);
    const streams:EmbeddedStream[]=f.streams.map(s=>{
      const sloc=loc(s.locator),facts=input.streams.get(s.locator.path);
      const hash=s.declaredChecksum.kind==='string'&&s.declaredChecksum.bytesHex?.length===32?s.declaredChecksum.bytesHex:null;
      const computed=<T>(value:T|null)=>facts?.state==='present'?obs<T>(value,{basis:'derived',evidence:[sloc]}):obs<T>(null,{state:'unavailable',reason:facts?.state==='unavailable'?facts.reason:'Embedded bytes unavailable.',evidence:[sloc]});
      if(hash&&facts?.state==='present'&&hash!==facts.md5)diagnostic('CHECKSUM_MISMATCH','invalid','Declared checksum differs from embedded bytes.',sloc);
      return {locator:sloc,efKeys:s.efKeys,mimeType:scalar(s.subtype,sloc,'name'),mediaTypeParameters:scalar(s.parameters,sloc),declaredSize:number(s.declaredSize,sloc),
        declaredMd5:hash?obs(hash,{raw:scalar(s.declaredChecksum,sloc).raw,evidence:[sloc]}):obs(null,{state:s.declaredChecksum.kind==='missing'?'missing':'invalid',evidence:[sloc]}),
        decodedSize:computed(facts?.state==='present'?facts.size:null),computedMd5:computed(facts?.state==='present'?facts.md5:null),sha256:computed(facts?.state==='present'?facts.sha256:null),
        extraction:facts?.state==='present'?'present':'unavailable'};
    });
    return {id:id(f.locator),locator:where,filename:scalar(f.filename,{...where,path:where.path+'/F'}),unicodeFilename:scalar(f.unicodeFilename,{...where,path:where.path+'/UF'}),afRelationship:scalar(f.relationship,{...where,path:where.path+'/AFRelationship'},'name'),
      origins:f.origins.map(o=>({kind:o.kind,locator:loc(o.locator),pageIndex:o.pageIndex,nameTreeKey:scalar(o.nameTreeKey,loc(o.locator))})),embedding:f.embedding,streams,
      roles:obs<readonly string[]>(null,{state:'unavailable'}),owners:obs<readonly string[]>(null,{state:'unavailable'}),metadataRecordIds:[]};
  });
  for(const record of xmp.records){
    if(record.kind==='email'){record.owners=obs([record.id],{basis:'derived',evidence:[record.locator]});continue;}
    record.owners=record.kind==='attachment'?owner(field(record,'Mail_GUID'),field(record,'Message-ID'),record.locator,true):obs<readonly string[]>(null,{state:'unavailable',reason:'Source metadata does not supply a mandatory message link.'});
    const checksum=field(record,'CheckSum','Checksum'),size=field(record,'SizeInBytes').value,filename=field(record,'Filename').value;
    const matches=files.filter(f=>f.streams.some(s=>size!==null&&/^\+?\d+$/.test(size)&&Number(size)===(s.decodedSize.value??s.declaredSize.value)&&
      (checksum.value!==null?/^[0-9a-fA-F]{32}$/.test(checksum.value)&&checksum.value.toLowerCase()===(s.computedMd5.value??s.declaredMd5.value):filename!==null&&[f.filename.value,f.unicodeFilename.value].includes(filename))));
    for(const f of matches)f.metadataRecordIds=[...f.metadataRecordIds,record.id];
    record.files=matches.length&&checksum.value===null?obs(null,{state:'unavailable',candidates:matches.map(f=>[f.id]),reason:'Filename and size are candidates, not a proven physical binding.'}):links(matches.map(f=>f.id),[record.locator],true);
    if(record.files.state==='ambiguous')diagnostic('AMBIGUOUS_FILE_BINDING','ambiguous','Several physical files match one metadata record.',record.locator);
  }
  for(const f of files){
    const records=xmp.records.filter(r=>f.metadataRecordIds.includes(r.id)&&r.files.value?.length===1&&r.files.value[0]===f.id);
    f.roles=links(records.map(r=>r.kind),[f.locator]);
    const explicitParts=f.origins.filter(o=>o.kind==='object-af').flatMap(o=>parts.filter(p=>p.locator.objectNumber!==null&&p.locator.objectNumber===o.locator.objectNumber&&p.locator.generation===o.locator.generation));
    if(explicitParts.length){
      const owners=new Set(explicitParts.flatMap(p=>p.messageIds.value??[]));
      f.owners=explicitParts.every(p=>p.messageIds.value?.length)?links([...owners],[f.locator]):obs(null,{state:'unavailable',reason:'File association refers to an unidentified document part.'});
      continue;
    }
    const contexts=f.origins.filter(o=>o.kind==='annotation'),owners=new Set<string>();let unresolved=false;
    if(contexts.length){
      for(const o of contexts){
        const candidates=new Set<string>();let valid=o.pageIndex!==null;
        for(const p of parts){const range=p.pageRange.value;if(p.pageRange.state==='invalid')valid=false;
          if(range&&o.pageIndex!==null&&range.start<=o.pageIndex&&range.end>=o.pageIndex){if(p.messageIds.value===null)valid=false;else p.messageIds.value.forEach(i=>candidates.add(i));}}
        if(!valid||candidates.size!==1)unresolved=true;candidates.forEach(i=>owners.add(i));
      }
      f.owners=unresolved?obs(null,{state:owners.size>1?'ambiguous':'unavailable',candidates:[...owners].map(i=>[i]),reason:'Not every annotation context has a unique message owner.'}):links([...owners],[f.locator]);
    }else{records.forEach(r=>r.owners.value?.forEach(i=>owners.add(i)));if(owners.size)f.owners=links([...owners],[f.locator]);}
  }
  // A single-email profile identifies the document itself as one email. Catalog
  // associations therefore have document scope, without relying on a filename.
  if(['s','si'].includes(xmp.declaration(NS.id,'conformance').value??'')&&messages.length===1)
    for(const f of files)if(!f.owners.value&&f.owners.state!=='ambiguous'&&f.origins.some(o=>o.kind==='catalog-af')&&!f.origins.some(o=>o.kind==='annotation'||o.kind==='object-af'))
      f.owners=obs([messages[0]!.id],{basis:'derived',evidence:[f.locator],reason:'Catalog association in a single-email document.'});
  // An exact unique Message-ID read from a Source file provides evidence after
  // explicit extraction. Duplicate identities remain unresolved.
  for(const file of files)if(['Source','Supplement'].includes(file.afRelationship.value??'')&&!file.owners.value&&file.owners.state!=='ambiguous'){
    const ids=new Set(file.streams.flatMap(stream=>{const fact=input.streams.get(stream.locator.path);return fact?.state==='present'&&fact.sourceMessageId?[fact.sourceMessageId]:[];}));
    const verified=file.streams.every(stream=>{const fact=input.streams.get(stream.locator.path);return fact?.state==='present'&&fact.sourceMessageId&&(!stream.declaredMd5.value||stream.declaredMd5.value===fact.md5);});
    if(verified&&ids.size===1){const candidates=byMid.get([...ids][0]!)??[];if(candidates.length===1){file.owners=obs([candidates[0]!.id],{basis:'derived',evidence:[file.locator],reason:'Exact unique Message-ID in extracted email source.'});}}
  }
  for(const message of messages){message.partIds=obs(parts.filter(p=>p.messageIds.value?.includes(message.id)).map(p=>p.id),{basis:'derived'});
    const sources=files.filter(f=>f.afRelationship.value==='Source'&&f.owners.value?.includes(message.id));
    if(sources.length)message.sourceAssetIds=obs(sources.map(f=>f.id),{basis:'derived',evidence:sources.map(f=>f.locator)});
    message.attachmentRecordIds=xmp.records.filter(r=>r.kind==='attachment'&&r.owners.value?.includes(message.id)).map(r=>r.id);}
  const col=assets.collection;
  const folders=col.folders.map(f=>({id:id(f.locator),locator:loc(f.locator),folderNumber:number(f.number,loc(f.locator)),name:scalar(f.name,loc(f.locator)),parentId:f.parent?documentId+'#'+f.parent:null,childIds:f.children.map(p=>documentId+'#'+p)}));
  const entries=files.flatMap(f=>f.origins.filter(o=>o.kind==='name-tree').map(o=>{
    const match=/^<(\d+)>/.exec(o.nameTreeKey.value??''),number=match?Number(match[1]):folders[0]?.folderNumber.value;
    return {fileId:f.id,nameTreeKey:o.nameTreeKey,folderIds:!folders.length&&!match?obs<readonly string[]>([],{basis:'derived'}):links(folders.filter(f=>f.folderNumber.value===number).map(f=>f.id),[o.locator],true)};
  }));
  const collection:InspectionResult['collection']=col.state==='present'&&col.locator?obs({locator:loc(col.locator),view:scalar(col.view,loc(col.locator),'name'),schemaKeys:col.schemaKeys,folders,entries,children:[]},{evidence:[loc(col.locator)]}):obs(null,{state:col.state});
  const claim=xmp.declaration(NS.id,'conformance'),known=['s','si','m','mi','c','ci'];
  let profile:ProfileObservation;
  if(claim.state==='present'&&!known.includes(claim.value)){profile=obs<DeclaredProfile>(null,{state:'unsupported',raw:claim.raw,evidence:claim.evidence,reason:'Unknown profile declaration.'});diagnostic('UNKNOWN_PROFILE','unsupported','Unrecognized declared EA-PDF profile.',metaLoc);}
  else profile=claim as ProfileObservation;
  for(const d of [...input.parts.diagnostics,...assets.diagnostics])diagnostic(d.code,d.code.includes('LIMIT')||d.code.includes('UNAVAILABLE')?'unavailable':'invalid','Inspection reported '+d.code.replaceAll('_',' ').toLowerCase()+'.',loc(d.locator));
  const header=obs(input.headerVersion,{raw:input.headerVersion?[{kind:'header',text:input.headerVersion,bytesHex:null}]:[]});
  const catalog=scalar(assets.catalogVersion,{documentId,objectNumber:null,generation:null,path:'catalog/Version'},'name');
  let effective=header;
  if(catalog.state==='present'&&/^\d\.\d$/.test(catalog.value))effective=obs([header.value??'0.0',catalog.value].sort((a,b)=>Number(a)-Number(b)).at(-1)!,{basis:'derived'});
  else if(catalog.state!=='missing'&&catalog.state!=='present')effective=obs(null,{state:'invalid'});
  const inspection:InspectionResult={schemaVersion:1,documentId,source:{sha256:documentId.slice(7),byteLength:input.byteLength},identification:{pdfHeaderVersion:header,pdfCatalogVersion:catalog,effectivePdfVersion:effective,
    pdfaPart:xmp.declaration(NS.pdfa,'part'),pdfaConformance:xmp.declaration(NS.pdfa,'conformance'),pdfaRevision:xmp.declaration(NS.pdfa,'rev'),
    eapdfVersion:xmp.declaration(NS.id,'version'),eapdfRevision:xmp.declaration(NS.id,'rev'),declaredProfile:profile,validationStatus:'not-evaluated'},pageCount:input.pageCount,parts,messages,files,metadataRecords:xmp.records,collection,diagnostics,limits,
    coverage:{metadata:xmp.state,documentParts:input.parts.status==='partial'?(input.parts.diagnostics.some(d=>d.code.includes('LIMIT'))?'unavailable':'invalid'):input.parts.status,
      associatedFiles:assets.diagnostics.some(d=>d.code==='OBJECT_LIMIT')?'unavailable':'present',collection:assets.diagnostics.some(d=>d.code==='FOLDER_CYCLE')?'invalid':col.state,
      fullEmailHeaders:'not-evaluated',logicalStructure:'not-evaluated',sourceEmailParsing:'not-evaluated',contentStreamAssociations:'not-evaluated',conformance:'not-evaluated'}};
  return {inspection,details:xmp.details};
}
