import {BrowserSession,type BrowserDocument} from './browser-session.js';
import {CSV_FIELDS,type CsvField,type CsvOptions} from './metadata-csv.js';
import {collectXmp,metadataZip,type XmpExport} from './metadata-export.js';

type Context={doc:BrowserDocument;root:BrowserDocument;messageId?:string;session:BrowserSession;check:()=>void;
  csv:(doc:BrowserDocument,options:CsvOptions)=>void;
  save:(blob:Blob,name:string,check:()=>void,dialog:HTMLDialogElement)=>Promise<void>};
export class MetadataDialog{
  private context?:Context;private session?:BrowserSession;private generation=0;private result?:XmpExport;private previewIndex=0;private previewOffset=0;
  private readonly dialog=document.getElementById('metadata-dialog') as HTMLDialogElement;
  private el<T extends HTMLElement=HTMLElement>(id:string):T{return document.getElementById(id) as T;}
  constructor(){
    const fields=this.el('metadata-fields');
    for(const field of CSV_FIELDS){const label=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.value=field;input.checked=true;label.append(input,document.createTextNode(field==='PDF'?'PDF filename / path':field));fields.append(label);}
    this.el('metadata-close').onclick=()=>this.close();this.dialog.addEventListener('cancel',event=>{event.preventDefault();this.close();});
    this.el('metadata-format').onchange=()=>this.changed();this.el('metadata-scope').onchange=()=>this.changed();
    this.el('metadata-load').onclick=()=>void this.load();this.el('metadata-save').onclick=()=>void this.save();
    this.el('metadata-previous').onclick=()=>{this.previewOffset=Math.max(0,this.previewOffset-256*1024);this.preview(this.previewIndex,false);};
    this.el('metadata-next').onclick=()=>{this.previewOffset+=256*1024;this.preview(this.previewIndex,false);};
    this.el('metadata-save-one').onclick=()=>void this.save(true);
    for(const [id,value]of [['metadata-all',true],['metadata-none',false]] as const)this.el(id).onclick=()=>{
      const group=this.format()==='csv'?'metadata-fields':'metadata-packets';
      this.el(group).querySelectorAll<HTMLInputElement>('input[type=checkbox]').forEach(input=>input.checked=value);
    };
  }
  open(context:Context){this.close();this.context=context;this.el<HTMLSelectElement>('metadata-format').value='csv';this.el<HTMLSelectElement>('metadata-scope').value='all';
    this.el<HTMLOptionElement>('metadata-current').disabled=!context.messageId;this.changed();this.dialog.showModal();}
  close(){this.generation++;this.session?.close();this.session=undefined;this.context=undefined;this.result=undefined;this.el('metadata-packets').replaceChildren();this.el<HTMLTextAreaElement>('metadata-preview').value='';this.dialog.close();}
  private format(){return this.el<HTMLSelectElement>('metadata-format').value;}
  private current(){return this.el<HTMLSelectElement>('metadata-scope').value==='current';}
  private changed(){
    this.generation++;this.session?.close();this.session=undefined;this.result=undefined;
    const xml=this.format()==='xmp';this.el('metadata-fields').hidden=xml;this.el('metadata-xml').hidden=!xml;
    this.el('metadata-packets').replaceChildren();this.el<HTMLTextAreaElement>('metadata-preview').value='';
    this.el('metadata-note').textContent=xml?'Original XMP streams are exported without rewriting them. Current message scope includes its containing PDF; shared XML may also describe other messages.':'Choose the columns to include in the CSV. PDF filename / path identifies the PDF containing the email, including its path inside a collection. The original PDF is unchanged.';
    this.el('metadata-state').textContent='';this.el<HTMLButtonElement>('metadata-load').disabled=false;
    this.el<HTMLButtonElement>('metadata-save').disabled=xml;this.el('metadata-save').textContent=xml?'Download selected XMP (.zip)':'Export CSV';this.el<HTMLButtonElement>('metadata-save-one').disabled=true;
  }
  private async load(){
    const context=this.context;if(!context)return;
    this.session?.close();const session=new BrowserSession({},{reuseWorker:true});this.session=session;
    const generation=++this.generation,check=()=>{context.check();if(this.generation!==generation)throw new Error('Metadata export cancelled.');};
    this.result=undefined;this.el('metadata-packets').replaceChildren();this.el<HTMLTextAreaElement>('metadata-preview').value='';this.el('metadata-owner').textContent='';
    this.el<HTMLButtonElement>('metadata-save-one').disabled=true;
    this.el<HTMLButtonElement>('metadata-load').disabled=true;this.el<HTMLButtonElement>('metadata-save').disabled=true;
    this.el('metadata-state').textContent='Reading metadata…';
    try{
      const doc=this.current()?context.doc:context.root,blob=await context.session.sourceBlob(doc);check();
      const source=await session.open(blob,doc.name);check();
      const result=await collectXmp(source,session,check,text=>{check();this.el('metadata-state').textContent=text;});check();this.result=result;
      const list=this.el('metadata-packets');list.replaceChildren();
      result.packets.forEach((packet,index)=>{
        const row=document.createElement('div'),label=document.createElement('label'),input=document.createElement('input'),preview=document.createElement('button');
        input.type='checkbox';input.value=String(index);input.checked=true;label.append(input,document.createTextNode(`${packet.document} · ${packet.locator}`));
        preview.type='button';preview.textContent='Preview';preview.setAttribute('aria-label',`Preview ${packet.name}`);preview.onclick=()=>this.preview(index);row.append(label,preview);list.append(row);
      });
      this.el('metadata-state').textContent=`${result.packets.length} metadata streams found.`+(result.issues.length?' Some content could not be fully inspected:\n'+result.issues.join('\n'):'');
      this.el<HTMLButtonElement>('metadata-save').disabled=!result.packets.length;if(result.packets.length)this.preview(0);
    }catch(error){if(this.generation===generation)this.el('metadata-state').textContent=error instanceof Error?error.message:String(error);}
    finally{session.close();if(this.session===session)this.session=undefined;if(this.generation===generation)this.el<HTMLButtonElement>('metadata-load').disabled=false;}
  }
  private preview(index:number,reset=true){if(reset)this.previewOffset=0;const packet=this.result?.packets[index];if(!packet)return;this.previewIndex=index;
    this.el('metadata-owner').textContent=`${packet.name} · ${packet.document}\nAttached to: ${packet.owners.join(', ')||packet.locator}`;
    // Keep previews bounded, while downloads retain the complete original stream.
    this.el<HTMLTextAreaElement>('metadata-preview').value=packet.text===null?'This stream could not be decoded as text. Its original bytes can still be downloaded.':packet.text.slice(this.previewOffset,this.previewOffset+256*1024);
    this.el<HTMLButtonElement>('metadata-previous').disabled=this.previewOffset===0;
    this.el<HTMLButtonElement>('metadata-next').disabled=!packet.text||this.previewOffset+256*1024>=packet.text.length;
    this.el('metadata-position').textContent=`Characters ${this.previewOffset+1}–${Math.min(this.previewOffset+256*1024,packet.text?.length??0)} of ${packet.text?.length??0}`;
    this.el<HTMLButtonElement>('metadata-save-one').disabled=false;
  }
  private async save(one=false){
    const context=this.context;if(!context)return;
    const generation=this.generation,check=()=>{context.check();if(generation!==this.generation)throw new Error('Metadata export cancelled.');};
    try{
      if(this.format()==='csv'){
        const fields=[...this.el('metadata-fields').querySelectorAll<HTMLInputElement>('input:checked')].map(input=>input.value as CsvField);
        if(!fields.length)throw new Error('Select at least one CSV field.');
        const current=this.current(),doc=current?context.doc:context.root,options:CsvOptions={fields,...(current?{messageId:context.messageId}:{})};
        this.close();context.csv(doc,options);return;
      }
      const result=this.result;if(!result)throw new Error('Load metadata first.');
      const selected=[...this.el('metadata-packets').querySelectorAll<HTMLInputElement>('input:checked')].map(input=>result.packets[Number(input.value)]!);
      const packet=result.packets[this.previewIndex]!;
      const blob=one?new Blob([new Uint8Array(packet.bytes)],{type:'application/rdf+xml'}):metadataZip(result,selected);
      const name=one?packet.name:(this.current()?context.doc:context.root).name.replace(/\.pdf$/i,'')+'-metadata.zip';
      check();await context.save(blob,name,check,this.dialog);check();this.el('metadata-state').textContent='Metadata downloaded.'+(result.issues.length?' See the export manifest for incomplete inspection details.':'');
    }catch(error){if(this.generation===generation)this.el('metadata-state').textContent=error instanceof Error?error.message:String(error);}
  }
}
