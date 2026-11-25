import {boundedStructureTree} from './structure-tree.js';
import {textMatches,pdfTextSeparator,type PdfTextFragment} from './document-search.js';
import type {PDFPageProxy,TextLayer} from 'pdfjs-dist';
import {pdfjs} from './pdfjs-adapter.js';
export class PageText {
  private eols:boolean[]=[];private gaps:string[]=[];
  private layer:TextLayer|undefined;
  private structure:HTMLElement|undefined;
  constructor(private readonly container:HTMLElement){}
  clear():void {this.eols=[];this.gaps=[];delete this.container.dataset.structure;this.structure?.remove();this.structure=undefined;this.layer?.cancel();this.layer=undefined;this.container.replaceChildren();}
  async render(page:PDFPageProxy,scale:number):Promise<void>{
    this.clear();const viewport=page.getViewport({scale});
    this.container.style.setProperty('--total-scale-factor',String(scale*page.userUnit));
    const eols:boolean[]=[],gaps:string[]=[];this.eols=eols;this.gaps=gaps;let previous:PdfTextFragment|undefined;
    const source=page.streamTextContent({includeMarkedContent:true}).pipeThrough(new TransformStream({
      transform(chunk,controller){
        for(const item of chunk.items)if('str'in item){
          gaps.push(pdfTextSeparator(previous,item));eols.push(item.hasEOL);
          if(item.hasEOL)previous=undefined;else if(item.str)previous=item;
        }
        controller.enqueue(chunk);
      },
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
    const text=values.map((str,i)=>(this.gaps[i]??'')+str+(this.eols[i]?'\n':'')).join('');
    const matches=textMatches(text,query);
    let start=0,firstMatch=0;
    values.forEach((value,index)=>{
      start+=(this.gaps[index]??'').length;const div=divs[index],end=start+value.length;
      if(!div){start=end+(this.eols[index]?1:0);return;}
      // Keep matches that continue into this fragment.
      while(firstMatch<matches.length&&matches[firstMatch]!.end<=start)firstMatch++;
      if(firstMatch===matches.length||matches[firstMatch]!.start>=end){
        div.textContent=value;start=end+(this.eols[index]?1:0);return;
      }
      const fragment=document.createDocumentFragment();let cursor=0;
      for(let i=firstMatch;i<matches.length&&matches[i]!.start<end;i++){
        const match=matches[i]!;
        const from=Math.max(0,match.start-start),to=Math.min(value.length,match.end-start);
        fragment.append(document.createTextNode(value.slice(cursor,from)));
        const mark=document.createElement('mark');
        mark.textContent=value.slice(from,to);
        if(i===activeIndex)mark.className='current-match';
        fragment.append(mark);cursor=to;
      }
      fragment.append(document.createTextNode(value.slice(cursor)));div.replaceChildren(fragment);start=end+(this.eols[index]?1:0);
    });
    this.container.querySelector('.current-match')?.scrollIntoView({block:'nearest',inline:'nearest'});
    return matches.length;
  }
}
