import {boundedStructureTree} from './structure-tree.js';
import {textMatches} from './document-search.js';
import type {PDFPageProxy,TextLayer} from 'pdfjs-dist';
import {pdfjs} from './pdfjs-adapter.js';
export class PageText {
  private layer:TextLayer|undefined;
  private structure:HTMLElement|undefined;
  constructor(private readonly container:HTMLElement){}
  clear():void {delete this.container.dataset.structure;this.structure?.remove();this.structure=undefined;this.layer?.cancel();this.layer=undefined;this.container.replaceChildren();}
  async render(page:PDFPageProxy,scale:number):Promise<void>{
    this.clear();const viewport=page.getViewport({scale});
    this.container.style.setProperty('--total-scale-factor',String(scale*page.userUnit));
    const layer=new pdfjs.TextLayer({textContentSource:page.streamTextContent({includeMarkedContent:true}),container:this.container,viewport});
    this.layer=layer;await layer.render();
    // Reuse upstream's structure-to-ARIA implementation, without starting its viewer,
    // annotation actions, or scripting manager. Canvas fallback carries the semantics.
    try{
      const url=new URL('./vendor/pdfjs/pdf_viewer.mjs',import.meta.url).href;
      const {StructTreeLayerBuilder}=await import(url);
      if(this.layer!==layer)return;
      const structure=await page.getStructTree();if(this.layer!==layer)return;
      if(!boundedStructureTree(structure))throw new Error('Structure tree exceeds the accessible display limit.');
      const builder=new StructTreeLayerBuilder({getStructTree:()=>Promise.resolve(structure)},viewport.rawDims),tree=await builder.render();
      if(this.layer!==layer)return;
      if(tree){builder.updateTextLayer();this.structure=tree;this.container.parentElement?.querySelector('canvas')?.append(tree);}
    }catch{this.container.dataset.structure='unavailable';}

  }
  highlight(query:string,activeIndex?:number):number {
    for(const mark of this.container.querySelectorAll('mark'))mark.replaceWith(...mark.childNodes);
    this.container.normalize();if(!query.trim())return 0;
    const nodes:Text[]=[];const walker=document.createTreeWalker(this.container,NodeFilter.SHOW_TEXT);let node:Node|null;
    while((node=walker.nextNode()))nodes.push(node as Text);
    // The same space-separated string as page search, with ranges mapped back
    // to actual PDF.js text nodes. A phrase can span several text runs.
    const starts:number[]=[];let full='';
    for(const n of nodes){starts.push(full.length);full+=n.data+' ';}
    const matches=textMatches(full,query);
    nodes.forEach((n,index)=>{
      const start=starts[index]!,end=start+n.data.length,parts=matches.filter(m=>m.start<end&&m.end>start);if(!parts.length)return;
      const fragment=document.createDocumentFragment();let cursor=0;
      for(const m of parts){const a=Math.max(0,m.start-start),b=Math.min(n.data.length,m.end-start);fragment.append(document.createTextNode(n.data.slice(cursor,a)));const mark=document.createElement('mark');mark.textContent=n.data.slice(a,b);if(activeIndex!==undefined&&matches[activeIndex]===m)mark.className='current-match';fragment.append(mark);cursor=b;}
      fragment.append(document.createTextNode(n.data.slice(cursor)));n.replaceWith(fragment);
    });
    this.container.querySelector('.current-match')?.scrollIntoView({block:'nearest',inline:'nearest'});
    return matches.length;
  }
}
