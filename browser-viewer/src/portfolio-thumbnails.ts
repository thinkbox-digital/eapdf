import {loadManagedPdf} from './pdfjs-adapter.js';
import {imageSize} from './attachment-viewer.js';
import type {PortfolioRow} from './portfolio.js';
export type ThumbnailData=Uint8Array|{width:number;height:number;data:Uint8Array};
// Per-panel cache and a single rendering worker bound background preview work.
export class PortfolioThumbnails {
  private epoch=0;private active=false;private queue:{row:PortfolioRow;host:HTMLElement;epoch:number}[]=[];
  private observer:IntersectionObserver;private targets=new WeakMap<Element,PortfolioRow>();
  private cache=new Map<string,string|null>();private pending:ReturnType<typeof loadManagedPdf>|undefined;
  constructor(root:HTMLElement,private readonly read:(row:PortfolioRow)=>Promise<ThumbnailData>){
    this.observer=new IntersectionObserver(entries=>{for(const item of entries)if(item.isIntersecting){const row=this.targets.get(item.target);this.observer.unobserve(item.target);if(row)this.queue.push({row,host:item.target as HTMLElement,epoch:this.epoch});}void this.work();},{root,rootMargin:'100px'});
  }
  reset():void{this.epoch++;this.queue=[];this.observer.disconnect();this.pending?.close();}
  close():void{this.reset();this.cache.clear();}
  observe(row:PortfolioRow,host:HTMLElement):void{
    const stream=row.file?.streams[0],size=stream?.decodedSize.value??stream?.declaredSize.value;
    if(row.kind==='folder'){if(row.directory?.thumbnail){this.targets.set(host,row);this.observer.observe(host);}return;}
    if(row.kind!=='file'||!row.file||size===null||size===undefined||size>4*1024**2||!(/\.(pdf|png|jpe?g|gif)$/i.test(row.name)||/^(application\/pdf|image\/(png|jpeg|gif))$/.test(stream?.mimeType.value??'')))return;
    this.targets.set(host,row);this.observer.observe(host);
  }
  private async work():Promise<void>{
    if(this.active)return;this.active=true;
    try{while(this.queue.length){const {row,host,epoch}=this.queue.shift()!;if(epoch!==this.epoch||!host.isConnected)continue;
      let url=this.cache.get(row.id);
      if(url===undefined){try{const data=await this.read(row);if(epoch!==this.epoch)continue;url=await this.render(data,epoch);}catch{url=null;}
        if(epoch!==this.epoch)continue;this.cache.set(row.id,url);if(this.cache.size>64)this.cache.delete(this.cache.keys().next().value!);
      }
      if(url&&host.isConnected&&epoch===this.epoch){const image=document.createElement('img');image.src=url;image.alt=`Preview of ${row.name}`;image.width=160;image.height=110;host.replaceChildren(image);}
    }}finally{this.active=false;}
  }
  private async render(data:ThumbnailData,epoch:number):Promise<string|null>{
    const canvas=document.createElement('canvas');canvas.width=160;canvas.height=110;
    if(!(data instanceof Uint8Array)){if(data.width*data.height>1048576||data.data.length!==data.width*data.height*4)return null;const source=document.createElement('canvas');source.width=data.width;source.height=data.height;source.getContext('2d')!.putImageData(new ImageData(new Uint8ClampedArray(data.data),data.width,data.height),0,0);const scale=Math.min(160/data.width,110/data.height);canvas.width=Math.max(1,Math.round(data.width*scale));canvas.height=Math.max(1,Math.round(data.height*scale));canvas.getContext('2d')!.drawImage(source,0,0,canvas.width,canvas.height);source.width=source.height=0;return canvas.toDataURL('image/png');}
    if(/%PDF-/.test(new TextDecoder('latin1').decode(data.subarray(0,1024)))){
      const managed=loadManagedPdf(data);this.pending=managed;managed.task.onPassword=()=>managed.close();
      try{const pdf=await managed.run(managed.task.promise,10000);if(epoch!==this.epoch)return null;const page=await managed.run(pdf.getPage(1),10000),base=page.getViewport({scale:1}),scale=Math.min(160/base.width,110/base.height);const view=page.getViewport({scale});canvas.width=Math.max(1,Math.ceil(view.width));canvas.height=Math.max(1,Math.ceil(view.height));await managed.run(page.render({canvas,viewport:view}).promise,10000);page.cleanup();return canvas.toDataURL('image/png');}finally{managed.close();if(this.pending===managed)this.pending=undefined;}
    }
    const size=imageSize(data);if(!size||size.width*size.height>16_000_000)return null;const bitmap=await createImageBitmap(new Blob([new Uint8Array(data)],{type:size.mime}));try{if(epoch!==this.epoch)return null;const scale=Math.min(160/bitmap.width,110/bitmap.height);canvas.width=Math.max(1,Math.ceil(bitmap.width*scale));canvas.height=Math.max(1,Math.ceil(bitmap.height*scale));canvas.getContext('2d')!.drawImage(bitmap,0,0,canvas.width,canvas.height);return canvas.toDataURL('image/png');}finally{bitmap.close();}
  }
}
