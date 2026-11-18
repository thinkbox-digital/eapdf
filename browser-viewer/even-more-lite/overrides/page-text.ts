// Keep selection and accessible PDF structure without search or highlighting.
import {boundedStructureTree} from './structure-tree.js';
import {pdfjs} from './pdfjs-adapter.js';
import type {PDFPageProxy,TextLayer} from 'pdfjs-dist';
export class PageText {
  private layer:TextLayer|undefined;
  private structure:HTMLElement|undefined;
  constructor(private readonly container:HTMLElement){}
  clear():void {
    this.structure?.remove();this.structure=undefined;this.layer?.cancel();this.layer=undefined;
    this.container.replaceChildren();delete this.container.dataset.structure;
  }
  async render(page:PDFPageProxy,scale:number):Promise<void>{
    this.clear();const viewport=page.getViewport({scale});
    this.container.style.setProperty('--total-scale-factor',String(scale*page.userUnit));
    const layer=new pdfjs.TextLayer({textContentSource:page.streamTextContent({includeMarkedContent:true}),container:this.container,viewport});
    this.layer=layer;await layer.render();
    try{
      const url=new URL('./vendor/pdfjs/pdf_viewer.mjs',import.meta.url).href;
      const {StructTreeLayerBuilder}=await import(url);
      if(this.layer!==layer)return;
      const structure=await page.getStructTree();if(this.layer!==layer)return;
      if(!boundedStructureTree(structure))throw Error('Structure exceeds display limit.');
      const builder=new StructTreeLayerBuilder({getStructTree:()=>Promise.resolve(structure)},viewport.rawDims),tree=await builder.render();
      if(this.layer!==layer)return;
      if(tree){builder.updateTextLayer();this.structure=tree;this.container.parentElement?.querySelector('canvas')?.append(tree);}
    }catch{this.container.dataset.structure='unavailable';}
  }
  highlight(_query?:string,_activeIndex?:number):number{return 0;}
}
