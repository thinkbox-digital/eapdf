import type {BrowserDocument,BrowserSession} from './browser-session.js';
import {fileLabel} from './navigation.js';

export type XmpPacket={name:string;document:string;locator:string;owners:readonly string[];bytes:Uint8Array;text:string|null};
export type XmpExport={packets:XmpPacket[];issues:string[]};
const LIMIT=64*1024**2;
const CRC_TABLE=Uint32Array.from({length:256},(_,value)=>{for(let bit=0;bit<8;bit++)value=(value>>>1)^((value&1)?0xedb88320:0);return value>>>0;});
export async function collectXmp(root:BrowserDocument,session:BrowserSession,check:()=>void,progress:(text:string)=>void):Promise<XmpExport>{
  const packets:XmpPacket[]=[],issues:string[]=[];let total=0;
  async function visit(doc:BrowserDocument,path:string):Promise<void>{
    check();progress(`Reading metadata: ${path}`);await session.ensure(doc);check();
    const assets=await doc.run(doc.pdf.getDocumentAssets());check();
    for(const diagnostic of assets.diagnostics)issues.push(`${path}: ${diagnostic.code} at ${diagnostic.locator.path}`);
    for(const metadata of assets.metadata){
      check();
      try{
        const bytes=await doc.run(doc.pdf.getAssociatedFileBytes({documentId:assets.documentId,metadataId:metadata.locator.path,maxBytes:Math.min(32*1024**2,LIMIT-total)}));check();
        total+=bytes.length;if(total>LIMIT)throw new Error('Metadata exceeds the 64 MiB export budget.');
        packets.push({name:`metadata-${String(packets.length+1).padStart(4,'0')}.xmp`,document:path,locator:metadata.locator.path,owners:metadata.owners.map(o=>o.path),bytes,text:metadata.text});
      }catch(error){check();issues.push(`${path} · ${metadata.locator.path}: ${error instanceof Error?error.message:String(error)}`);}
    }
    const collection=doc.inspection.collection;
    if(!['present','missing'].includes(collection.state))issues.push(`${path}: collection could not be fully inspected.`);
    if(!collection.value)return;
    const memberIds=new Set(collection.value.entries.map(e=>e.fileId));
    const visited=new Set<string>();
    for(const file of doc.inspection.files){
      if(!memberIds.has(file.id))continue;
      if(!file.streams.length&&/\.pdf$/i.test(fileLabel(file)))issues.push(`${path}: missing embedded PDF ${fileLabel(file)}.`);
      for(const stream of file.streams){
        if(stream.mimeType.value?.split(';')[0]?.toLowerCase()!=='application/pdf'&&!/\.pdf$/i.test(fileLabel(file)))continue;
        if(visited.has(stream.locator.path))continue;visited.add(stream.locator.path);
        let child:BrowserDocument|undefined;
        try{child=await session.child(doc,file,stream,false);check();await visit(child,path+' / '+child.name);}
        catch(error){check();issues.push(`${path} / ${fileLabel(file)}: ${error instanceof Error?error.message:String(error)}`);}
        finally{if(child)session.release(child);}
      }
    }
  }
  await visit(root,root.name);check();return {packets,issues:[...new Set(issues)]};
}

// Store ZIP entries without rewriting XML, preserving each decoded stream byte for byte.
export function metadataZip(result:XmpExport,selected:readonly XmpPacket[]):Blob{
  if(!selected.length)throw new Error('Select at least one metadata stream.');
  const manifest={format:'EA-PDF metadata export',complete:result.issues.length===0&&selected.length===result.packets.length,
    selection:selected.length===result.packets.length?'all discovered streams':'selected streams',issues:result.issues,
    streams:selected.map(({name,document,locator,owners,bytes})=>({file:name,document,locator,owners,size:bytes.length}))};
  const entries=[{name:'manifest.json',bytes:new TextEncoder().encode(JSON.stringify(manifest,null,2)+'\n')},...selected];
  const chunks:Uint8Array[]=[],directory:Uint8Array[]=[];let offset=0,centralSize=0;
  for(const entry of entries){
    const name=new TextEncoder().encode(entry.name),data=entry.bytes;let crc=0xffffffff;
    for(const byte of data)crc=(crc>>>8)^CRC_TABLE[(crc^byte)&255]!;crc=(crc^0xffffffff)>>>0;
    const local=new Uint8Array(30+name.length),l=new DataView(local.buffer);l.setUint32(0,0x04034b50,true);l.setUint16(4,20,true);l.setUint16(6,0x800,true);l.setUint16(12,33,true);l.setUint32(14,crc,true);l.setUint32(18,data.length,true);l.setUint32(22,data.length,true);l.setUint16(26,name.length,true);local.set(name,30);
    const central=new Uint8Array(46+name.length),c=new DataView(central.buffer);c.setUint32(0,0x02014b50,true);c.setUint16(4,20,true);c.setUint16(6,20,true);c.setUint16(8,0x800,true);c.setUint16(14,33,true);c.setUint32(16,crc,true);c.setUint32(20,data.length,true);c.setUint32(24,data.length,true);c.setUint16(28,name.length,true);c.setUint32(42,offset,true);central.set(name,46);
    chunks.push(local,new Uint8Array(data));directory.push(central);offset+=local.length+data.length;centralSize+=central.length;
  }
  if(entries.length>65535)throw new Error('Too many metadata streams for this ZIP export.');
  const end=new Uint8Array(22),e=new DataView(end.buffer);e.setUint32(0,0x06054b50,true);e.setUint16(8,entries.length,true);e.setUint16(10,entries.length,true);e.setUint32(12,centralSize,true);e.setUint32(16,offset,true);
  return new Blob([...chunks,...directory,end].map(b=>new Uint8Array(b)),{type:'application/zip'});
}
