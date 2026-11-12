import {element,text,button} from './viewer-elements.js';
import {messageLabel} from './message-tree.js';
import {treeIcon} from './viewer-icons.js';
import {resolveStep,invalidateFullTextIndex,fullTextPageOwners} from './fulltext-index.js';
import {metadataSearchText} from './embedded-index.js';
import {indexMessages,type MessageIndex,type IndexedMessage} from './message-index.js';
import {searchCollection,searchIndexedCollection,type CollectionMatch} from './collection-search.js';
import {DocumentSearch,textMatches,pdfSearchText} from './document-search.js';
import {BrowserSession,type BrowserDocument} from './browser-session.js';
import {VirtualList} from './virtual-list.js';
import type {ScrollingPages} from './scrolling-pages.js';
import type {AssociatedFile,EmbeddedStream,MailMessage} from './domain/inspection.js';
import {messagePages} from './navigation.js';
type CollectionResults={root:BrowserDocument;query:string;result:Awaited<ReturnType<typeof searchCollection>>;index:number;occurrence:number;total:number;offsets:Float64Array};
interface SearchNavigation {
 readonly doc:BrowserDocument|undefined;readonly session:BrowserSession|undefined;
 page:number;readonly generation:number;readonly busy:boolean;
 operation(action:(ticket:number)=>Promise<void>):Promise<void>;
 check(ticket:number):void;
 openChild(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,ticket:number):Promise<void>;
 showDocument(doc:BrowserDocument,ticket:number,message?:MailMessage):Promise<void>;
 render(ticket:number,fromTop?:boolean):Promise<void>;
 syncMessageToPage():void;navigationTab(mode:'browse'|'results'):void;
}
interface SearchElements {search:HTMLInputElement;searchStatus:HTMLElement;status:HTMLElement;tree:HTMLElement;viewport:HTMLElement;pages:ScrollingPages;}

