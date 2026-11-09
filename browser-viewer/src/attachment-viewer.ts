import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';
import { loadManagedPdf } from './pdfjs-adapter.js';
import {saveBlob} from './downloads.js';
const MAX_PIXELS = 16_000_000;
const MAX_TEXT = 2 * 1024 * 1024;
export type AttachmentKind = 'pdf' | 'image' | 'text' | 'unsupported';
export function attachmentKind(bytes: Uint8Array, name: string, mime: string): AttachmentKind {
  const head = new TextDecoder('latin1').decode(bytes.subarray(0, 1024));
  if (/%PDF-\d\.\d/.test(head)) return 'pdf';
  if (imageSize(bytes)) return 'image';
  const type = mime.split(';')[0]!.trim().toLowerCase();
  if (type.startsWith('text/') || ['application/json','application/xml','message/rfc822','message/global','image/svg+xml'].includes(type) ||
      /\.(txt|csv|tsv|log|json|xml|html?|eml|svg|md)$/i.test(name)) return 'text';
  return 'unsupported';
}
// Read dimensions before allocating pixels. GIF displays its first frame only.
export function imageSize(bytes: Uint8Array): { width: number; height: number; mime: string } | null {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes.length >= 24 && [137,80,78,71,13,10,26,10].every((n,i)=>bytes[i]===n)) {
    return {width:view.getUint32(16),height:view.getUint32(20),mime:'image/png'};
  }
  const signature = new TextDecoder('latin1').decode(bytes.subarray(0,6));
  if (bytes.length >= 10 && ['GIF87a','GIF89a'].includes(signature)) {
    return {width:view.getUint16(6,true),height:view.getUint16(8,true),mime:'image/gif'};
  }
  if (bytes[0] === 255 && bytes[1] === 216) {
    let offset=2;
    while (offset+4<=bytes.length) {
      if (bytes[offset++]!==255) return null;
      while(bytes[offset]===255) offset++;
      const marker=bytes[offset++];
      if(marker===undefined||marker===217||marker===218) return null;
      if(marker===1||(marker>=208&&marker<=215)) continue;
      if(offset+2>bytes.length) return null;
      const length=view.getUint16(offset);
      if(length<2||offset+length>bytes.length) return null;
      if(marker>=192&&marker<=207&&![196,200,204].includes(marker)&&length>=8) {
        return {width:view.getUint16(offset+5),height:view.getUint16(offset+3),mime:'image/jpeg'};
      }
      offset+=length;
    }
  }
  return null;
}
export function attachmentText(bytes: Uint8Array, mime: string): {text: string; truncated: boolean} {
  const charset=/charset\s*=\s*["']?([^\s;"']+)/i.exec(mime)?.[1];
  const encoding=bytes[0]===255&&bytes[1]===254?'utf-16le':bytes[0]===254&&bytes[1]===255?'utf-16be':charset??'utf-8';
  let decoder:TextDecoder;
  try {decoder=new TextDecoder(encoding);} catch {throw new Error('This text encoding is not supported. Download the original file to read it.');}
  const value=decoder.decode(bytes.subarray(0,MAX_TEXT));
  if(value.includes('\0')) throw new Error('This attachment contains binary data. Download it to open with a suitable application.');
  return {text:value,truncated:bytes.length>MAX_TEXT};
}
export class AttachmentViewer {
  private readonly dialog=document.createElement('dialog');
  private readonly title=document.createElement('h2');
  private readonly status=document.createElement('p');
  private readonly canvas=document.createElement('canvas');
  private readonly content=document.createElement('pre');
  private readonly previous=document.createElement('button');
  private readonly next=document.createElement('button');
  private readonly zoom=document.createElement('select');
  private readonly done=document.createElement('button');
  private readonly save=document.createElement('button');
  private managed:ReturnType<typeof loadManagedPdf>|undefined;
  private pdf:PDFDocumentProxy|undefined;
  private rendering:RenderTask|undefined;
  private image:ImageBitmap|undefined;
  private bytes:Uint8Array|undefined;
  private name='';
  private restoreFocus:(()=>void)|undefined;
  private page=1;
  private generation=0;
  private busy=false;
  constructor() {
    this.dialog.id='attachment-viewer';
    this.title.id='attachment-title';this.dialog.setAttribute('aria-labelledby',this.title.id);
    this.status.id='attachment-status';this.status.setAttribute('role','status');
    this.canvas.id='attachment-canvas';this.content.id='attachment-text';
    this.previous.textContent='Previous';this.previous.id='attachment-previous';
    this.next.textContent='Next';this.next.id='attachment-next';
    this.done.id='attachment-close';this.done.type='button';this.done.setAttribute('aria-label','Close attachment preview');this.done.title='Close attachment preview (Esc)';
    const cross=document.createElement('span');cross.textContent='×';cross.setAttribute('aria-hidden','true');this.done.append(cross,document.createTextNode('Close'));
    const heading=document.createElement('header');heading.className='attachment-heading';heading.append(this.title,this.done);
    this.save.textContent='Download original';this.save.id='attachment-download';
    this.zoom.id='attachment-zoom';this.zoom.setAttribute('aria-label','Attachment zoom');
    for(const scale of [0.5,0.75,1,1.25,1.5,2]) {const option=document.createElement('option');option.value=String(scale);option.textContent=`${scale*100}%`;this.zoom.append(option);}
    const toolbar=document.createElement('div');toolbar.className='attachment-controls';
    toolbar.append(this.previous,this.next,this.zoom,this.save);
    const viewport=document.createElement('div');viewport.className='attachment-content';viewport.append(this.canvas,this.content);
    this.dialog.append(heading,toolbar,this.status,viewport);document.body.append(this.dialog);
    this.done.addEventListener('click',()=>this.close());
    this.dialog.addEventListener('cancel',event=>{event.preventDefault();this.close();});
    this.previous.addEventListener('click',()=>{if(!this.busy){this.page--;void this.render();}});
    this.next.addEventListener('click',()=>{if(!this.busy){this.page++;void this.render();}});
    this.zoom.addEventListener('change',()=>{if(!this.busy)void this.render();});
    this.save.addEventListener('click',async()=>{
      if(!this.bytes||this.save.disabled)return;
      const epoch=this.generation;this.save.disabled=true;
      try{await saveBlob(new Blob([new Uint8Array(this.bytes)],{type:'application/octet-stream'}),this.name,
        ()=>{if(epoch!==this.generation)throw new Error('Download cancelled.');},this.dialog);
      }catch(error){if(epoch===this.generation)this.status.textContent=`Download failed: ${error instanceof Error?error.message:String(error)}`;}
      finally{if(epoch===this.generation)this.save.disabled=false;}
    });
  }
  close():void { const restore=this.restoreFocus;this.restoreFocus=undefined;this.release();if(this.dialog.open)this.dialog.close();restore?.(); }
  private release():void {
    this.generation++;this.rendering?.cancel();this.rendering=undefined;
    this.managed?.close();this.managed=undefined;this.pdf=undefined;
    this.image?.close();this.image=undefined;this.bytes=undefined;
    this.save.disabled=false;
    this.canvas.hidden=true;this.canvas.width=this.canvas.height=0;this.content.textContent='';this.busy=false;
  }
  private controls():void {
    this.previous.hidden=this.next.hidden=!this.pdf||this.pdf.numPages<=1;
    this.previous.disabled=this.busy||!this.pdf||this.page<=1;
    this.next.disabled=this.busy||!this.pdf||this.page>=this.pdf.numPages;
    this.zoom.disabled=this.busy||(!this.pdf&&!this.image);
    this.dialog.setAttribute('aria-busy',String(this.busy));
  }
  async open(bytes:Uint8Array,name:string,mime:string,restoreFocus?:()=>void):Promise<void> {
    this.release();const ticket=this.generation;
    const previous=document.activeElement;this.restoreFocus=restoreFocus??(()=>{if(previous instanceof HTMLElement&&previous.isConnected)previous.focus({preventScroll:true});});
    this.bytes=bytes;this.name=name;this.page=1;this.zoom.value='1';this.title.textContent=name;this.title.title=name;
    this.canvas.hidden=this.content.hidden=true;this.status.textContent='Opening attachment…';this.busy=true;this.controls();
    if(!this.dialog.open)this.dialog.showModal();
    this.done.focus();
    try {
      if(bytes.length===0) {this.status.textContent='This embedded file is empty. Its original content may have been omitted.';return;}
      const kind=attachmentKind(bytes,name,mime);
      if(kind==='pdf') {
        this.managed=loadManagedPdf(bytes.slice());
        const pdf=await this.managed.run(this.managed.task.promise);
        if(ticket!==this.generation)return;
        this.pdf=pdf;await this.render();
      } else if(kind==='image') {
        const size=imageSize(bytes)!;
        if(!size.width||!size.height||size.width*size.height>MAX_PIXELS)throw new Error('Image exceeds the 16-million-pixel viewing limit. Download the original to view it.');
        const bitmap=await createImageBitmap(new Blob([new Uint8Array(bytes)],{type:size.mime}));
        if(ticket!==this.generation){bitmap.close();return;}
        if(bitmap.width*bitmap.height>MAX_PIXELS){bitmap.close();throw new Error('Decoded image exceeds the viewing limit.');}
        this.image=bitmap;await this.render();
      } else if(kind==='text') {
        const decoded=attachmentText(bytes,mime);this.content.textContent=decoded.text;this.content.hidden=false;
        this.status.textContent=decoded.truncated?'Showing the first 2 MiB as plain text. Download the complete original.':'Shown as plain text. Markup and email source are not executed.';
      } else this.status.textContent='Viewing is not available for this file type. Download the original to open it in a suitable application.';
    } catch(error) {if(ticket===this.generation)this.status.textContent=`Unable to view attachment: ${error instanceof Error?error.message:String(error)}`;}
    finally {if(ticket===this.generation){this.busy=false;this.controls();}}
  }
  private async render():Promise<void> {
    const ticket=this.generation;this.busy=true;this.controls();this.canvas.hidden=true;
    try {
      const scale=Number(this.zoom.value);
      if(this.pdf&&this.managed) {
        const page=await this.managed.run(this.pdf.getPage(this.page));
        try {
          if(ticket!==this.generation)return;
          const viewport=page.getViewport({scale});
          if(Math.ceil(viewport.width)*Math.ceil(viewport.height)>MAX_PIXELS)throw new Error('Page is too large at this zoom. Choose a lower zoom.');
          this.canvas.width=Math.ceil(viewport.width);this.canvas.height=Math.ceil(viewport.height);
          this.rendering=page.render({canvas:this.canvas,viewport});
          await this.managed.run(this.rendering.promise);
          if(ticket!==this.generation)return;
          this.status.textContent=`Page ${this.page} of ${this.pdf.numPages}`;
        } finally {page.cleanup();}
      } else if(this.image) {
        const width=Math.ceil(this.image.width*scale),height=Math.ceil(this.image.height*scale);
        if(width*height>MAX_PIXELS)throw new Error('Image is too large at this zoom. Choose a lower zoom.');
        this.canvas.width=width;this.canvas.height=height;this.canvas.getContext('2d')!.drawImage(this.image,0,0,width,height);
        this.status.textContent=`Image ${this.image.width} × ${this.image.height} pixels (first frame).`;
      }
      this.canvas.hidden=false;
    } catch(error) {if(ticket===this.generation)this.status.textContent=`Unable to view attachment: ${error instanceof Error?error.message:String(error)}`;}
    finally {if(ticket===this.generation){this.rendering=undefined;this.busy=false;this.controls();}}
  }
}
