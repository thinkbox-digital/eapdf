import type { InspectionDiagnostic, InspectionState, MetadataRecord, ObjectLocator, TextObservation } from './inspection.js';
import { observation, type Mutable } from './observations.js';
export const NS = { rdf: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#', meta: 'http://www.pdfa.org/eapdf/ns/meta/', id: 'http://www.pdfa.org/eapdf/ns/id/', pdfa: 'http://www.aiim.org/pdfa/ns/id/' };
const selected = new Set(['Mail_GUID','messageid','Message-ID','Filename','SizeInBytes','ContentType','Content-Type','CheckSum','Checksum','NumberMessages','NumberAttachments']);
const children = (element: Element, ns: string, name: string) => [...element.children].filter(e => e.namespaceURI === ns && e.localName === name);
const at = (loc: ObjectLocator, field: string): ObjectLocator => ({ ...loc, path: loc.path + '/' + field });
export function parseXmp(text: string | null, loc: ObjectLocator, parse: (source: string) => Document, unavailable = false) {
  const diagnostics: InspectionDiagnostic[] = [], records: Mutable<MetadataRecord>[] = [];
  const details = new Map<string, Record<string, string>>();
  let state: InspectionState = unavailable ? 'unavailable' : 'missing', descriptions: Element[] = [];
  const diagnostic = (code: string, state: InspectionState, message: string, where = loc) => diagnostics.push({code,state,message,evidence:[where]});
  if(unavailable&&text===null)diagnostic('METADATA_UNAVAILABLE','unavailable','Email metadata could not be read within the configured processing limits. Message lists and metadata exports are unavailable.');
  if (text !== null) {
    try {
      if (/<!DOCTYPE|<!ENTITY/i.test(text)) { state = 'unsupported'; throw new Error('DTD/entity declarations are not supported.'); }
      const document = parse(text);
      if (document.getElementsByTagName('parsererror').length) throw new Error('Catalog XMP is not well-formed XML.');
      const pending:{node:Element;depth:number}[]=[{node:document.documentElement,depth:0}];let nodes=0;
      while(pending.length){
        const item=pending.pop()!;
        if(++nodes>100000||item.depth>64){state='unavailable';throw new Error('XML node/depth limit reached.');}
        for(const node of item.node.children){
          if(pending.length+nodes>=100000){state='unavailable';throw new Error('XML node limit reached.');}
          pending.push({node,depth:item.depth+1});
        }
      }
      descriptions = [...document.getElementsByTagNameNS(NS.rdf,'RDF')].flatMap(r => children(r,NS.rdf,'Description'));
      if (!descriptions.length) throw new Error('No RDF description found.');
      state = 'present';
    } catch (error) { if (state !== 'unsupported' && state !== 'unavailable') state = 'invalid'; diagnostic('METADATA_UNREADABLE',state,String(error)); }
  }
  const scalar = (nodes: (Element | string)[], where: ObjectLocator): TextObservation => {
    const raw = nodes.map(node => typeof node === 'string' ? {kind:'xmp-attribute' as const,text:node,bytesHex:null}
      : node.children.length || node.hasAttributeNS(NS.rdf,'resource') ? {kind:'xmp-structure' as const,text:null,bytesHex:null}
        : {kind:'xmp-text' as const,text:node.textContent ?? '',bytesHex:null});
    if (raw.some(r => r.text === null)) { diagnostic('XMP_VALUE_NOT_TEXT','invalid','URI or structured value is not simple text.',where);return observation(null,{state:'invalid',raw,evidence:[where]}); }
    const values = [...new Set(raw.map(r => r.text!))];
    if (values.length > 1) { diagnostic('XMP_VALUE_AMBIGUOUS','ambiguous','Conflicting values.',where);return observation(null,{state:'ambiguous',raw,evidence:[where],candidates:values.sort()}); }
    return observation(values[0] ?? null,{raw,evidence:[where]});
  };
  const declaration = (namespace: string, name: string): TextObservation => {
    const where=at(loc,`{${namespace}}${name}`);
    if (!descriptions.length && ['invalid','unsupported','unavailable'].includes(state)) return observation(null,{state:'unavailable',evidence:[where]});
    const nodes: (Element|string)[]=[];
    for(const d of descriptions){nodes.push(...children(d,namespace,name));if(d.hasAttributeNS(namespace,name))nodes.push(d.getAttributeNS(namespace,name)!);}
    return scalar(nodes,where);
  };
  // Reading a label is not validation. Preserve one explicit text value, including
  // the RDF qualified-property form, without flattening arrays or qualifiers.
  const displayText = (nodes: (Element|string)[], where: ObjectLocator, name:string):string|null => {
    const values=nodes.map(node=>{
      if(typeof node==='string')return node;
      if(node.hasAttributeNS(NS.rdf,'resource'))return null;
      if(!node.children.length)return node.hasAttributeNS(NS.rdf,'parseType')?null:node.textContent??'';
      const nested=children(node,NS.rdf,'Description');
      const resource=nested.length===1&&node.children.length===1?nested[0]!:
        node.getAttributeNS(NS.rdf,'parseType')==='Resource'?node:null;
      if(!resource)return null;
      const value=children(resource,NS.rdf,'value');
      return value.length===1&&!value[0]!.children.length&&!value[0]!.hasAttributeNS(NS.rdf,'resource')&&
        !value[0]!.hasAttributeNS(NS.rdf,'parseType')?value[0]!.textContent??'':null;
    });
    if(values.some(value=>value===null)){
      diagnostic('XMP_DISPLAY_VALUE_UNAVAILABLE','unavailable',`${name} could not be read as one text value. No value was inferred.`,where);return null;
    }
    const unique=[...new Set(values)];
    if(unique.length!==1){
      diagnostic('XMP_DISPLAY_VALUE_AMBIGUOUS','ambiguous',`Conflicting ${name} values were found. No value was selected.`,where);return null;
    }
    return unique[0]!;
  };
  const readRecord = (elements: Element[], container: string, index: number, kind: MetadataRecord['kind']) => {
    const where=at(loc,`${container}[${index}]`),id=loc.documentId+'#'+where.path;
    const groups = new Map<string,(Element|string)[]>();
    for (const record of elements) {
      for(const attr of record.attributes)if(attr.namespaceURI===NS.meta)groups.set(attr.localName,[...(groups.get(attr.localName)??[]),attr.value]);
      for(const node of record.children)if(node.namespaceURI===NS.meta&&!['email','assets','attachments'].includes(node.localName))groups.set(node.localName,[...(groups.get(node.localName)??[]),node]);
    }
    const fields: Record<string,TextObservation>=Object.create(null),display: Record<string,string>=Object.create(null);
    for(const [key,nodes] of groups){
      if(selected.has(key))fields[key]=scalar(nodes,at(where,key));
      if(kind==='email'&&['Subject','Raw-From','Raw-To','Sent','Raw-Cc','Raw-Bcc'].includes(key)){
        const value=displayText(nodes,at(where,key),key);if(value!==null)display[key]=value;
      }
      if(['From','To','Cc','Bcc'].includes(key)) {
        const foaf='http://xmlns.com/foaf/0.1/';
        display[key]=nodes.flatMap(n=>typeof n==='string'?[]:[...n.getElementsByTagNameNS(NS.rdf,'li')].map(li=>{
          const name=li.getElementsByTagNameNS(foaf,'name')[0]?.textContent??'';
          const address=li.getElementsByTagNameNS(foaf,'mbox')[0];
          const mailbox=(address?.getAttributeNS(NS.rdf,'resource')??address?.textContent??'').replace(/^mailto:/i,'');
          return mailbox?`${name} <${mailbox}>`:name;
        })).filter(Boolean).join(', ');
      }
    }
    records.push({id,kind,locator:where,fields,uninspectedFields:[...groups.keys()].filter(k=>!selected.has(k)).sort(),owners:observation<readonly string[]>(null,{state:'not-evaluated'}),files:observation<readonly string[]>(null,{state:'not-evaluated'})});
    details.set(id,display);
  };
  for(const [container,kind] of [['email','email'],['assets','source-asset'],['attachments','attachment']] as const){
    let index=0;
    for(const desc of descriptions)for(const prop of children(desc,NS.meta,container)){
      const bags=children(prop,NS.rdf,'Bag');
      if(bags.length!==1){state='unsupported';diagnostic('XMP_RECORD_STRUCTURE','unsupported','Expected RDF Bag.');continue;}
      for(const li of bags[0]!.children){
        if(index>=10000){state='unavailable';diagnostic('XMP_RECORD_LIMIT','unavailable','Metadata record limit reached.');break;}
        readRecord([children(li,NS.rdf,'Description')[0]??li],container,index++,kind);
      }
    }
  }
  // Some single-message producers put email fields directly on the document
  // RDF subject. Preserve those explicit fields; never invent a sole-message GUID.
  if(!records.some(r=>r.kind==='email')&&!descriptions.some(d=>children(d,NS.meta,'email').length)) {
    const profile=declaration(NS.id,'conformance');
    const roots=descriptions.filter(d=>(d.getAttributeNS(NS.rdf,'about')??'')==='');
    const hasIdentity=roots.some(d=>['Mail_GUID','Message-ID','messageid'].some(k=>children(d,NS.meta,k).length||d.hasAttributeNS(NS.meta,k)));
    if(['s','si'].includes(profile.value??'')&&hasIdentity)readRecord(roots,'document-email',0,'email');
  }
  return {state,records,details,diagnostics,declaration};
}