/** Owns search workers, result lists and metadata filters; navigation remains with the viewer. */
export class SearchController {
 private readonly finder=new DocumentSearch();private searchEpoch=0;
 private searchSession:BrowserSession|undefined;
 private collectionResults:CollectionResults|undefined;private collectionList:VirtualList<CollectionMatch>|undefined;
 readonly filter=element<HTMLInputElement>('message-filter');readonly filterResults=text('ul','');
 private filterList:VirtualList<IndexedMessage>|undefined;
 private filtered:{root:BrowserDocument;matches:IndexedMessage[];index:number;doc?:BrowserDocument}|undefined;
 private forceMetadataScan=false;private metadataSession:BrowserSession|undefined;private metadataIndex:Promise<MessageIndex>|undefined;
 private metadataEpoch=0;private filterRevision=0;private filterTimer:ReturnType<typeof setTimeout>|undefined;
 constructor(private readonly navigation:SearchNavigation,private readonly elements:SearchElements){this.filterResults.id='filter-results';this.filterResults.hidden=true;elements.tree.before(this.filterResults);}
clearSearch():void {
  this.searchEpoch++;this.searchSession?.close();this.collectionResults=undefined;this.finder.clear();this.elements.pages.highlight('');
  this.collectionList?.close();this.collectionList=undefined;element('collection-matches').replaceChildren();element('collection-search-summary').textContent='';this.elements.searchStatus.textContent='';
  element<HTMLButtonElement>('results-tab').disabled=true;this.navigation.navigationTab('browse');
}
async showCollectionMatch(cached:CollectionResults,index:number,occurrence:number,ticket:number):Promise<void>{
  const match=cached.result.matches[index];if(!match)return;
  const rejectIndex=():never=>{
    invalidateFullTextIndex(cached.root);this.collectionResults=undefined;this.elements.pages.highlight('');
    setTimeout(()=>{if(ticket===this.navigation.generation&&this.elements.search.value.trim()===cached.query.trim())void this.findInCollection(1,true);},0);
    throw new Error('Indexed destination disagrees with the PDF. Restarting with PDF.js search.');
  };
  let doc=cached.root;
  for(let depth=0;depth<(match.indexed?.route.length??match.path.length);depth++){
    const step=match.indexed?resolveStep(doc,match.indexed.route[depth]!):match.path[depth];
    if(!step)return rejectIndex();
    const file=doc.inspection.files.find(f=>f.id===step.fileId),stream=file?.streams.find(s=>s.locator.path===step.streamPath);
    if(!file||!stream){if(match.indexed)rejectIndex();throw new Error('Search destination is no longer available.');}
    if(this.navigation.doc?.parent===doc&&this.navigation.doc.parentFileId===step.fileId&&this.navigation.doc.parentStreamPath===step.streamPath)doc=this.navigation.doc;
    else{try{await this.navigation.openChild(doc,file,stream,ticket);doc=this.navigation.doc!;}catch(error){this.navigation.check(ticket);if(match.indexed)rejectIndex();throw error;}}
  }
  if(match.indexed){
    if(match.page<1||match.page>doc.pdf.numPages)rejectIndex();
    let verified=false;
    try{
      const page=await doc.run(doc.pdf.getPage(match.page));let value='';
      try{const content=await doc.run(page.getTextContent());value=pdfSearchText(content.items);}finally{page.cleanup();}
      this.navigation.check(ticket);const actual=textMatches(value,cached.query);
      verified=actual.length>0&&actual.length===match.count&&(fullTextPageOwners(doc).get(match.page)??'')===match.indexed.guid;
    }catch{this.navigation.check(ticket);}
    if(!verified)rejectIndex();
  }
  const wasFiltered=!!this.filteredPages(doc);this.clearMessageFilterForSearch();
  if(this.navigation.doc!==doc)await this.navigation.showDocument(doc,ticket);
  if(wasFiltered||this.navigation.page!==match.page){this.navigation.page=match.page;await this.navigation.render(ticket,false);}
  this.navigation.check(ticket);this.navigation.syncMessageToPage();const highlighted=this.elements.pages.highlight(cached.query,{page:match.page,index:occurrence});
  cached.index=index;cached.occurrence=occurrence;
  const number=cached.offsets[index]!+occurrence+1;
  this.collectionList?.rowAt(index);
  this.elements.searchStatus.textContent=`Match ${number} of ${cached.total} in collection${cached.result.incomplete.length?' (incomplete search)':''}.`;
  if(match.indexed&&highlighted!==match.count){this.elements.pages.highlight(cached.query);this.elements.searchStatus.textContent+=' Matching page verified; precise occurrence highlighting is unavailable for this text layout.';}
  for(const node of element('collection-matches').querySelectorAll<HTMLButtonElement>('button[data-match-index]')){
    if(Number(node.dataset.matchIndex)===index)node.setAttribute('aria-current','true');else node.removeAttribute('aria-current');
  }
  const active=element('collection-matches').querySelector<HTMLElement>('button[aria-current]');
  if(active&&!element('collection-results').hidden){
    const panel=element('collection-results'),bounds=panel.getBoundingClientRect(),box=active.getBoundingClientRect();
    const top=bounds.top;
    if(box.top<top)panel.scrollTop-=Math.ceil(top-box.top);
    else if(box.bottom>bounds.bottom)panel.scrollTop+=Math.ceil(box.bottom-bounds.bottom);
  }
}
async findInCollection(direction:1|-1=1,first=false):Promise<void>{
  if(!this.navigation.doc||!this.navigation.session||!this.elements.search.value.trim())return;
  await this.navigation.operation(async ticket=>{
    const query=this.elements.search.value.trim();let root=this.navigation.doc!;while(root.parent)root=root.parent;
    const cached=this.collectionResults;
    if(cached&&cached.root===root&&cached.query.toLowerCase()===query.toLowerCase()){
      if(!cached.result.matches.length){this.elements.searchStatus.textContent=cached.result.incomplete.length?'No matches found in an incomplete collection search.':'No matching text in this collection.';return;}
      let index=cached.index,occurrence=cached.occurrence+direction;
      if(index<0){index=direction===1?0:cached.result.matches.length-1;occurrence=direction===1?0:cached.result.matches[index]!.count-1;}
      else if(occurrence>=cached.result.matches[index]!.count){index=(index+1)%cached.result.matches.length;occurrence=0;}
      else if(occurrence<0){index=(index+cached.result.matches.length-1)%cached.result.matches.length;occurrence=cached.result.matches[index]!.count-1;}
      this.navigation.navigationTab('results');
      await this.showCollectionMatch(cached,first?0:index,first?0:occurrence,ticket);return;
    }
    this.collectionResults=undefined;
    const epoch=++this.searchEpoch;const checkSearch=()=>{this.navigation.check(ticket);if(epoch!==this.searchEpoch)throw new Error('Search cancelled.');};
    const main=this.navigation.session!,isolated=new BrowserSession({},{reuseWorker:true});this.searchSession=isolated;
    element('cancel-search').hidden=false;const list=element('collection-matches');this.collectionList?.close();this.collectionList=undefined;list.replaceChildren();element<HTMLButtonElement>('results-tab').disabled=false;this.navigation.navigationTab('results');
    element('collection-search-summary').textContent='Searching collection…';
    try{
      const fast=await searchIndexedCollection(main,root,query,checkSearch);let result=fast.result;
      if(!result){const blob=await main.sourceBlob(root);checkSearch();const copy=await isolated.open(blob,root.name);checkSearch();
        result=await searchCollection(isolated,copy,query,checkSearch,count=>{this.elements.searchStatus.textContent=`Searching collection · ${count} pages…`;},true);
        if(fast.diagnostic)result.notices=[fast.diagnostic];
      }checkSearch();
      const offsets=new Float64Array(result.matches.length+1);for(let i=0;i<result.matches.length;i++)offsets[i+1]=offsets[i]!+result.matches[i]!.count;
      const saved:CollectionResults={root,query,result,index:-1,occurrence:0,total:offsets[result.matches.length]!,offsets};this.collectionResults=saved;
      const groups=new Map<string,HTMLUListElement>(),virtual=result.matches.length>256;
      const create=(match:CollectionMatch,index:number)=>{
        const key=JSON.stringify(match.indexed?.route??match.path);let group=virtual?undefined:groups.get(key);
        const container=text('li','');
        if(!group){
          container.className='collection-result';
          const name=match.path.length?match.document.split(' / ').at(-1)!:'Collection overview';
          const heading=text('h3','');heading.className='result-heading';heading.title=name;heading.append(treeIcon('document'),text('span',name));
          group=text('ul','');group.className='result-pages';container.append(heading,group);
          if(!virtual){list.append(container);groups.set(key,group);}
        }
        const row=text('li',''),choose=button('',()=>void this.navigation.operation(async active=>{
          if(this.collectionResults!==saved)return;this.elements.search.value=saved.query;element<HTMLSelectElement>('search-scope').value='collection';
          await this.showCollectionMatch(saved,index,0,active);
        }));choose.className='collection-hit';choose.dataset.matchIndex=String(index);
        if(saved.index===index)choose.setAttribute('aria-current','true');
        const meta=text('span','');meta.className='match-meta';const selected=text('span','✓ Viewing');selected.className='match-selected';
        meta.append(text('span',`Page ${match.page} · ${match.count} ${match.count===1?'match':'matches'}`),selected);
        const excerpt=text('span','');excerpt.className='match-excerpt';let offset=0;excerpt.append(document.createTextNode('“'));
        for(const found of textMatches(match.excerpt,query)){
          excerpt.append(document.createTextNode(match.excerpt.slice(offset,found.start)),text('mark',match.excerpt.slice(found.start,found.end)));offset=found.end;
        }
        excerpt.append(document.createTextNode(match.excerpt.slice(offset)+'”'));
        choose.append(meta,excerpt);row.append(choose);group.append(row);return container;
      };
      if(virtual)this.collectionList=new VirtualList(list,element('collection-results'),result.matches,create,150);
      else result.matches.forEach(create);
      element('collection-search-summary').textContent=`${result.matches.length} matching pages.`;
      if(result.matches.length){const index=direction===1?0:result.matches.length-1;await this.showCollectionMatch(saved,index,direction===1?0:result.matches[index]!.count-1,ticket);}
      else this.elements.searchStatus.textContent=result.incomplete.length?'No matches found in an incomplete collection search.':'No matching text in this collection.';
    }catch(error){if(epoch!==this.searchEpoch){this.elements.searchStatus.textContent='Search cancelled.';element('collection-search-summary').textContent='Search cancelled; no complete result set was produced.';}else throw error;}
    finally{isolated.close();if(this.searchSession===isolated)this.searchSession=undefined;element('cancel-search').hidden=true;}
  });
}
async findMatch(direction:1|-1):Promise<void>{
  const query=this.elements.search.value.trim(),doc=this.navigation.doc;if(!query||!doc)return;
  await this.navigation.operation(async ticket=>{
    const epoch=++this.searchEpoch;const checkSearch=()=>{this.navigation.check(ticket);if(epoch!==this.searchEpoch)throw new Error('Search cancelled.');};
    element('cancel-search').hidden=false;this.elements.searchStatus.textContent='Searching this PDF…';
    try{await this.navigation.session!.ensure(doc);const result=await this.finder.find(doc,query,this.navigation.page,direction,checkSearch);checkSearch();
      if('page'in result){this.clearMessageFilterForSearch();this.navigation.page=result.page;await this.navigation.render(ticket);checkSearch();this.elements.pages.highlight(query,result);this.elements.searchStatus.textContent=`Match ${result.index+1} of ${result.count} on page ${result.page}`;}
      else this.elements.searchStatus.textContent=result.incomplete?'Search limit reached; results are incomplete.':'No matching text in this document.';
    }catch(error){if(epoch!==this.searchEpoch){this.elements.searchStatus.textContent='Search cancelled.';return;}throw error;}
    finally{element('cancel-search').hidden=true;}
  });
}
filteredPages(doc:BrowserDocument):number[]|undefined {
  if(!this.filtered||this.filtered.doc!==doc||!this.filter.value.trim())return;
  const item=this.filtered.matches[this.filtered.index],message=doc.inspection.messages.find(m=>item?.mailGuid?m.mailGuid.value===item.mailGuid:m.id===item?.messageId);
  const mapped=message?messagePages(doc.inspection,message):[];
  return mapped.length?mapped:undefined;
}
async openFiltered(index:number,ticket:number,edge?:-1|1):Promise<void>{
  const state=this.filtered,item=state?.matches[index];if(!state||!item)return;
  let doc=state.root;
  for(const step of item.path){const file=doc.inspection.files.find(f=>f.id===step.fileId),stream=file?.streams.find(s=>s.locator.path===step.streamPath);
    if(!file||!stream)throw new Error('Email destination is no longer available.');
    doc=await this.navigation.session!.child(doc,file,stream,false);this.navigation.check(ticket);
  }
  if(this.filtered!==state)return;
  const candidates=doc.inspection.messages.filter(m=>item.mailGuid?m.mailGuid.value===item.mailGuid:m.id===item.messageId);
  const message=candidates.length===1?candidates[0]:undefined;
  if(item.mailGuid&&(!message||doc.inspection.messages.length!==1||metadataSearchText(doc.details.get(message.metadataRecordId)??{})!==item.searchText)){
    this.forceMetadataScan=true;this.metadataIndex=undefined;this.navigation.session!.rememberMessageIndex(state.root,undefined);this.filtered=undefined;
    await this.filterMessages();throw new Error('Embedded index disagrees with the selected email. Search was rebuilt from child PDFs.');
  }
  if(!message)throw new Error('Email metadata is no longer available.');
  state.index=index;state.doc=doc;
  await this.navigation.showDocument(doc,ticket,message);
  if(edge===-1){const mapped=this.filteredPages(doc);this.navigation.page=mapped?.at(-1)??doc.pdf.numPages;await this.navigation.render(ticket,false);this.elements.viewport.scrollTop=this.elements.viewport.scrollHeight-this.elements.viewport.clientHeight;}
  for(const button of this.filterResults.querySelectorAll('button')){if(button.dataset.messageIndex===String(index))button.setAttribute('aria-current','true');else button.removeAttribute('aria-current');}
  this.elements.status.textContent+=` · Filtered email ${index+1} of ${state.matches.length}`;
}
async filterMessages():Promise<void>{
  this.filterList?.close();this.filterList=undefined;this.filterResults.replaceChildren();
  const query=this.filter.value.trim().toLowerCase();this.elements.tree.hidden=!!query;this.filterResults.hidden=!query;
  if(!query||!this.navigation.doc||!this.navigation.session)return;
  const ticket=this.navigation.generation,epoch=this.metadataEpoch,revision=this.filterRevision;let root=this.navigation.doc;while(root.parent)root=root.parent;
  const checkIndex=()=>{this.navigation.check(ticket);if(epoch!==this.metadataEpoch)throw new Error('Email search cancelled.');};
  const cachedIndex=this.forceMetadataScan?undefined:this.navigation.session.cachedMessageIndex(root);
  if(!this.metadataIndex&&cachedIndex)this.metadataIndex=Promise.resolve(cachedIndex);
  if(!this.metadataIndex&&!root.inspection.collection.value){
    this.metadataIndex=indexMessages(this.navigation.session,root,checkIndex,()=>{}).then(result=>{checkIndex();this.navigation.session!.rememberMessageIndex(root,result);return result;});
  }
  if(!this.metadataIndex){
    const main=this.navigation.session,isolated=new BrowserSession({},{reuseWorker:true});this.metadataSession=isolated;
    this.metadataIndex=(async()=>{try{
      const blob=await main.sourceBlob(root);checkIndex();const copy=await isolated.open(blob,root.name);checkIndex();
      return await indexMessages(isolated,copy,checkIndex,count=>{if(this.filter.value.trim())element('filter-status').textContent=`Reading email list · ${count} emails…`;},undefined,this.forceMetadataScan);
    }finally{isolated.close();if(this.metadataSession===isolated)this.metadataSession=undefined;}})();
  }
  element('filter-status').textContent='Finding emails…';
  try{
    const result=await this.metadataIndex;checkIndex();if(revision!==this.filterRevision||query!==this.filter.value.trim().toLowerCase())return;
    const matches=result.messages.filter(m=>textMatches(m.searchText,query).length>0);
    this.filtered={root,matches,index:-1};
    const matchIndices=new Map(matches.map((item,index)=>[item,index]));
    const create=(item:IndexedMessage)=>{const row=text('li',''),name=item.document.split(' / ').at(-1)!;
      const index=matchIndices.get(item)!,choose=button(item.label,()=>void this.navigation.operation(active=>this.openFiltered(index,active)));choose.dataset.messageIndex=String(index);if(this.filtered?.index===index)choose.setAttribute('aria-current','true');choose.title=name;choose.prepend(treeIcon('message'));messageLabel(choose,item.label,{From:item.sender??'',Sent:item.sent??''});row.append(choose);
      return row;};
    if(matches.length>256)this.filterList=new VirtualList(this.filterResults,this.elements.tree.closest('aside')!,matches,create,60);else for(const item of matches)this.filterResults.append(create(item));
    element('filter-status').textContent=`${matches.length} matching emails. ${result.incomplete.length?'Search incomplete: '+result.incomplete.join(' '):'All reachable email metadata searched.'} ${(result.notices??[]).join(' ')}`;
  }catch(error){if(ticket===this.navigation.generation&&epoch===this.metadataEpoch&&revision===this.filterRevision){this.metadataIndex=undefined;element('filter-status').textContent=`Email search could not finish: ${error instanceof Error?error.message:String(error)}`;}}
}
clearMessageFilterForSearch(reason='Email filter cleared to show whole-document search results.'):void {
  if(!this.filter.value && !this.filtered)return;
  this.filter.value='';this.filtered=undefined;this.filterRevision++;clearTimeout(this.filterTimer);
  this.filterList?.close();this.filterList=undefined;this.filterResults.replaceChildren();
  this.filterResults.hidden=true;this.elements.tree.hidden=false;
  element('filter-status').textContent=reason;
}

resetCursor():void {this.finder.resetCursor();}
documentChanged():void {this.finder.clear();}
get metadataWork():Promise<MessageIndex>|undefined{return this.metadataIndex;}
set metadataWork(work:Promise<MessageIndex>|undefined){this.metadataIndex=work;}
cancelSearch():void{this.searchEpoch++;this.searchSession?.close();this.elements.searchStatus.textContent='Cancelling search…';}
reset():void{
 this.metadataEpoch++;this.forceMetadataScan=false;this.metadataSession?.close();this.metadataSession=undefined;this.metadataIndex=undefined;clearTimeout(this.filterTimer);
 this.filterList?.close();this.filterList=undefined;this.filterResults.replaceChildren();this.filterResults.hidden=true;this.filter.value='';this.filtered=undefined;this.elements.tree.hidden=false;element('filter-status').textContent='';
 this.searchEpoch++;this.searchSession?.close();this.searchSession=undefined;this.collectionResults=undefined;this.collectionList?.close();this.collectionList=undefined;this.finder.clear();element('collection-matches').replaceChildren();
}
onFilterInput():void{const restricted=this.navigation.doc&&this.filteredPages(this.navigation.doc);this.filtered=undefined;if(restricted&&!this.navigation.busy)void this.navigation.operation(ticket=>this.navigation.render(ticket,false));this.filterRevision++;clearTimeout(this.filterTimer);if(!this.filter.value.trim()){this.filterList?.close();this.filterList=undefined;this.filterResults.replaceChildren();this.filterResults.hidden=true;this.elements.tree.hidden=false;element('filter-status').textContent='';return;}this.filterTimer=setTimeout(()=>void this.filterMessages(),200);}
scrollFiltered(direction:-1|1):boolean|undefined{
 const doc=this.navigation.doc;
 if(this.filtered&&this.filter.value.trim()){
 if(this.navigation.busy||!doc)return false;
 if(direction===1?this.elements.viewport.scrollTop+this.elements.viewport.clientHeight<this.elements.viewport.scrollHeight-2:this.elements.viewport.scrollTop>2)return false;
 const next=this.filtered.index+direction;
 if(this.filtered.index<0)return true;
 if(next<0||next>=this.filtered.matches.length){this.elements.status.textContent=direction===1?'End of filtered messages.':'Beginning of filtered messages.';return true;}
 void this.navigation.operation(ticket=>this.openFiltered(next,ticket,direction));return true;
 }
}
virtualNeighbor(target:HTMLElement,direction:-1|1):{consume:boolean;row?:HTMLElement}|undefined{
 if(this.collectionList&&target.matches('#collection-matches button[data-match-index]')){
 const index=Number(target.dataset.matchIndex)+direction;
 if(this.collectionResults&&index>=0&&index<this.collectionResults.result.matches.length)return {consume:true,row:this.collectionList.rowAt(index)?.querySelector<HTMLButtonElement>('button[data-match-index]')??undefined};
 return {consume:false};
 }
 if(this.filterList&&target.matches('#filter-results button[data-message-index]'))return {consume:true,row:this.filterList.rowAt(Number(target.dataset.messageIndex)+direction)?.querySelector<HTMLButtonElement>('button')??undefined};
}

}
