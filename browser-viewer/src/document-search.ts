import type {PDFDocumentProxy} from 'pdfjs-dist';
import type {BrowserDocument} from './browser-session.js';
export type TextMatch={start:number;end:number};
// Match the pinned PDFFindController: actual spaces belong to str, not item boundaries.
export function pdfSearchText(items:readonly ({str:string;hasEOL?:boolean}|object)[]):string{
  let text='';for(const item of items)if('str'in item&&typeof item.str==='string')text+=item.str+('hasEOL'in item&&item.hasEOL?'\n':'');return text;
}
// Keep this scalar normalization identical to FullTextSearchIndex.normalize.
const spaces=/[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/;
function* normalizedParts(text:string):Generator<{value:string;start:number;end:number}>{
  let offset=0,pending:{start:number;end:number}|undefined,emitted=false;
  for(let cursor=0;cursor<text.length;){
    const character=String.fromCodePoint(text.codePointAt(cursor)!);
    const start=cursor;cursor+=character.length;offset=cursor;
    // Upstream's broken-word rule: lower-case to lower-case or upper-case to letter.
    if(character==='-'&&text[cursor]==='\n'){
      const before=Array.from(text.slice(Math.max(0,start-2),start)).at(-1)??'',after=String.fromCodePoint(text.codePointAt(cursor+1)??0);
      if((/\p{Ll}/u.test(before)&&/\p{Ll}/u.test(after))||(/\p{Lu}/u.test(before)&&/\p{L}/u.test(after))){cursor++;continue;}
    }
    for(const c of character.normalize('NFKD').toLowerCase().replaceAll('ς','σ')){
      if(c==='\u00ad')continue;
      if(spaces.test(c)){if(emitted)pending={start:pending?.start??start,end:offset};continue;}
      if(pending){yield {value:' ',...pending};pending=undefined;}
      yield {value:c,start,end:offset};emitted=true;
    }
  }
}
export function normalizeSearchText(text:string):string{let result='';for(const part of normalizedParts(text))result+=part.value;return result;}
export function textMatches(text:string,query:string,maximum=Infinity):TextMatch[]{
  const needle=normalizeSearchText(query);if(!needle)return [];
  const folded=normalizeSearchText(text),result:TextMatch[]=[];
  for(let from=0;result.length<maximum;){const at=folded.indexOf(needle,from);if(at<0)break;result.push({start:at,end:at+needle.length});from=at+needle.length;}
  let offset=0,start=0,end=0;
  for(const part of normalizedParts(text)){
    const next=offset+part.value.length;
    while(start<result.length&&result[start]!.start<next)result[start++]!.start=part.start;
    while(end<result.length&&result[end]!.end<=next)result[end++]!.end=part.end;
    offset=next;if(end===result.length)break;
  }
  // Compatibility expansion can map several matches to the same source glyph.
  return result.filter((match,i)=>i===0||match.start>=result[i-1]!.end);
}
export class DocumentSearch {
  private cache=new Map<string,string>();private characters=0;private pdf:PDFDocumentProxy|undefined;
  private cursor:{query:string;page:number;index:number}|undefined;
  clear():void{this.cache.clear();this.characters=0;this.pdf=undefined;this.cursor=undefined;}
  async find(doc:BrowserDocument,query:string,page:number,direction:1|-1,check:()=>void):Promise<{page:number;index:number;count:number}|{incomplete:boolean}>{
    if(this.pdf!==doc.pdf){this.clear();this.pdf=doc.pdf;}
    const normalized=normalizeSearchText(query),previous=this.cursor;
    const deadline=performance.now()+120000;
    for(let step=0;step<=doc.pdf.numPages;step++){
      check();if(performance.now()>deadline)return {incomplete:true};const number=((page-1+step*direction)%doc.pdf.numPages+doc.pdf.numPages)%doc.pdf.numPages+1;
      const key=String(number);let value=this.cache.get(key);
      if(value===undefined){
        const p=await doc.run(doc.pdf.getPage(number));
        try{const content=await doc.run(p.getTextContent());check();value=pdfSearchText(content.items);}
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
