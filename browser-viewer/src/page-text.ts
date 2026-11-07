import {boundedStructureTree} from './structure-tree.js';
import {textMatches,pdfSearchText} from './document-search.js';
import type {PDFPageProxy,TextLayer} from 'pdfjs-dist';
import {pdfjs} from './pdfjs-adapter.js';
export class PageText {
  private eols:boolean[]=[];
  private layer:TextLayer|undefined;
  private structure:HTMLElement|undefined;
  constructor(private readonly container:HTMLElement){}
  clear():void {this.eols=[];delete this.container.dataset.structure;this.structure?.remove();this.structure=undefined;this.layer?.cancel();this.layer=undefined;this.container.replaceChildren();}
  async render(page:PDFPageProxy,scale:number):Promise<void>{
    this.clear();const viewport=page.getViewport({scale});
    this.container.style.setProperty('--total-scale-factor',String(scale*page.userUnit));
    const eols:boolean[]=[];this.eols=eols;
    const source=page.streamTextContent({includeMarkedContent:true}).pipeThrough(new TransformStream({
      transform(chunk,controller){for(const item of chunk.items)if('str'in item)eols.push(item.hasEOL);controller.enqueue(chunk);},
    }));
    const layer=new pdfjs.TextLayer({textContentSource:source,container:this.container,viewport});
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
    const layer=this.layer;if(!layer)return 0;
    // Use the same fragment/EOL boundaries as getTextContent search; offsets stay
    // in the original strings used by each PDF.js text div.
    const values=layer.textContentItemsStr,divs=layer.textDivs;
    const matches=textMatches(pdfSearchText(values.map((str,i)=>({str,hasEOL:this.eols[i]}))),query);let start=0;
    values.forEach((value,index)=>{
      const div=divs[index],end=start+value.length;
      if(!div){start=end+(this.eols[index]?1:0);return;}
      const parts=matches.filter(m=>m.start<end&&m.end>start);
      if(!parts.length){div.textContent=value;start=end+(this.eols[index]?1:0);return;}
      const fragment=document.createDocumentFragment();let cursor=0;
      for(const m of parts){const a=Math.max(0,m.start-start),b=Math.min(value.length,m.end-start);fragment.append(document.createTextNode(value.slice(cursor,a)));const mark=document.createElement('mark');mark.textContent=value.slice(a,b);if(activeIndex!==undefined&&matches[activeIndex]===m)mark.className='current-match';fragment.append(mark);cursor=b;}
      fragment.append(document.createTextNode(value.slice(cursor)));div.replaceChildren(fragment);start=end+(this.eols[index]?1:0);
    });
    this.container.querySelector('.current-match')?.scrollIntoView({block:'nearest',inline:'nearest'});
    return matches.length;
  }
}
