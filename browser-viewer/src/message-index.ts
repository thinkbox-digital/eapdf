import {readEmbeddedIndex} from './embedded-index.js';
import {metadataSearchText} from './metadata-search.js';
import type {BrowserDocument,BrowserSession} from './browser-session.js';
import {fileLabel} from './navigation.js';
import type {SearchStep} from './collection-search.js';
export type IndexedMessage={path:SearchStep[];messageId:string;mailGuid?:string;label:string;document:string;searchText:string;sender?:string;sent?:string;internetMessageId?:string;inReplyTo?:string;references?:string};
export type MessageIndex={messages:IndexedMessage[];incomplete:string[];notices?:string[];source?:'embedded'|'scanned'|'mixed'};
/** Retain only bounded display metadata; release the isolated PDF session after indexing. */
export async function indexMessages(session:BrowserSession,root:BrowserDocument,check:()=>void,progress:(count:number)=>void,discovered?:(messages:readonly IndexedMessage[])=>void,forceScan=false):Promise<MessageIndex>{
  const messages:IndexedMessage[]=[],incomplete:string[]=[],notices:string[]=[];let characters=0,stopped=false,embedded=false,scanned=false;
  const problem=(value:string)=>{incomplete.push(value);};
  async function visit(doc:BrowserDocument,path:SearchStep[],name:string):Promise<void>{
    check();if(stopped)return;
    const i=doc.inspection;
    if(i.collection.value&&!forceScan){
      const indexed=await readEmbeddedIndex(doc,check);check();
      if(indexed.diagnostic)notices.push(indexed.diagnostic);
      if(indexed.messages){
        const batch:IndexedMessage[]=[];
        for(const item of indexed.messages){
          const value={...item,path:[...path,...item.path],document:name+item.document.slice(doc.name.length)};
          characters+=value.label.length+value.document.length+value.searchText.length;
          if(characters>8_000_000){problem('Email metadata search limit reached.');stopped=true;return;}
          batch.push(value);
        }
        messages.push(...batch);embedded=true;discovered?.(batch);progress(messages.length);return;
      }
    }
    scanned=true;
    if(!['present','missing'].includes(i.coverage.metadata)||i.diagnostics.some(d=>/^XMP_.*LIMIT$/.test(d.code)||['XMP_RECORD_STRUCTURE','XMP_DISPLAY_VALUE_UNAVAILABLE','XMP_DISPLAY_VALUE_AMBIGUOUS'].includes(d.code)))problem(`${name}: some email metadata could not be read.`);
    if(!['present','missing'].includes(i.collection.state)){problem(`${name}: collection structure could not be read.`);return;}
    if(!i.collection.value){
      const first=messages.length;
      for(const m of i.messages){
        check();const data=doc.details.get(m.metadataRecordId)??{};
        const label=data.Subject||doc.name,searchText=metadataSearchText(data);
        characters+=label.length+name.length+searchText.length;
        if(characters>8_000_000){problem('Email metadata search limit reached.');stopped=true;return;}
        messages.push({path,messageId:m.id,label,document:name,searchText,sender:data.From||data['Raw-From'],sent:data.Sent,internetMessageId:m.internetMessageId?.value??undefined,inReplyTo:data['In-Reply-To'],references:data.References});
        if(messages.length%250===0){progress(messages.length);await new Promise(resolve=>setTimeout(resolve,0));check();}
      }
      discovered?.(messages.slice(first));
    }else{
      if(i.coverage.associatedFiles!=='present'){problem(`${name}: collection files could not be fully read.`);return;}
      const members=new Set(i.collection.value.entries.map(e=>e.fileId)),available=new Set(i.files.map(f=>f.id));let count=0;
      if([...members].some(id=>!available.has(id)))problem(`${name}: some collection members are unavailable.`);
      for(const file of i.files){
        if(!members.has(file.id))continue;
        if(!file.streams.length&&/\.pdf$/i.test(fileLabel(file)))problem(`${fileLabel(file)}: embedded PDF is missing.`);
        for(const stream of file.streams){
          check();if(stopped)return;
          if(stream.mimeType.value?.split(';')[0]?.toLowerCase()!=='application/pdf'&&!/\.pdf$/i.test(fileLabel(file)))continue;
          count++;
          try{const child=await session.child(doc,file,stream,false);
            try{check();await visit(child,[...path,{fileId:file.id,streamPath:stream.locator.path}],name+' / '+fileLabel(file));}
            finally{await session.release(child);}}
          catch(error){check();problem(`${fileLabel(file)}: ${error instanceof Error?error.message:String(error)}`);}
        }
      }
      if(!count)problem(`${name}: no readable collection PDFs were found.`);
    }
    progress(messages.length);
  }
  await visit(root,[],root.name);check();return {messages,incomplete,notices,source:embedded?(scanned?'mixed':'embedded'):'scanned'};
}
