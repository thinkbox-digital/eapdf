import type {PDFDocumentProxy,PDFPageProxy,RenderTask} from 'pdfjs-dist';
import type {BrowserDocument} from './browser-session.js';
import {PageText} from './page-text.js';

type PageSlot={number:number;box:HTMLElement;canvas:HTMLCanvasElement;text:HTMLElement;links:HTMLElement;layer:PageText;render?:RenderTask;ready:boolean};
const GAP=24,MAX_PIXELS=16_000_000,MAX_RETAINED_PIXELS=32_000_000;

// Keep light page placeholders for scrolling; retain pixels/text only near the
// current page. Large archives must not allocate one canvas for every page.
export class ScrollingPages {
  onAttachment?:(page:number,annotation:number|string)=>void;
  private doc:Pick<BrowserDocument,'pdf'|'run'>|undefined;
  private pdf:PDFDocumentProxy|undefined;
  private slots:PageSlot[]=[];
  private scale=1;
  private firstPage=1;
  private lastPage=0;
  private prefetch=true;
  private defaultWidth=1;
  private defaultHeight=1;
  private sizes=new Map<number,{width:number;height:number}>();
  private top=document.createElement('div');
  private bottom=document.createElement('div');
  private offset(number:number):number {
    let result=(number-this.firstPage)*(this.defaultHeight+GAP);
    for(const [page,size] of this.sizes)if(page>=this.firstPage&&page<number)result+=size.height-this.defaultHeight;
    return result;
  }
  // Browsers cap element/scroll dimensions. Compress only the offscreen space;
  // mounted pages retain their normal dimensions and natural local scrolling.
  private ratio():number {return Math.min(1,8_000_000/Math.max(1,this.offset(this.lastPage+1)));}
  private coordinates(position:number,inverse=false):number {
    const first=this.slots[0]?.number??1,last=this.slots.at(-1)?.number??0;
    const start=this.offset(first),end=this.offset(last+1),ratio=this.ratio(),physicalStart=start*ratio;
    if(inverse){
      if(position<start)return position*ratio;
      if(position<=end)return physicalStart+position-start;
      return physicalStart+end-start+(position-end)*ratio;
    }
    if(position<physicalStart)return position/ratio;
    if(position<=physicalStart+end-start)return start+position-physicalStart;
    return end+(position-physicalStart-end+start)/ratio;
  }
  private spacers():void {
    const first=this.slots[0]?.number??1,last=this.slots.at(-1)?.number??0,total=this.lastPage;
    this.top.hidden=first===this.firstPage;this.bottom.hidden=last===total;
    this.top.style.height=`${Math.max(0,this.offset(first)*this.ratio()-GAP)}px`;
    this.bottom.style.height=`${Math.max(0,(this.offset(total+1)-this.offset(last+1))*this.ratio()-GAP)}px`;
  }
  private mount(center:number):void {
    const total=this.lastPage,first=Math.max(this.firstPage,center-4),last=Math.min(total,center+4);
    if(this.slots[0]?.number===first&&this.slots.at(-1)?.number===last)return;
    const logical=this.coordinates(Math.max(0,this.viewport.scrollTop-30));
    const previous=new Map(this.slots.map(slot=>[slot.number,slot]));
    const next:PageSlot[]=[];
    for(let number=first;number<=last;number++){
      let slot=previous.get(number);previous.delete(number);
      if(!slot){
        const size=this.sizes.get(number)??{width:this.defaultWidth,height:this.defaultHeight};
        const box=document.createElement('section');box.className='pdf-page';box.dataset.page=String(number);box.setAttribute('aria-label',`Page ${number} of ${total}`);
        box.style.width=`${size.width}px`;box.style.height=`${size.height}px`;
        const canvas=document.createElement('canvas');canvas.width=canvas.height=0;canvas.hidden=true;
        const text=document.createElement('div');text.className='textLayer';
        const label=document.createElement('span');label.className='page-placeholder';label.textContent=`Page ${number}`;
        const links=document.createElement('div');links.className='internal-links';
        box.append(label,canvas,text,links);slot={number,box,canvas,text,links,layer:new PageText(text),ready:false};
      }
      next.push(slot);
    }
    for(const slot of previous.values()){this.release(slot);slot.box.remove();}
    this.slots=next;this.spacers();
    this.surface.replaceChildren(this.top,...next.map(slot=>slot.box),this.bottom);
    this.viewport.scrollTop=30+this.coordinates(logical,true);
  }
  private slot(number:number):PageSlot|undefined{return this.slots.find(slot=>slot.number===number);}
  private updateHorizontalOverflow():void {
    const style=getComputedStyle(this.viewport),available=this.viewport.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight);
    const oversized=this.slots.some(slot=>slot.box.getBoundingClientRect().width>available+1);
    this.viewport.style.overflowX=oversized?'auto':'hidden';
  }

  private active=1;
  private epoch=0;
  private locked=false;
  private frame=0;
  private work:Promise<void>=Promise.resolve();
  private query='';
  private match:{page:number;index:number}|undefined;
  private retained=new Set<PageSlot>();
  private pending:{epoch:number;promise:Promise<void>}|undefined;
  constructor(private viewport:HTMLElement,private surface:HTMLElement,
    private onPage:(page:number)=>void,private onError:(message:string)=>void,
    private onRenderState:(page:number,failed:boolean)=>void=()=>{},
    private onEmbeddedLink?:(action:import('./embedded-navigation.js').EmbeddedAction)=>void){
    viewport.addEventListener('scroll',()=>this.schedule(),{passive:true});
    new ResizeObserver(()=>this.schedule()).observe(viewport);
  }
  clear():void {
    ++this.epoch;cancelAnimationFrame(this.frame);this.frame=0;
    for(const slot of this.slots)this.release(slot);
    this.slots=[];this.sizes.clear();this.doc=undefined;this.pdf=undefined;this.locked=false;
    this.surface.replaceChildren();this.surface.hidden=true;
    const canvas=document.createElement('canvas');canvas.id='canvas';canvas.hidden=true;
    this.surface.append(canvas);this.viewport.scrollTop=0;
  }
  highlight(query:string,match?:{page:number;index:number}):number|undefined {
    let activeCount:number|undefined;this.query=query;this.match=match;for(const slot of this.slots)if(slot.ready){const count=slot.layer.highlight(query,match?.page===slot.number?match.index:undefined);if(match?.page===slot.number)activeCount=count;}return activeCount;
  }
  async show(doc:Pick<BrowserDocument,'pdf'|'run'>,scale:number,page:number,query:string,prefetch=true,range?:readonly number[]):Promise<void>{
    const first=range?.[0]??1,last=range?.at(-1)??doc.pdf.numPages;
    const rangeChanged=first!==this.firstPage||last!==this.lastPage;
    page=Math.max(first,Math.min(last,page));
    this.prefetch=prefetch;
    const sameDocument=this.doc===doc&&this.pdf===doc.pdf;
    const selectedMatch=sameDocument&&this.query===query?this.match:undefined;
    const zooming=sameDocument&&this.scale!==scale;
    const previous=this.slot(this.active)?.box.getBoundingClientRect();
    const bounds=this.viewport.getBoundingClientRect();
    const center=zooming&&previous?Math.max(0,Math.min(1,(bounds.left+this.viewport.clientLeft+this.viewport.clientWidth/2-previous.left)/previous.width)):0.5;
    this.locked=true;let epoch=this.epoch;
    try{
      if(this.doc!==doc||this.pdf!==doc.pdf||this.scale!==scale||rangeChanged){
        this.clear();this.locked=true;this.doc=doc;this.pdf=doc.pdf;this.scale=scale;this.firstPage=first;this.lastPage=last;
        epoch=this.epoch;const firstPdfPage=await doc.run(doc.pdf.getPage(first));
        if(epoch!==this.epoch)return;
        const size=firstPdfPage.getViewport({scale});firstPdfPage.cleanup();
        this.defaultWidth=size.width;this.defaultHeight=size.height;
        this.mount(page);this.surface.hidden=false;
      }
      this.query=query;this.mount(page);this.select(page);this.jump(page);
      epoch=this.epoch;await this.enqueue(epoch,true);
      if(epoch!==this.epoch)return;
      this.jump(page);this.highlight(query,selectedMatch);
      if(!sameDocument)this.viewport.scrollLeft=0;
      if(zooming){
        const box=this.slot(page)?.box.getBoundingClientRect();
        if(box){
          const viewport=this.viewport.getBoundingClientRect();
          this.viewport.scrollLeft+=box.left+center*box.width-(viewport.left+this.viewport.clientLeft+this.viewport.clientWidth/2);
        }
      }
    }finally{if(epoch===this.epoch){this.updateHorizontalOverflow();this.locked=false;this.schedule();}}
  }
  private select(number:number):void {
    const previous=this.slot(this.active);previous?.canvas.removeAttribute('id');previous?.text.removeAttribute('id');
    this.active=number;const slot=this.slot(number);if(slot){slot.canvas.id='canvas';slot.text.id='text-layer';}
  }
  private jump(number:number):void {
    const slot=this.slot(number);if(!slot)return;
    const top=this.viewport.getBoundingClientRect().top;
    this.viewport.scrollTop+=slot.box.getBoundingClientRect().top-top-GAP;
  }
  private schedule():void {
    if(this.frame||this.locked||!this.doc||!this.prefetch)return;
    this.frame=requestAnimationFrame(()=>{
      this.frame=0;if(this.locked||!this.doc)return;
      const bounds=this.viewport.getBoundingClientRect();let selected=this.active,best=0;
      // Pages are vertically ordered. Locate the visible interval without forcing
      // layout reads for every page in a long archive on every scroll frame.
      const position=this.coordinates(Math.max(0,this.viewport.scrollTop-30));
      let low=this.firstPage,high=this.lastPage;
      while(low<high){const mid=(low+high)>>>1;const height=this.sizes.get(mid)?.height??this.defaultHeight;if(this.offset(mid)+height<=position)low=mid+1;else high=mid;}
      this.mount(low);
      for(const slot of this.slots){
        const r=slot.box.getBoundingClientRect();if(r.top>=bounds.bottom)break;
        const visible=Math.max(0,Math.min(r.bottom,bounds.bottom)-Math.max(r.top,bounds.top));
        if(visible>best){best=visible;selected=slot.number;}
      }
      if(selected!==this.active){this.select(selected);this.onPage(selected);}
      const epoch=this.epoch;void this.enqueue(epoch).catch(error=>{if(epoch===this.epoch)this.onError(error instanceof Error?error.message:String(error));});
    });
  }
  private enqueue(epoch:number,focused=false):Promise<void>{
    if(this.pending?.epoch===epoch)return this.pending.promise;
    const promise=this.work.catch(()=>{}).then(()=>this.paintWindow(epoch,focused));
    const pending={epoch,promise};this.pending=pending;this.work=promise;
    const done=()=>{if(this.pending===pending)this.pending=undefined;};void promise.then(done,done);
    return promise;
  }
  private release(slot:PageSlot):void {
    this.retained.delete(slot);slot.render?.cancel();slot.render=undefined;slot.layer.clear();slot.links.replaceChildren();slot.canvas.width=slot.canvas.height=0;slot.canvas.hidden=true;slot.ready=false;delete slot.box.dataset.rendered;
  }
  private async paintWindow(epoch:number,focused=false):Promise<void>{
    if(epoch!==this.epoch||!this.doc)return;
    const doc=this.doc,center=this.active;
    const wanted=this.slots.filter(slot=>Math.abs(slot.number-center)<=2);
    for(const slot of this.retained)if(!wanted.includes(slot))this.release(slot);
    // Current page first; adjacent pages follow without blocking manual scrolling.
    wanted.sort((a,b)=>Math.abs(a.number-center)-Math.abs(b.number-center));
    for(const slot of wanted){
      if(epoch!==this.epoch)return;
      if(slot.ready||(focused&&slot.number!==center))continue;
      if(Math.abs(slot.number-this.active)>2)continue;
      let page:PDFPageProxy|undefined;
      try{
        page=await doc.run(doc.pdf.getPage(slot.number));
        if(epoch!==this.epoch)return;
        const view=page.getViewport({scale:this.scale}),ratio=Math.min(devicePixelRatio||1,2);
        const anchor=this.slot(this.active)??slot,before=anchor.box.getBoundingClientRect().top;
        this.sizes.set(slot.number,{width:view.width,height:view.height});this.spacers();
        slot.box.style.width=`${view.width}px`;slot.box.style.height=`${view.height}px`;
        this.viewport.scrollTop+=anchor.box.getBoundingClientRect().top-before;
        const pixels=Math.ceil(view.width*ratio)*Math.ceil(view.height*ratio);
        if(pixels>MAX_PIXELS)throw new Error('Page is too large at this zoom. Choose a lower zoom.');
        let retainedPixels=[...this.retained].reduce((n,s)=>n+s.canvas.width*s.canvas.height,0);
        if(slot.number===center)for(const other of [...this.retained].sort((a,b)=>Math.abs(b.number-center)-Math.abs(a.number-center))){
          if(retainedPixels+pixels<=MAX_RETAINED_PIXELS)break;
          retainedPixels-=other.canvas.width*other.canvas.height;this.release(other);
        }
        if(retainedPixels+pixels>MAX_RETAINED_PIXELS)continue;
        this.retained.add(slot);
        slot.canvas.width=Math.ceil(view.width*ratio);slot.canvas.height=Math.ceil(view.height*ratio);
        slot.canvas.style.width=`${view.width}px`;slot.canvas.style.height=`${view.height}px`;
        slot.canvas.setAttribute('aria-label',`Page ${slot.number} of ${doc.pdf.numPages}`);
        slot.render=page.render({canvas:slot.canvas,viewport:view,transform:[ratio,0,0,ratio,0,0]});
        await doc.run(slot.render.promise);if(epoch!==this.epoch)return;slot.render=undefined;
        slot.canvas.hidden=false;
        try{await doc.run(slot.layer.render(page,this.scale));if(epoch!==this.epoch)return;slot.layer.highlight(this.query,this.match?.page===slot.number?this.match.index:undefined);}
        catch(error){if(epoch!==this.epoch)return;slot.layer.clear();slot.box.querySelector('.page-placeholder')!.textContent='Text selection unavailable';}
        try{
          const annotations=await doc.run(page.getAnnotations({intent:'display'}));if(epoch!==this.epoch)return;
          for(const [annotationIndex,annotation] of annotations.entries()){
            if(annotation.subtype==='FileAttachment'&&Array.isArray(annotation.rect)){
              const rect=[...view.convertToViewportPoint(annotation.rect[0],annotation.rect[1]),...view.convertToViewportPoint(annotation.rect[2],annotation.rect[3])],link=document.createElement('a');link.href='#';
              link.classList.add('file-attachment-link');const contents=annotation.contentsObj?.str||annotation.file?.filename||'Open attachment';link.title=contents;link.setAttribute('aria-label',contents);
              Object.assign(link.style,{left:`${Math.min(rect[0],rect[2])}px`,top:`${Math.min(rect[1],rect[3])}px`,width:`${Math.max(16,Math.abs(rect[2]-rect[0]))}px`,height:`${Math.max(16,Math.abs(rect[3]-rect[1]))}px`});
              link.addEventListener('click',event=>{event.preventDefault();if(epoch===this.epoch)this.onAttachment?.(slot.number,annotation.id??annotationIndex);});slot.links.append(link);continue;
            }
            if(annotation.subtype!=='Link'||(!annotation.dest&&!annotation.embeddedAction)||!Array.isArray(annotation.rect)||annotation.rect.length!==4||!annotation.rect.every(Number.isFinite))continue;
            const rect=[...view.convertToViewportPoint(annotation.rect[0],annotation.rect[1]),...view.convertToViewportPoint(annotation.rect[2],annotation.rect[3])],left=Math.max(0,Math.min(rect[0]!,rect[2]!)),top=Math.max(0,Math.min(rect[1]!,rect[3]!)),right=Math.min(view.width,Math.max(rect[0]!,rect[2]!)),bottom=Math.min(view.height,Math.max(rect[1]!,rect[3]!));
            if(right<=left||bottom<=top)continue;
            const link=document.createElement('a');link.href='#';link.setAttribute('aria-label','Go to linked page in this PDF');link.title='Go to linked page';Object.assign(link.style,{left:`${left}px`,top:`${top}px`,width:`${right-left}px`,height:`${bottom-top}px`});
            if(annotation.embeddedAction){link.setAttribute('aria-label','Open linked embedded PDF');link.title='Open linked embedded PDF';}
            link.addEventListener('click',event=>{event.preventDefault();if(epoch!==this.epoch)return;if(annotation.embeddedAction){this.onEmbeddedLink?.(annotation.embeddedAction);return;}void (async()=>{
              try{const dest=typeof annotation.dest==='string'?await doc.run(doc.pdf.getDestination(annotation.dest)):annotation.dest;if(epoch!==this.epoch||!dest)return;const index=typeof dest[0]==='number'?dest[0]:await doc.run(doc.pdf.getPageIndex(dest[0]));if(epoch!==this.epoch)return;if(!Number.isInteger(index)||index<0||index>=doc.pdf.numPages)throw new Error('Link destination is outside this PDF.');await this.show(doc,this.scale,index+1,this.query);this.onPage(index+1);}
              catch(error){if(epoch===this.epoch)this.onError(error instanceof Error?error.message:String(error));}
            })();});slot.links.append(link);
          }
        }catch{if(epoch===this.epoch)slot.links.dataset.unavailable='true';}
        slot.ready=true;slot.box.dataset.rendered='true';this.onRenderState(slot.number,false);
      }catch(error){
        if(epoch!==this.epoch)return;this.release(slot);this.onRenderState(slot.number,true);
        slot.box.querySelector('.page-placeholder')!.textContent=`Unable to display page ${slot.number}: ${error instanceof Error?error.message:String(error)}`;
        if(slot.number===this.active)throw error;
      }finally{page?.cleanup();if(epoch===this.epoch)this.updateHorizontalOverflow();}
    }
    if(!focused&&epoch===this.epoch&&center!==this.active)this.schedule();
  }
}
