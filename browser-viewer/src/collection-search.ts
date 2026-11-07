import {readFullTextIndex,queryFullTextIndex} from './fulltext-index.js';
import type {BrowserDocument,BrowserSession} from './browser-session.js';
import {fileLabel} from './navigation.js';
import {textMatches,pdfSearchText} from './document-search.js';
export type SearchStep={fileId:string;streamPath:string};
export type CollectionMatch={path:SearchStep[];document:string;page:number;count:number;excerpt:string;indexed?:{route:string[][];guid:string;textHash:string;md5:string;size:number}};
export async function searchCollection(session:BrowserSession,root:BrowserDocument,query:string,check:()=>void,progress:(pages:number)=>void,forceScan=false,maximumMilliseconds=120000):Promise<CollectionSearchResult>{
  const fast=forceScan?{}:await searchIndexedCollection(session,root,query,check);if(fast.result)return fast.result;
  const matches:CollectionMatch[]=[],incomplete:string[]=[];let pages=0,retainedCharacters=0;let stopped=false;const deadline=performance.now()+Math.min(1800000,Math.max(1,maximumMilliseconds));
  async function visit(doc:BrowserDocument,path:SearchStep[],name:string):Promise<void>{
    for(let page=1;page<=doc.pdf.numPages;page++){
      check();if(performance.now()>deadline){stopped=true;incomplete.push('Search time budget reached.');return;}
      const p=await doc.run(doc.pdf.getPage(page));
      try{const content=await doc.run(p.getTextContent());check();const value=pdfSearchText(content.items);pages++;progress(pages);
        const occurrences=textMatches(value,query),match=occurrences[0];if(match){
          const excerpt=value.slice(Math.max(0,match.start-60),match.end+120);
          retainedCharacters+=name.length+excerpt.length+path.reduce((n,p)=>n+p.fileId.length+p.streamPath.length,0);
          if(retainedCharacters>10_000_000){stopped=true;incomplete.push('Search result memory budget reached.');return;}
          matches.push({path,document:name,page,count:occurrences.length,excerpt});
        }
      }finally{p.cleanup();}
    }
    if(doc.inspection.collection.state==='missing')return;
    if(!doc.inspection.collection.value||doc.inspection.coverage.associatedFiles!=='present'){incomplete.push(`${name}: collection files could not be fully inspected.`);return;}
    const members=new Set(doc.inspection.collection.value.entries.map(e=>e.fileId));
    const available=new Set(doc.inspection.files.map(f=>f.id));
    if([...members].some(id=>!available.has(id)))incomplete.push(`${name}: some collection members are unavailable.`);
    for(const file of doc.inspection.files){
      if(!members.has(file.id))continue;
      if(!file.streams.length&&/\.pdf$/i.test(fileLabel(file)))incomplete.push(`${fileLabel(file)}: embedded PDF is missing.`);
      for(const stream of file.streams){
      check();if(stopped)return;if(!/^application\/pdf(?:;|$)/i.test(stream.mimeType.value??'')&&!/\.pdf$/i.test(fileLabel(file)))continue;
      try{const child=await session.child(doc,file,stream,false);
        try{check();await visit(child,[...path,{fileId:file.id,streamPath:stream.locator.path}],name+' / '+fileLabel(file));}
        finally{await session.release(child);}}
      catch(error){check();incomplete.push(`${fileLabel(file)}: ${error instanceof Error?error.message:String(error)}`);}
      }
    }
  }
  await visit(root,[],root.name);return {matches,incomplete,notices:fast.diagnostic?[fast.diagnostic]:[],source:'scanned'};
}

export type CollectionSearchResult={matches:CollectionMatch[];incomplete:string[];notices?:string[];source?:'indexed'|'scanned'};
export async function searchIndexedCollection(session:BrowserSession,root:BrowserDocument,query:string,check:()=>void):Promise<{result?:CollectionSearchResult;diagnostic?:string}>{
 const loaded=await readFullTextIndex(session,root,check);if(!loaded.index)return {diagnostic:loaded.diagnostic};
 try{return {result:{matches:await queryFullTextIndex(loaded.index,root,query,check),incomplete:[],notices:['Search uses normalized PDFBox text. PDF.js reading order can differ; selected matches are verified on the page.'],source:'indexed'}};}
 catch(error){check();return {diagnostic:`Indexed query unavailable: ${error instanceof Error?error.message:String(error)}. Using PDF.js search.`};}
}
