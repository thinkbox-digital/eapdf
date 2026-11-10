import type {InspectionResult,AssociatedFile} from './domain/inspection.js';
import {fileLabel} from './navigation.js';
export type Scalar=string|number|boolean|null;
export type Values=readonly (readonly [string,Scalar,Scalar])[];
export type PortfolioField={key:string;label:string;subtype:string;order:number|null;visible:boolean};
export type PortfolioEntry={key:string;keyHex:string;filePath:string;name:Scalar;description:Scalar;created:Scalar;modified:Scalar;size:Scalar;compressedSize:Scalar;values:Values};
export type PortfolioFolder={thumbnail?:boolean;path:string;parent:string|null;number:Scalar;name:Scalar;description:Scalar;created:Scalar;modified:Scalar;values:Values};
export type PortfolioData={initial:string|null;initialHex:string|null;view:string;layouts:Scalar[];fields:PortfolioField[];sort:Scalar[];ascending:Scalar[];ascendingAll?:boolean;folders:PortfolioFolder[];entries:PortfolioEntry[];split:{direction:Scalar;position:Scalar}|null;colors:[string,Scalar[]][];notices:string[]};
export type Detection={kind:'eapdf'|'portfolio'|'pdf';collection:boolean;notice:string};
export function detectDocument(inspection:InspectionResult):Detection {
  const id=inspection.identification;
  const recognized=id.eapdfVersion.state==='present'&&id.eapdfVersion.value==='1'&&id.declaredProfile.state==='present'&&['s','si','m','mi','c','ci'].includes(id.declaredProfile.value??'');
  const collection=inspection.collection.state==='present';
  const declared=[id.eapdfVersion,id.eapdfRevision,id.declaredProfile].some(v=>v.state!=='missing'&&v.state!=='unavailable');
  return {kind:recognized?'eapdf':collection?'portfolio':'pdf',collection,notice:recognized?'':declared?'EA-PDF identification is incomplete or unsupported; showing the available PDF content.':inspection.collection.state==='invalid'?'The Collection dictionary is invalid; showing the available PDF content.':''};
}
export type PortfolioRow={id:string;folder:string|null;kind:'file'|'folder';name:string;file?:AssociatedFile;entry?:PortfolioEntry;directory?:PortfolioFolder};
const defaults:PortfolioField[]=[{key:'F',label:'Name',subtype:'F',order:0,visible:true},{key:'Desc',label:'Description',subtype:'Desc',order:1,visible:true},{key:'Size',label:'Size',subtype:'Size',order:2,visible:true},{key:'ModDate',label:'Modified',subtype:'ModDate',order:3,visible:true}];
export function pdfDate(value:Scalar):number|null {
  if(typeof value!=='string')return null;
  const m=/^(?:D:)?(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?(Z|[+-])?(\d{2})?'?(\d{2})?'?$/.exec(value);if(!m)return null;
  const month=Number(m[2]??1),day=Number(m[3]??1),hour=Number(m[4]??0),minute=Number(m[5]??0),second=Number(m[6]??0);
  if(month<1||month>12||day<1||day>31||hour>23||minute>59||second>59||Number(m[8]??0)>23||Number(m[9]??0)>59||(m[7]==='Z'&&m[8]!==undefined))return null;
  const d=new Date(0);d.setUTCFullYear(Number(m[1]),month-1,day);d.setUTCHours(hour,minute,second,0);
  if(d.getUTCMonth()!==month-1||d.getUTCDate()!==day)return null;
  return d.getTime()+(m[7]==='+'?-1:m[7]==='-'?1:0)*(Number(m[8]??0)*60+Number(m[9]??0))*60000;
}
export class PortfolioModel {
  readonly fields:PortfolioField[];readonly rows:PortfolioRow[]=[];readonly notices:string[];readonly root:string|null;
  constructor(readonly data:PortfolioData,inspection:InspectionResult){
    this.notices=[...data.notices];
    if(!['D','T','H','C'].includes(data.view)||(data.view==='C'&&!data.layouts.some(v=>typeof v==='string'&&['D','T','H','FilmStrip','FreeForm','Linear','Tree'].includes(v))))this.notices.push('The requested Portfolio layout is unsupported; showing Details.');
    this.fields=(data.fields.length?data.fields:defaults).map((f,i)=>({...f,label:typeof f.label==='string'?f.label:f.key,order:typeof f.order==='number'?f.order:i})).sort((a,b)=>(a.order??0)-(b.order??0));
    // File size remains available even when a collection omits it from its schema.
    if(!this.fields.some(f=>f.subtype==='Size')){let key='Size';while(this.fields.some(f=>f.key===key))key='_'+key;this.fields.push({key,label:'Size',subtype:'Size',order:this.fields.length,visible:false});}
    this.root=data.folders[0]?.path??null;
    const folders=new Map(data.folders.map(f=>[f.path,f])),numbers=new Map<number,string|null>();
    for(const f of data.folders){if(typeof f.number==='number'&&Number.isSafeInteger(f.number)&&f.number>=0)numbers.set(f.number,numbers.has(f.number)?null:f.path);}
    for(const f of data.folders){if(f.path===this.root)continue;const seen=new Set([f.path]);let p=f.parent,valid=true;while(p){if(seen.has(p)||!folders.has(p)){valid=false;break;}seen.add(p);p=folders.get(p)!.parent;}if(!valid)this.notices.push('A broken folder hierarchy was placed at the collection root.');this.rows.push({id:f.path,folder:valid?f.parent:this.root,kind:'folder',name:typeof f.name==='string'?f.name:'Unnamed folder',directory:f});}
    const files=new Map(inspection.files.map(f=>[f.locator.path,f])),keys=new Map<string,AssociatedFile|null>();for(const file of inspection.files)for(const origin of file.origins)if(origin.kind==='name-tree')for(const raw of origin.nameTreeKey.raw)if(raw.bytesHex!==null){const previous=keys.get(raw.bytesHex);keys.set(raw.bytesHex,keys.has(raw.bytesHex)&&previous!==file?null:file);}
    data.entries.forEach((entry,i)=>{
      const file=files.get(entry.filePath)??keys.get(entry.keyHex)??undefined;
      let folder=this.root;const match=/^<(\d+)>(.+)$/su.exec(entry.key);
      if(match){const n=Number(match[1]),candidate=numbers.get(n);if(candidate)folder=candidate;else if(data.folders.length)this.notices.push('A member with an unknown or ambiguous folder ID was placed at the collection root.');}
      this.rows.push({id:'member:'+i,folder,kind:'file',name:file?fileLabel(file):typeof entry.name==='string'?entry.name:entry.key,file,entry});
    });
  }
  value(row:PortfolioRow,field:PortfolioField):{value:Scalar;prefix:string}{
    const item=row.entry??row.directory;const found=item?.values.find(v=>v[0]===field.key);let value:Scalar=null;
    switch(field.subtype){case 'F':value=row.name;break;case 'Desc':value=item?.description??null;break;case 'CreationDate':value=item?.created??null;break;case 'ModDate':value=item?.modified??null;break;case 'Size':value=row.entry?.size??null;break;case 'CompressedSize':value=row.entry?.compressedSize??null;break;default:value=found?.[1]??null;}
    return {value,prefix:typeof found?.[2]==='string'?found[2]:''};
  }
  display(row:PortfolioRow,field:PortfolioField):string {
    const {value,prefix}=this.value(row,field);if(value===null)return '';
    const date=['D','CreationDate','ModDate'].includes(field.subtype)?pdfDate(value):null;
    if(date!==null){const precision=/^(?:D:)?(\d{4})(\d{2})?(\d{2})?/.exec(String(value));return prefix+(precision&&!precision[3]?precision.slice(1,3).filter(Boolean).join('-'):new Date(date).toISOString().slice(0,10));}
    if(['Size','CompressedSize'].includes(field.subtype)&&typeof value==='number'&&Number.isFinite(value)&&value>=0){
      const units=['B','KiB','MiB','GiB','TiB','PiB'];let amount=value,unit=0;while(amount>=1024&&unit<units.length-1){amount/=1024;unit++;}
      return prefix+amount.toLocaleString('en-US',{maximumFractionDigits:unit?1:0})+' '+units[unit];
    }
    return prefix+String(value);
  }
  detail(row:PortfolioRow,field:PortfolioField):string {
    const {value,prefix}=this.value(row,field);if(value===null)return '';
    if(['Size','CompressedSize'].includes(field.subtype)&&typeof value==='number')return prefix+value.toLocaleString('en-US')+' bytes';
    const date=['D','CreationDate','ModDate'].includes(field.subtype)?pdfDate(value):null;
    if(date!==null&&/^(?:D:)?\d{8}/.test(String(value)))return prefix+new Date(date).toISOString().replace('T',' ').replace('.000Z',' UTC')+' · Recorded: '+value;
    return prefix+String(value);
  }
  sorted(rows:readonly PortfolioRow[],sort:readonly Scalar[]=this.data.sort,ascending:readonly Scalar[]=this.data.ascending):PortfolioRow[]{
    const keys=sort.length?sort:['F'];
    return [...rows].sort((a,b)=>{
      for(let i=0;i<keys.length;i++){
        const field=this.fields.find(f=>f.key===keys[i])??(keys[i]==='F'?defaults[0]:undefined);if(!field)continue;
        const scalar=(row:PortfolioRow)=>{const v=this.value(row,field).value;if(['D','CreationDate','ModDate'].includes(field.subtype))return pdfDate(v);return ['N','Size','CompressedSize'].includes(field.subtype)?typeof v==='number'?v:null:typeof v==='string'?v:null;};
        const av=scalar(a),bv=scalar(b);if(av===null&&bv!==null)return 1;if(bv===null&&av!==null)return -1;
        const compared=typeof av==='number'&&typeof bv==='number'?av-bv:typeof av==='string'&&typeof bv==='string'?av.localeCompare(bv):0;
        if(compared)return compared*((ascending===this.data.ascending&&this.data.ascendingAll===false||ascending[i]===false)?-1:1);
      }
      return 0;
    });
  }
  folderPath(fileId:string):string[] {
    const members=this.rows.filter(row=>row.kind==='file'&&row.file?.id===fileId);
    const locations=new Set(members.map(row=>row.folder));
    if(locations.size!==1)return [];
    const names:string[]=[],seen=new Set<string>();let folder=members[0]?.folder;
    while(folder&&folder!==this.root){
      if(seen.has(folder))return [];seen.add(folder);
      const row=this.rows.find(row=>row.kind==='folder'&&row.id===folder);
      if(!row)return [];
      names.unshift(row.name);folder=row.folder;
    }
    return names;
  }
  initial():PortfolioRow|'container'|'empty'|'invalid'{
    if(this.data.initialHex===null)return 'container';
    const matches=this.rows.filter(r=>r.kind==='file'&&r.entry?.keyHex===this.data.initialHex);
    return matches.length===1?matches[0]!:'invalid';
  }
  layout():string {
    if(this.data.view!=='C')return ['D','T','H'].includes(this.data.view)?this.data.view:'D';
    return this.data.layouts.find(v=>typeof v==='string'&&['D','T','H','FilmStrip','FreeForm','Linear','Tree'].includes(v)) as string??'D';
  }
}
