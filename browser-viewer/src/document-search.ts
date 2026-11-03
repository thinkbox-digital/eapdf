import type {PDFDocumentProxy} from 'pdfjs-dist';
import type {BrowserDocument} from './browser-session.js';
export type TextMatch={start:number;end:number};
// Keep offsets in the original string even when Unicode lowercasing expands a character.
export function textMatches(text:string,query:string,maximum=Infinity):TextMatch[]{
  const needle=query.trim().toLowerCase();if(!needle)return [];
  const folded=text.toLowerCase(),result:TextMatch[]=[];
  for(let from=0;result.length<maximum;){const at=folded.indexOf(needle,from);if(at<0)break;result.push({start:at,end:at+needle.length});from=at+needle.length;}
  if(folded.length!==text.length){
    // Map only match endpoints, not every character: hostile text must not cause
    // two document-sized JavaScript number arrays just to highlight a few hits.
    let original=0,lower=0,start=0,end=0;
    for(const character of text){const next=lower+character.toLowerCase().length;
      while(start<result.length&&result[start]!.start<next)result[start++]!.start=original;
      while(end<result.length&&result[end]!.end<=next)result[end++]!.end=original+character.length;
      original+=character.length;lower=next;if(end===result.length)break;
    }
  }
  return result;
}
export class DocumentSearch {
  private cache=new Map<string,string>();private characters=0;private pdf:PDFDocumentProxy|undefined;
  private cursor:{query:string;page:number;index:number}|undefined;
  clear():void{this.cache.clear();this.characters=0;this.pdf=undefined;this.cursor=undefined;}
  async find(doc:BrowserDocument,query:string,page:number,direction:1|-1,check:()=>void):Promise<{page:number;index:number;count:number}|{incomplete:boolean}>{
    if(this.pdf!==doc.pdf){this.clear();this.pdf=doc.pdf;}
    const normalized=query.trim().toLowerCase(),previous=this.cursor;
    const deadline=performance.now()+120000;
    for(let step=0;step<=doc.pdf.numPages;step++){
      check();if(performance.now()>deadline)return {incomplete:true};const number=((page-1+step*direction)%doc.pdf.numPages+doc.pdf.numPages)%doc.pdf.numPages+1;
      const key=String(number);let value=this.cache.get(key);
      if(value===undefined){
        const p=await doc.run(doc.pdf.getPage(number));
        try{const content=await doc.run(p.getTextContent());check();value=content.items.map(i=>'str'in i?i.str:'').join(' ');}
        finally{p.cleanup();}
        if(value.length<=4_000_000){while(this.characters+value.length>4_000_000&&this.cache.size){const first=this.cache.keys().next().value!;this.characters-=this.cache.get(first)!.length;this.cache.delete(first);}this.cache.set(key,value);this.characters+=value.length;}
      }
      const matches=textMatches(value,query);let index=direction===1?0:matches.length-1;
      if(step===0&&previous?.query===normalized&&previous.page===page)index=previous.index+direction;
      if(index>=0&&index<matches.length){this.cursor={query:normalized,page:number,index};return {page:number,index,count:matches.length};}
    }
    return {incomplete:false};
  }
}
