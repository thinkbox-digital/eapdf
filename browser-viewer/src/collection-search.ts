import type {BrowserDocument,BrowserSession} from './browser-session.js';
import {fileLabel} from './navigation.js';
import {textMatches} from './document-search.js';
export type SearchStep={fileId:string;streamPath:string};
export type CollectionMatch={path:SearchStep[];document:string;page:number;excerpt:string};
export async function searchCollection(session:BrowserSession,root:BrowserDocument,query:string,check:()=>void,progress:(pages:number)=>void):Promise<{matches:CollectionMatch[];incomplete:string[]}>{
  const matches:CollectionMatch[]=[],incomplete:string[]=[];let pages=0,retainedCharacters=0;let stopped=false;const deadline=performance.now()+120000;
  async function visit(doc:BrowserDocument,path:SearchStep[],name:string):Promise<void>{
    for(let page=1;page<=doc.pdf.numPages;page++){
      check();if(performance.now()>deadline){stopped=true;incomplete.push('Search time budget reached.');return;}
      const p=await doc.run(doc.pdf.getPage(page));
      try{const content=await doc.run(p.getTextContent());check();const value=content.items.map(i=>'str'in i?i.str:'').join(' ');pages++;progress(pages);
        const match=textMatches(value,query,1)[0];if(match){
          const excerpt=value.slice(Math.max(0,match.start-60),match.end+120);
          retainedCharacters+=name.length+excerpt.length+path.reduce((n,p)=>n+p.fileId.length+p.streamPath.length,0);
          if(retainedCharacters>10_000_000){stopped=true;incomplete.push('Search result memory budget reached.');return;}
          matches.push({path,document:name,page,excerpt});
        }
      }finally{p.cleanup();}
    }
    if(doc.inspection.collection.state==='missing')return;
    if(!doc.inspection.collection.value||doc.inspection.coverage.associatedFiles!=='present'){incomplete.push(`${name}: collection files could not be fully inspected.`);return;}
    for(const file of doc.inspection.files)for(const stream of file.streams){
      check();if(stopped)return;if(!/^application\/pdf(?:;|$)/i.test(stream.mimeType.value??'')&&!/\.pdf$/i.test(fileLabel(file)))continue;
      try{const child=await session.child(doc,file,stream);await visit(child,[...path,{fileId:file.id,streamPath:stream.locator.path}],name+' / '+fileLabel(file));}
      catch(error){check();incomplete.push(`${fileLabel(file)}: ${error instanceof Error?error.message:String(error)}`);}
    }
  }
  await visit(root,[],root.name);return {matches,incomplete};
}
