import {SearchController} from './search-controller.js';
import {MessageTree,type PdfPlacement} from './message-tree.js';
import {FilesPanel} from './files-panel.js';
import {element,text,button,visiblePaneControls} from './viewer-elements.js';
import {isPdf,canView,sizeLabel} from './attachment-types.js';
import {ThreadPreview} from './thread-preview.js';
import {openingDestination,type OpeningDestination} from './opening-destination.js';
import {checkCollectionNames} from './collection-names.js';
import {messageDownload,messagePdf} from './message-download.js';
import {archiveStats} from './archive-stats.js';
import {setupPaneResizers} from './pane-resizer.js';
import {treeIcon} from './viewer-icons.js';
import {detectDocument,PortfolioModel,type PortfolioRow} from './portfolio.js';
import {resolveEmbeddedLink,type EmbeddedAction} from './embedded-navigation.js';
import {PortfolioView} from './portfolio-view.js';
import {readEmbeddedIndex} from './embedded-index.js';
import {saveBlob} from './downloads.js';
import {indexMessages,type IndexedMessage} from './message-index.js';
import {BrowserSession,type BrowserDocument} from './browser-session.js';
import {AttachmentViewer} from './attachment-viewer.js';
import {ScrollingPages} from './scrolling-pages.js';
import type {AssociatedFile,EmbeddedStream,MailMessage} from './domain/inspection.js';
import {fileLabel,messagePages,folderFiles} from './navigation.js';
import {metadataCsv,type CsvOptions} from './metadata-csv.js';
import {MetadataDialog} from './metadata-dialog.js';
import {setupLinkedOpening} from './linked-opening.js';

const fileInput=element<HTMLInputElement>('file'),pageInput=element<HTMLInputElement>('page'),zoom=element<HTMLSelectElement>('zoom');
const status=element('status'),tree=element('tree'),files=element('files');
const surface=element('page-surface'),viewport=element('viewport'),empty=element('empty-state');
const search=element<HTMLInputElement>('search'),searchStatus=element('search-status');
const attachmentViewer=new AttachmentViewer();
let portfolioView:PortfolioView|undefined,portfolioDocument:BrowserDocument|undefined;
const initializedViews=new WeakSet<BrowserDocument>();
const openingWarnings=new WeakMap<BrowserDocument,string>();
const openingPlans=new WeakMap<BrowserDocument,OpeningDestination>();
const portfolioModels=new WeakMap<BrowserDocument,PortfolioModel>();
let portfolioReturn:{doc:BrowserDocument;page:number;message?:string}|undefined;
const failedPages=new Set<number>();
const pages=new ScrollingPages(viewport,surface,number=>{
  pageNumber=number;syncMessageToPage();if(current){savedPages.set(current,number);status.textContent=`${current.name} · Page ${number} of ${current.pdf.numPages}`;}controls();
},message=>{status.textContent=`Unable to display PDF: ${message}`;},(number,failed)=>{
  if(failed)failedPages.add(number);else failedPages.delete(number);controls();
},action=>{if(current){const source=current;void operation(ticket=>followEmbeddedLink(source,action,ticket));}});
pages.onAttachment=(page,annotation)=>{const doc=current;if(!doc)return;void operation(async ticket=>{
  const ref=typeof annotation==='string'?/^(\d+)R(\d+)?$/.exec(annotation):null;
  const target=ref?{target:`object:${ref[1]}:${ref[2]??0}`}:await doc.run(doc.pdf.getDocumentAssets({embeddedTarget:{page:page-1,annotation}}));check(ticket);
  const file=doc.inspection.files.find(f=>f.origins.some(o=>o.kind==='annotation'&&o.locator.path===target.target));const stream=file?.streams[0];
  if(!file||!stream)throw Error('Attachment content is unavailable.');
  if(isPdf(file,stream))await openChild(doc,file,stream,ticket);
  else {const bytes=await session!.bytes(doc,file,stream);check(ticket);if(canView(file,stream))await attachmentViewer.open(bytes,fileLabel(file),stream.mimeType.value??'',()=>{});else await saveBlob(new Blob([new Uint8Array(bytes)]),fileLabel(file),()=>check(ticket));}
});};
let cancelLinked=()=>{};
const threadPreview=new ThreadPreview(()=>{
 if(!current||!session||busy)return;const active=session,ticket=generation;let root=current;while(root.parent)root=root.parent;
 return {selection:{doc:current,message:selectedMessage(current)},root,session:active,check:()=>check(ticket),open:async (item,edge)=>{if(busy)throw Error('Please wait for the current message to finish opening.');await operation(async token=>{
  let doc=root;for(const step of item.path){const file=doc.inspection.files.find(f=>f.id===step.fileId),stream=file?.streams.find(s=>s.locator.path===step.streamPath);if(!file||!stream)throw Error('Conversation destination unavailable.');doc=await active.child(doc,file,stream,false);check(token);}
  const matches=doc.inspection.messages.filter(m=>item.mailGuid?m.mailGuid.value===item.mailGuid:m.id===item.messageId);if(matches.length!==1)throw Error('Conversation destination is ambiguous.');await showDocument(doc,token,matches[0]);
  if(edge===-1){const range=messagePages(doc.inspection,matches[0]!);pageNumber=range.at(-1)??doc.pdf.numPages;await render(token,false);viewport.scrollTop=viewport.scrollHeight-viewport.clientHeight;}else if(edge===1)viewport.scrollTop=0;
 });}};
});

const selectedMessage=(doc:BrowserDocument)=>doc.inspection.messages.find(m=>m.id===savedMessages.get(doc));
function displayedMessageDownload(doc:BrowserDocument) {
  let message=selectedMessage(doc);
  if(!message&&doc.inspection.messages.length>1){
    const owners=new Set(doc.inspection.parts.filter(part=>part.dpm.Mail_ContentSetType?.value==='BodyRendering'&&part.pageRange.state==='present'&&part.pageRange.value.start<pageNumber&&part.pageRange.value.end>=pageNumber-1).flatMap(part=>part.messageIds.value??[]));
    if(owners.size===1)message=doc.inspection.messages.find(m=>owners.has(m.id));
  }
  return messageDownload(doc,message,!!portfolioView);
}
let exportSession:BrowserSession|undefined;
let cancelExport:(()=>void)|undefined;
const exportDialog=element<HTMLDialogElement>('export-dialog');
const metadataDialog=new MetadataDialog();
element('export-cancel').addEventListener('click',()=>cancelExport?.());
exportDialog.addEventListener('cancel',event=>{event.preventDefault();cancelExport?.();});

let browseSession:BrowserSession|undefined;
let browseEpoch=0;
const browseCompleted=new WeakSet<BrowserDocument>();
const browseMessageCounts=new WeakMap<BrowserDocument,number>();

let session:BrowserSession|undefined,current:BrowserDocument|undefined,busy=false,generation=0,pageNumber=1,editingPage=false,renderedZoom=1;
let renderedViewportWidth=0,renderedViewportHeight=0;
const savedMessages=new Map<BrowserDocument,string|undefined>(),savedPages=new Map<BrowserDocument,number>();
const messageTree=new MessageTree(tree,{
  busy:()=>busy,singleMessage,
  openDocument:(doc,message,node,fromTop)=>void operation(ticket=>showDocument(doc,ticket,message,node,undefined,fromTop)),
  openEmbedded:(doc,file,stream,placement)=>void operation(ticket=>openChild(doc,file,stream,ticket,undefined,placement))
});
const searchController=new SearchController({
 get doc(){return current;},get session(){return session;},get page(){return pageNumber;},set page(value){pageNumber=value;},
 get generation(){return generation;},get busy(){return busy;},operation,check,openChild,showDocument,render,syncMessageToPage,navigationTab
},{search,searchStatus,status,tree,viewport,pages});
const {filter,filterResults}=searchController;
const pageOwners=new WeakMap<BrowserDocument,Map<number,MailMessage|null>>();
// PDF points use 72 units/inch; browser CSS uses 96.
const PDF_TO_CSS=96/72;

function collectionOverview(doc:BrowserDocument|undefined):boolean{return !!doc?.inspection.collection.value&&['c','ci'].includes(doc.inspection.identification.declaredProfile.value??'')&&!portfolioView;}
function controls():void {
  fileInput.disabled=false;const available=!!current;
  portfolioView?.setBusy(busy);
  element<HTMLButtonElement>('portfolio-toggle').disabled=element<HTMLButtonElement>('portfolio-show').disabled=busy;
  const scope=element<HTMLSelectElement>('search-scope');scope.disabled=busy||!available;scope.options[0]!.disabled=collectionOverview(current);if(collectionOverview(current))scope.value='collection';
  pageInput.disabled=zoom.disabled=search.disabled=element<HTMLButtonElement>('find').disabled=busy||!available;
  element<HTMLButtonElement>('previous').disabled=busy||!available||pageNumber<=1;
  element<HTMLButtonElement>('next').disabled=busy||!available||pageNumber>=(current?.pdf.numPages??0);
  element<HTMLButtonElement>('export-csv').disabled=busy||!available;
  element('retry').hidden=!available||!failedPages.has(pageNumber);
  for(const id of ['retry','restart','find-previous','search-submit'])element<HTMLButtonElement>(id).disabled=busy||!available;
  element<HTMLButtonElement>('back').disabled=busy||!current?.parent;
  element<HTMLButtonElement>('zoom-out').disabled=busy||!available||(zoom.value.startsWith('fit-')?renderedZoom:Number(zoom.value))<=0.5;
  element<HTMLButtonElement>('zoom-in').disabled=busy||!available||(zoom.value.startsWith('fit-')?renderedZoom:Number(zoom.value))>=2;
  element<HTMLButtonElement>('close').disabled=!session;
  const download=element<HTMLButtonElement>('download-message'),plan=current?displayedMessageDownload(current):undefined;
  download.disabled=busy||!plan;
  for(const node of download.childNodes)if(node.nodeType===Node.TEXT_NODE)node.textContent=portfolioView?'Download active file':'Download this message';
  download.title=portfolioView&&plan?`Download ${plan.name} as its original PDF`:plan?(plan.pages?'Download this message’s pages as a PDF':'Download this message as its original PDF'):'Select a message to download as a PDF';
  for(const b of document.querySelectorAll<HTMLButtonElement>('#tree button,#files button,#breadcrumbs button'))b.disabled=busy||b.dataset.unavailable==='true';
  if(!editingPage||document.activeElement!==pageInput){pageInput.value=String(pageNumber);pageInput.removeAttribute('aria-invalid');editingPage=false;}
  for(const id of ['previous','next','back','zoom-in','zoom-out']){const control=element<HTMLButtonElement>(id);control.setAttribute('aria-disabled',String(control.disabled));}
  element<HTMLButtonElement>('open-search').disabled=!available;
  pageInput.style.width=`calc(${Math.max(4,String(current?.pdf.numPages??0).length)}ch + 1.5rem)`;
  element('pages').textContent=`of ${current?.pdf.numPages??0}`;
  viewport.setAttribute('aria-busy',String(busy));
}
function check(ticket:number):void {if(ticket!==generation)throw new Error('Cancelled.');}
let keyboardSelection: (()=>HTMLElement|null|undefined)|undefined;
let pendingOpen:{file:Blob;name:string}|undefined;
async function operation(action:(ticket:number)=>Promise<void>):Promise<void>{
  if(busy)return;const ticket=generation;
  // Disabling a clicked tree button blurs it. Embedded PDFs also replace the row.
  // Restore the selected row after rendering so the next arrow key reaches Messages.
  const focused=document.activeElement;
  if(!keyboardSelection&&focused instanceof HTMLElement&&tree.contains(focused))
    keyboardSelection=()=>tree.querySelector<HTMLElement>('button[aria-current]')??(focused.isConnected?focused:undefined);
  if(!keyboardSelection&&focused instanceof HTMLElement&&focused.matches('#portfolio-panel [data-portfolio-row]'))keyboardSelection=()=>element('portfolio-panel').querySelector<HTMLElement>('button[aria-current]');
  busy=true;controls();
  try{await action(ticket);}catch(error){if(ticket===generation){element('restart').hidden=!current;status.textContent=`Unable to display PDF: ${error instanceof Error?error.message:String(error)}`;}}
  finally{if(ticket===generation){busy=false;controls();const focus=keyboardSelection;keyboardSelection=undefined;focus?.()?.focus({preventScroll:true});if(pendingOpen)queueMicrotask(()=>{if(!busy&&pendingOpen){const next=pendingOpen;pendingOpen=undefined;void openFile(next.file,next.name);}});}}
}
const loadingDialog=element<HTMLDialogElement>('loading-dialog');
let loadingTimer:ReturnType<typeof setTimeout>|undefined;
function finishLoading():void {
  clearTimeout(loadingTimer);loadingTimer=undefined;
  loadingDialog.close();tree.hidden=false;
}
element('loading-cancel').addEventListener('click',()=>close());
loadingDialog.addEventListener('cancel',event=>{event.preventDefault();close();});
function close():void {
  pendingOpen=undefined;
  threadPreview.reset();finishLoading();metadataDialog.close();cancelExport?.();
  portfolioView?.close();portfolioView=undefined;portfolioDocument=undefined;portfolioReturn=undefined;
  element('portfolio-toggle').hidden=element('portfolio-show').hidden=true;delete document.body.dataset.documentKind;
  searchController.reset();keyboardSelection=undefined;
  messageTree.closeLists();filesPanel.close();
  cancelLinked();stopBrowseIndex();element('subject-index-progress').hidden=true;exportSession?.close();exportSession=undefined;
  generation++;failedPages.clear();pages.clear();attachmentViewer.close();session?.close();session=undefined;current=undefined;
  messageTree.clear();savedPages.clear();savedMessages.clear();busy=false;pageNumber=1;editingPage=false;
  element('restart').hidden=true;empty.hidden=false;element('collection-results').hidden=true;element('collection-matches').replaceChildren();element('outline').replaceChildren();element('outline-panel').hidden=true;
  for(const panel of [tree,files,element('breadcrumbs')])panel.replaceChildren();
  element('declared-profile').textContent='';element('declared-profile').hidden=true;
  element('archive-stats').hidden=true;
  element('header-filename').textContent='';element('header-filename').title='';element('message-count').textContent='0';element('selection-details').replaceChildren();element('files-title').textContent='Files in this PDF';closeSearch(false);
  element('file-count').textContent='0';search.value='';searchStatus.textContent='';element('document-notice').hidden=true;element('navigation-title').textContent='Messages';element('export-csv').hidden=false;
  element<HTMLButtonElement>('results-tab').disabled=true;navigationTab('browse');
  status.textContent='Closed. Open or drop a PDF to begin.';controls();
}
async function pageScale(doc:Pick<BrowserDocument,'pdf'|'run'>,number:number):Promise<number>{
  if(!zoom.value.startsWith('fit-'))return Number(zoom.value)*PDF_TO_CSS;
  const page=await doc.run(doc.pdf.getPage(number));
  try{
    const size=page.getViewport({scale:1}),style=getComputedStyle(viewport);
    const width=viewport.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight)-2;
    const height=viewport.clientHeight-parseFloat(style.paddingTop)-parseFloat(style.paddingBottom)-2;
    const scale=width/size.width;
    return Math.max(.01,zoom.value==='fit-page'?Math.min(scale,height/size.height):scale);
  }finally{page.cleanup();}
}
async function render(ticket:number,followMessage=true):Promise<void>{
  const doc=current;if(!doc||!session)return;
  failedPages.clear();controls();
  try{
    await session.ensure(doc);check(ticket);
    status.textContent=`Rendering page ${pageNumber}…`;
    const range=searchController.filteredPages(doc);if(range)pageNumber=Math.max(range[0]!,Math.min(range.at(-1)!,pageNumber));
    const scale=await pageScale(doc,pageNumber);check(ticket);
    renderedViewportWidth=viewport.clientWidth;renderedViewportHeight=viewport.clientHeight;
    empty.hidden=true;await pages.show(doc,scale,pageNumber,search.value,true,searchController.filteredPages(doc));check(ticket);renderedZoom=scale/PDF_TO_CSS;
    empty.hidden=true;status.textContent=`${doc.name} · Page ${pageNumber} of ${doc.pdf.numPages}`;savedPages.set(doc,pageNumber);if(followMessage)syncMessageToPage();
  }catch(error){if(ticket===generation&&current===doc)failedPages.add(pageNumber);throw error;}
}

function markSelection(node:HTMLElement|undefined):void {messageTree.markSelection(node);}
// Update selection only: following the page must never navigate back to a
// message's first page. Cache ownership instead of rescanning DParts on scroll.
function syncMessageToPage():void {
  const doc=current;
  if(!doc||doc.inspection.collection.value||doc.inspection.messages.length<2)return;
  let owners=pageOwners.get(doc);
  if(!owners){
    owners=new Map();
    for(const message of doc.inspection.messages)for(const page of messagePages(doc.inspection,message)){
      const existing=owners.get(page);owners.set(page,existing===undefined?message:existing===message?message:null);
    }
    pageOwners.set(doc,owners);
  }
  const message=owners.get(pageNumber)??undefined;
  const row=messageTree.messageRow(doc,message);
  const changed=savedMessages.get(doc)!==message?.id;
  if(changed){savedMessages.set(doc,message?.id);showFiles(doc,message);}
  if(!row||(!changed&&row.hasAttribute('aria-current')))return;
  markSelection(row);
  if(tree.hidden||element('browse-panel').hidden||element('navigation-panel').hidden)return;
  // Scroll just the navigation panel; leave the PDF and keyboard focus alone.
  const panel=tree.closest('aside')!,bounds=panel.getBoundingClientRect(),box=row.getBoundingClientRect();
  const top=bounds.top+(panel.querySelector('.panel-heading')?.getBoundingClientRect().height??0)+12;
  if(box.top<top)panel.scrollTop-=Math.ceil(top-box.top);
  else if(box.bottom>bounds.bottom)panel.scrollTop+=Math.ceil(box.bottom-bounds.bottom)+8;
}
function breadcrumbs(doc:BrowserDocument):void {
  const bar=element('breadcrumbs');bar.replaceChildren();const path:BrowserDocument[]=[];
  for(let d:BrowserDocument|null=doc;d;d=d.parent)path.unshift(d);
  path.forEach((d,i)=>{
    if(d.parent&&d.parentFileId)for(const folder of portfolioModels.get(d.parent)?.folderPath(d.parentFileId)??[]){
      bar.append(text('span','›'));const segment=text('span',folder);segment.className='breadcrumb-folder';bar.append(segment);
    }
    if(i)bar.append(text('span','›'));const b=button(d.name,()=>void operation(async ticket=>{await showDocument(d,ticket);if(portfolioView)portfolioReturn=undefined;if(portfolioView&&d.inspection.collection.state==='present'&&d!==portfolioDocument)await activatePortfolio(d,ticket);}));b.title=d.name;bar.append(b);});
}
function showInformation(doc:BrowserDocument):void {
  const id=doc.inspection.identification,profile=element('declared-profile');
  profile.textContent=id.declaredProfile.value?`EA-PDF ${id.eapdfVersion.value??'?'}${id.declaredProfile.value}`:'No EA-PDF profile declared';profile.hidden=false;
  for(const diagnostic of doc.inspection.diagnostics)console.debug('[EA-PDF inspection]',doc.name,diagnostic);
  breadcrumbs(doc);
  let root=doc;while(root.parent)root=root.parent;
  const rootDetection=detectDocument(root.inspection),detection=rootDetection.kind==='eapdf'||portfolioView?rootDetection:detectDocument(doc.inspection);document.body.dataset.documentKind=detection.kind;
  element('navigation-title').textContent=detection.kind==='eapdf'?'Messages':'Contents';
  element('navigation-panel').setAttribute('aria-label',detection.kind==='eapdf'?'Messages and collections':'Document contents');
  element('export-csv').hidden=detection.kind!=='eapdf';
  element('portfolio-toggle').hidden=!rootDetection.collection&&!detection.collection;
  element('portfolio-toggle').textContent=portfolioView?(detectDocument((portfolioReturn?.doc??doc).inspection).kind==='eapdf'?'Email view':'PDF view'):'Portfolio view';
  element('portfolio-show').hidden=!portfolioView?.hidden;
  const notice=detectDocument(doc.inspection).notice||detection.notice||openingWarnings.get(doc)||'';
  element('document-notice').textContent=notice;element('document-notice').hidden=!notice;
  if(detection.kind!=='eapdf')profile.textContent=detection.kind==='portfolio'?'PDF Portfolio':'PDF';
  element('header-filename').textContent=root.name;element('header-filename').title=root.name;
  element('message-count').textContent=String(browseMessageCounts.get(root)??root.inspection.messages.length);
  element('archive-stats').hidden=true;
  void archiveStats(root).then(stats=>{let shown=current;while(shown?.parent)shown=shown.parent;if(shown!==root)return;element('archive-stats').hidden=!stats;element('archive-stats').textContent=stats??'';});
}
function refreshInspection(doc:BrowserDocument):void {
  if(current!==doc)return;showInformation(doc);showFiles(doc,singleMessage(doc)?undefined:selectedMessage(doc));
  const mismatches=doc.inspection.diagnostics.filter(d=>d.code==='CHECKSUM_MISMATCH');
  if(mismatches.length){status.textContent='Warning: an embedded file differs from its recorded checksum.';}
}
const filesPanel=new FilesPanel({files,count:element('file-count'),title:element('files-title'),details:element('selection-details')},{
  busy:()=>busy,conversationsEnabled:()=>threadPreview.enabled,
  index:doc=>session?.cachedMessageIndex(doc)?.messages,singleMessage,
  download:(doc,file,stream)=>void operation(async ticket=>{checkCollectionNames(doc.inspection);status.textContent=`Reading ${fileLabel(file)}…`;const data=await session!.bytes(doc,file,stream);check(ticket);await saveBlob(new Blob([new Uint8Array(data)],{type:'application/octet-stream'}),fileLabel(file),()=>check(ticket));check(ticket);refreshInspection(doc);status.textContent=`Download requested: ${fileLabel(file)} (${sizeLabel(data.length)}).${doc.inspection.diagnostics.some(d=>d.code==='CHECKSUM_MISMATCH')?' Warning: recorded checksum mismatch.':''}`;}),
  view:(doc,file,stream)=>void operation(async ticket=>{
          if(isPdf(file,stream)){await openChild(doc,file,stream,ticket);return;}
          checkCollectionNames(doc.inspection);status.textContent=`Reading ${fileLabel(file)}…`;const data=await session!.bytes(doc,file,stream);check(ticket);refreshInspection(doc);await attachmentViewer.open(data,fileLabel(file),stream.mimeType.value??'',()=>{[...files.querySelectorAll<HTMLButtonElement>('button[data-view-file-id]')].find(b=>b.dataset.viewFileId===file.id&&b.dataset.viewStream===stream.locator.path)?.focus({preventScroll:true});});check(ticket);status.textContent=`${doc.name} · Page ${pageNumber} of ${doc.pdf.numPages}`;
        })
});
function showFiles(doc:BrowserDocument,message?:MailMessage):void {
  threadPreview.sync(doc,message);filesPanel.show(doc,message);
}
function singleMessage(doc:BrowserDocument):MailMessage|undefined {
  return !doc.inspection.collection.value&&doc.inspection.messages.length===1?doc.inspection.messages[0]:undefined;
}
async function showDocument(doc:BrowserDocument,ticket:number,message?:MailMessage,node?:HTMLElement,edge?:-1|1,fromTop=false):Promise<void>{
  checkCollectionNames(doc.inspection);
  let opening:OpeningDestination|null=null,openingWarning='';
  message??=singleMessage(doc);
  if(!filesPanel.hasDescriptions(doc)){
    await session!.ensure(doc);const assets=await doc.run(doc.pdf.getDocumentAssets());check(ticket);
    filesPanel.setDescriptions(doc,new Map(assets.files.filter(f=>typeof f.description.value==='string').map(f=>[f.locator.path,String(f.description.value)])));
  }
  if(!initializedViews.has(doc)){
    try{const style=getComputedStyle(viewport);opening=await openingDestination(doc,viewport.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight),viewport.clientHeight-parseFloat(style.paddingTop)-parseFloat(style.paddingBottom),()=>check(ticket));}
    catch(error){check(ticket);openingWarning=error instanceof Error?error.message:String(error);}
    const mode=await doc.run(doc.pdf.getPageMode());check(ticket);initializedViews.add(doc);
    if(mode==='UseAttachments')setPaneHidden('details',false,false);
    else if(mode==='UseOutlines')setPaneHidden('navigation',false,false);
  }
  if(doc.parent?.inspection.collection.state==='present'&&!portfolioModels.has(doc.parent)){
    const data=await session!.portfolio(doc.parent);check(ticket);portfolioModels.set(doc.parent,new PortfolioModel(data,doc.parent.inspection));
  }
  await session!.ensure(doc);if(!doc.inspection.collection.value)await session!.associateFiles(doc);check(ticket);message=message?doc.inspection.messages.find(m=>m.id===message!.id):undefined;if(current!==doc)searchController.documentChanged();if(current)savedPages.set(current,pageNumber);current=doc;editingPage=false;savedMessages.set(doc,message?.id);
  const destinations=message?messagePages(doc.inspection,message):[];pageNumber=fromTop||edge===1?1:edge===-1?doc.pdf.numPages:destinations[0]??savedPages.get(doc)??1;
  markSelection(node??messageTree.node(doc)?.querySelector<HTMLButtonElement>(':scope > button')??undefined);showInformation(doc);showFiles(doc,singleMessage(doc)?undefined:message);searchStatus.textContent='';
  portfolioView?.syncDocument(doc.parent===portfolioDocument?doc.parentFileId:undefined);
  if(opening){openingPlans.set(doc,opening);savedMessages.delete(doc);pageNumber=opening.page;if(![...zoom.options].some(o=>o.value===opening!.zoom)){const option=document.createElement('option');option.value=opening.zoom;option.textContent=`${Math.round(Number(opening.zoom)*100)}%`;zoom.append(option);}zoom.value=opening.zoom;}
  await render(ticket,false);
  if(opening&&(opening.left!==null||opening.top!==null)){
    const page=await doc.run(doc.pdf.getPage(pageNumber));check(ticket);try{
      const view=page.getViewport({scale:await pageScale(doc,pageNumber)}),box=surface.querySelector<HTMLElement>(`[data-page="${pageNumber}"]`),crop=page.view;
      const point=view.convertToViewportPoint(opening.left??crop[0]!,opening.top??crop[3]!);
      if(box){const rect=box.getBoundingClientRect(),area=viewport.getBoundingClientRect();if(opening.top!==null)viewport.scrollTop+=rect.top-area.top+point[1];if(opening.left!==null)viewport.scrollLeft+=rect.left-area.left+point[0];}
    }finally{page.cleanup();}
  }
  if(openingWarning){openingWarnings.set(doc,openingWarning);element('document-notice').textContent=openingWarning;element('document-notice').hidden=false;}

  if(fromTop){viewport.scrollTop=0;viewport.scrollLeft=0;}
  await showOutline(doc,ticket);
  if(edge===-1)viewport.scrollTop=viewport.scrollHeight-viewport.clientHeight;
  if(edge){messageTree.node(doc)?.querySelector('button')?.scrollIntoView({block:'nearest'});status.textContent=`${doc.name} · Page ${pageNumber} of ${doc.pdf.numPages} · ${edge===1?'Next':'Previous'} file in this folder`;}
  if(message&&!destinations.length)status.textContent+=' — Message page mapping unavailable; showing document page.';
  if(doc.inspection.coverage.metadata==='unavailable'){
    status.textContent+=' — Email metadata unavailable.';
  }
}
async function openChild(parent:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,ticket:number,edge?:-1|1,placement?:PdfPlacement):Promise<void>{
  checkCollectionNames(parent.inspection);
  status.textContent=`Opening ${fileLabel(file)}…`;const child=await session!.child(parent,file,stream,false);check(ticket);
  const row=messageTree.attachChild(parent,file,stream,child,placement);
  const single=!child.inspection.collection.value&&child.inspection.messages.length===1?child.inspection.messages[0]:undefined;
  await showDocument(child,ticket,single,row.querySelector('button')??undefined,edge);
  if(portfolioView&&detectDocument(child.inspection).kind==='portfolio')await activatePortfolio(child,ticket,true);
  else if(!portfolioView)prepareBrowseSubjects(child,ticket);
}
// An edge gesture changes files only inside the immediate containing folder.
function scrollFile(direction:-1|1):boolean {
  const doc=current,parent=doc?.parent;
  const atEdge=direction===1?viewport.scrollTop+viewport.clientHeight>=viewport.scrollHeight-2:viewport.scrollTop<=2;
  if(!busy&&doc&&atEdge&&threadPreview.navigate(direction,true))return true;
  const filteredScroll=searchController.scrollFiltered(direction);if(filteredScroll!==undefined)return filteredScroll;
  if(busy||!doc||!parent||!doc.inspection.messages.length||doc.inspection.collection.value)return false;
  if(direction===1?viewport.scrollTop+viewport.clientHeight<viewport.scrollHeight-2:viewport.scrollTop>2)return false;
  const siblings=folderFiles(parent.inspection,doc.parentFileId??''),index=siblings.findIndex(f=>f.id===doc.parentFileId);
  if(index<0||siblings[index]!.streams.filter(s=>isPdf(siblings[index]!,s)).length!==1)return false;
  void operation(async ticket=>{
    try{
      for(let i=index+direction;i>=0&&i<siblings.length;i+=direction){
        const file=siblings[i]!,streams=file.streams.filter(s=>isPdf(file,s));
        if(!streams.length){if(/\.pdf$/i.test(fileLabel(file)))throw new Error(`Missing embedded PDF: ${fileLabel(file)}`);continue;}
        if(streams.length!==1)throw new Error(`Ambiguous PDF streams: ${fileLabel(file)}`);
        status.textContent=`Reading ${direction===1?'next':'previous'} message in this folder: ${fileLabel(file)}…`;
        const child=await session!.child(parent,file,streams[0]!,false);check(ticket);
        // Nested collections are folder boundaries; ordinary PDF attachments have no email records.
        if(child.inspection.collection.value){status.textContent='Stopped at a nested collection. Select it to continue.';return;}
        if(child.inspection.coverage.metadata!=='present'&&child.inspection.coverage.metadata!=='missing')throw new Error(`Unreadable message metadata: ${child.name}`);
        if(!child.inspection.messages.length)continue;
        await openChild(parent,file,streams[0]!,ticket,direction);return;
      }
      status.textContent=direction===1?'End of messages in this folder.':'Beginning of messages in this folder.';
    }finally{
      // Inspecting skipped children can evict the current PDF worker; restore it.
      if(ticket===generation&&current===doc&&!doc.active){
        const message=status.textContent;await render(ticket,false);
        viewport.scrollTop=direction===1?viewport.scrollHeight-viewport.clientHeight:0;status.textContent=message;
      }
    }
  });
  return true;
}
let lastWheel=0,wheelConsumed=false;
viewport.addEventListener('wheel',event=>{
  if(event.ctrlKey||event.metaKey||Math.abs(event.deltaY)<=Math.abs(event.deltaX))return;
  const now=performance.now();if(now-lastWheel>200)wheelConsumed=false;lastWheel=now;
  if(wheelConsumed)return;
  if(scrollFile(event.deltaY>0?1:-1)){wheelConsumed=true;event.preventDefault();}
},{passive:false});
let touchStart:{y:number;top:boolean;bottom:boolean}|undefined;
viewport.addEventListener('touchstart',event=>{
  const touch=event.touches.length===1?event.touches[0]:undefined;
  touchStart=touch?{y:touch.clientY,top:viewport.scrollTop<=2,bottom:viewport.scrollTop+viewport.clientHeight>=viewport.scrollHeight-2}:undefined;
},{passive:true});
viewport.addEventListener('touchend',event=>{
  const start=touchStart,touch=event.changedTouches[0];touchStart=undefined;if(!start||!touch)return;
  const delta=start.y-touch.clientY;if(delta>40&&start.bottom)scrollFile(1);else if(delta< -40&&start.top)scrollFile(-1);
},{passive:true});
function stopBrowseIndex():void {
  browseEpoch++;browseSession?.close();browseSession=undefined;
  searchController.metadataWork=undefined;
  element('subject-index-status').textContent='Subject indexing stopped. Unread subjects still show filenames.';
  element('subject-index-cancel').hidden=true;
}
element('subject-index-cancel').addEventListener('click',stopBrowseIndex);
function prepareBrowseSubjects(doc:BrowserDocument,ticket:number):void {
  if(detectDocument(doc.inspection).kind!=='eapdf'||!doc.inspection.collection.value||browseCompleted.has(doc)||browseSession)return;
  const main=session!,epoch=++browseEpoch,isolated=new BrowserSession({},{reuseWorker:true});browseSession=isolated;
  const checkIndex=()=>{check(ticket);if(epoch!==browseEpoch)throw new Error('Subject indexing cancelled.');};
  const updateSubjects=messageTree.subjectUpdater(doc);
  const update=(messages:readonly IndexedMessage[])=>{checkIndex();updateSubjects(messages);};
  element('subject-index-progress').hidden=false;element('subject-index-progress').dataset.complete='false';element('subject-index-cancel').hidden=false;
  element('subject-index-status').textContent='Reading subjects in the background… You can browse now.';
  const work=(async()=>{
    try{
      // Yield so the loaded document and enabled controls paint before indexing starts.
      await new Promise(resolve=>setTimeout(resolve,0));checkIndex();
      let indexed=main.cachedMessageIndex(doc);
      if(!indexed){const fast=await readEmbeddedIndex(doc,checkIndex);if(fast.messages){indexed={messages:fast.messages,incomplete:[],source:'embedded'};main.rememberMessageIndex(doc,indexed);}}
      if(indexed)update(indexed.messages);
      else{
        const blob=await main.sourceBlob(doc);checkIndex();const copy=await isolated.open(blob,doc.name);checkIndex();
        indexed=await indexMessages(isolated,copy,checkIndex,count=>{
          checkIndex();element('subject-index-status').textContent=`Reading subjects · ${count} emails. You can browse now.`;
        },update);
        checkIndex();element('subject-index-status').textContent=`Subjects read · ${indexed.messages.length} emails. Linking supporting files…`;
        if(indexed.source!=='embedded')await isolated.associateFiles(copy);checkIndex();
        await main.adoptAssociations(doc,isolated,copy);if(current===doc)refreshInspection(doc);
      }
      checkIndex();main.rememberMessageIndex(doc,indexed);browseCompleted.add(doc);browseMessageCounts.set(doc,indexed.messages.length);
      if(current===doc)showFiles(doc,singleMessage(doc)?undefined:selectedMessage(doc));
      messageTree.updateFolderCounts(doc,indexed.messages);
      element('subject-index-status').textContent=indexed.incomplete.length?`Subjects partially indexed: ${indexed.incomplete.join(' ')}`:`Subjects ready · ${indexed.messages.length} emails. ${(indexed.notices??[]).join(' ')}`;
      element('subject-index-cancel').hidden=true;element('subject-index-progress').dataset.complete=String(!indexed.incomplete.length);element('message-count').textContent=String(indexed.messages.length);return indexed;
    }finally{isolated.close();if(browseSession===isolated)browseSession=undefined;}
  })();
  if(!doc.parent)searchController.metadataWork=work;
  void work.catch(error=>{
    if(ticket!==generation||epoch!==browseEpoch)return;
    if(!doc.parent&&searchController.metadataWork===work)searchController.metadataWork=undefined;
    element('subject-index-status').textContent=`Subject indexing could not finish: ${error instanceof Error?error.message:String(error)}`;
    element('subject-index-cancel').hidden=true;
  });
}

function evictDocument(doc:BrowserDocument):void {
  messageTree.evict(doc);savedMessages.delete(doc);savedPages.delete(doc);
  pageOwners.delete(doc);browseCompleted.delete(doc);browseMessageCounts.delete(doc);
}
function documentTree(doc:BrowserDocument):HTMLLIElement {return messageTree.build(doc);}
async function openFile(file:Blob,name:string):Promise<void>{
  // A file picker may finish while a layout/zoom render is in flight. Keep the
  // latest explicit selection and open it when that operation releases the UI.
  if(busy){pendingOpen={file,name};return;}close();zoom.value=matchMedia('(max-width:799px)').matches?'fit-width':'fit-page';element('linked-file').hidden=true;session=new BrowserSession({},{onEvict:evictDocument,keepDocuments:()=>current?[current]:[]});const active=session;
  await operation(async ticket=>{
    status.textContent=`Opening ${name}…`;
    tree.hidden=true;
    element('loading-message').textContent='Inspecting the email archive. Large files may take a while.';
    element('loading-progress').hidden=false;
    element('loading-cancel').textContent='Cancel';
    loadingTimer=setTimeout(()=>{if(ticket===generation)loadingDialog.showModal();},1000);
    try {
    const doc=await active.open(file,name,fraction=>{if(ticket===generation)status.textContent=`Inspecting ${name} · ${Math.round(fraction*100)}%`;},null,undefined,undefined,async preview=>{check(ticket);const meta=await preview.run(preview.pdf.getMetadata());check(ticket);if(['c','ci'].includes(String(meta.metadata?.get('pdfmailid:conformance')??'')))return;empty.hidden=true;await pages.show(preview,await pageScale(preview,1),1,'',false);check(ticket);status.textContent='First page ready. Email and file inspection continues…';},message=>{check(ticket);status.textContent=message;element('loading-message').textContent=message;});check(ticket);
    tree.append(documentTree(doc));await showDocument(doc,ticket);
    if(detectDocument(doc.inspection).kind==='portfolio')await activatePortfolio(doc,ticket,true);
    if(ticket===generation){finishLoading();if(!portfolioView)prepareBrowseSubjects(doc,ticket);}
    } catch(error) {
      if(ticket===generation){
        clearTimeout(loadingTimer);loadingTimer=undefined;
        element('loading-message').textContent=`Unable to open PDF: ${error instanceof Error?error.message:String(error)}`;
        element('loading-progress').hidden=true;
        element('loading-cancel').textContent='Close';
        if(!loadingDialog.open)loadingDialog.showModal();
      }
      throw error;
    }
  });
}
async function openPortfolioRow(row:PortfolioRow,ticket:number,reveal=true):Promise<void>{
  const parent=portfolioDocument,file=row.file;if(!parent||!file)throw new Error('This collection member is unavailable.');
  const stream=file.streams.find(s=>s.efKeys.includes('/UF'))??file.streams[0];if(!stream)throw new Error('This file has no readable embedded content.');
  portfolioView?.show(row,reveal&&isPdf(file,stream));
  if(isPdf(file,stream)){await openChild(parent,file,stream,ticket);if(reveal)portfolioReturn=undefined;return;}
  const data=await session!.bytes(parent,file,stream);check(ticket);
  await attachmentViewer.open(data,row.name,stream.mimeType.value??'',()=>element('portfolio-panel').querySelector<HTMLButtonElement>('button[aria-current]')?.focus({preventScroll:true}));check(ticket);
  status.textContent=`Viewing ${row.name}`;
}
async function activatePortfolio(doc:BrowserDocument,ticket:number,initial=false):Promise<void>{
  const data=await session!.portfolio(doc);check(ticket);
  stopBrowseIndex();portfolioView?.close();portfolioDocument=doc;
  const model=new PortfolioModel(data,doc.inspection);portfolioModels.set(doc,model);
  portfolioView=new PortfolioView(element('portfolio-panel'),model,{
    open:row=>void operation(active=>openPortfolioRow(row,active)),
    cover:()=>void operation(async active=>{portfolioView?.revealPreview();await showDocument(doc,active);portfolioReturn=undefined;}),
    thumbnail:async row=>{if(row.directory){await session!.ensure(doc);const result=await doc.run(doc.pdf.getDocumentAssets({folderThumbnail:row.directory.path}));check(ticket);return result.thumbnail;}const file=row.file,stream=file?.streams[0];if(!file||!stream)throw new Error('No preview available.');const bytes=await session!.bytes(doc,file,stream);check(ticket);return bytes;},
    download:row=>void operation(async active=>{const file=row.file,stream=file?.streams.find(s=>s.efKeys.includes('/UF'))??file?.streams[0];if(!file||!stream)throw new Error('This collection member is unavailable.');const bytes=await session!.bytes(doc,file,stream);check(active);await saveBlob(new Blob([new Uint8Array(bytes)],{type:'application/octet-stream'}),row.name,()=>check(active));check(active);}),
    parent:doc.parent?()=>void operation(active=>returnToParent(doc.parent!,active)):undefined,
  });
  portfolioView.syncDocument(current?.parent===doc?current.parentFileId:undefined);
  showInformation(current??doc);
  await showOutline(current??doc,ticket);
  if(initial){const target=model.initial();if(target==='invalid'){element('document-notice').textContent='The Portfolio initial document is missing or ambiguous. Showing the collection cover.';element('document-notice').hidden=false;}else if(target==='empty'){pages.clear();surface.hidden=true;empty.hidden=true;status.textContent='The portfolio has no files to preview.';}else if(target!=='container')await openPortfolioRow(target,ticket,false);else {const plan=openingPlans.get(doc);if(plan){pageNumber=plan.page;await render(ticket,false);}}}
}
element('portfolio-show').addEventListener('click',()=>portfolioView?.reveal());
element('portfolio-toggle').addEventListener('click',()=>void operation(async ticket=>{
  if(!current)return;
  if(portfolioView){const saved=portfolioReturn,doc=saved?.doc??current;portfolioView.close();portfolioView=undefined;portfolioDocument=undefined;portfolioReturn=undefined;await showDocument(doc,ticket,doc.inspection.messages.find(m=>m.id===saved?.message));if(saved){pageNumber=saved.page;await render(ticket,false);}showInformation(doc);prepareBrowseSubjects(doc,ticket);}
  else{portfolioReturn={doc:current,page:pageNumber,message:savedMessages.get(current)};let doc=current;while(doc.parent&&!doc.inspection.collection.value)doc=doc.parent;if(!doc.inspection.collection.value){while(doc.parent)doc=doc.parent;}await activatePortfolio(doc,ticket);}
}));
fileInput.addEventListener('change',()=>{const file=fileInput.files?.[0];fileInput.value='';if(file)void openFile(file,file.name);});
// Capture file drags before controls in the navigation pane handle them or the
// browser performs its default file navigation. Every viewer pane is a drop target.
for(const type of ['dragenter','dragover'] as const)window.addEventListener(type,event=>{
  const transfer=event.dataTransfer;
  if(transfer&&(Array.from(transfer.types).includes('Files')||Array.from(transfer.items).some(item=>item.kind==='file'))){
    event.preventDefault();event.stopPropagation();transfer.dropEffect='copy';document.body.classList.add('drop-target');
  }
},{capture:true});
const clearDropTarget=()=>document.body.classList.remove('drop-target');
window.addEventListener('dragleave',event=>{if(!event.relatedTarget)clearDropTarget();},{capture:true});
window.addEventListener('dragend',clearDropTarget,{capture:true});
window.addEventListener('blur',clearDropTarget);
window.addEventListener('drop',event=>{event.preventDefault();event.stopPropagation();clearDropTarget();const file=event.dataTransfer?.files[0];if(file)void openFile(file,file.name);},{capture:true});
element('export-csv').addEventListener('click',()=>{
  const doc=current,active=session;if(!doc||!active||busy)return;
  let root=doc;while(root.parent)root=root.parent;const ticket=generation;
  metadataDialog.open({doc,root,messageId:selectedMessage(doc)?.id,session:active,check:()=>check(ticket),save:saveBlob,
    csv:(source,options)=>void operation(activeTicket=>exportCsv(source,options,activeTicket))});
});
async function exportCsv(doc:BrowserDocument,options:CsvOptions,ticket:number){
  const active=session;if(!active)return;
  const isolated=new BrowserSession({},{reuseWorker:true});exportSession=isolated;
  let cancelled=false;
  const cancel=()=>{cancelled=true;isolated.close();exportDialog.close();};cancelExport=cancel;
  const verify=()=>{check(ticket);if(cancelled)throw new Error('Export cancelled.');};
  const progress=(count:number,name?:string)=>{
    verify();const message=`${count.toLocaleString()} emails prepared${name?' · Reading '+name:''}`;
    element('export-message').textContent=message;status.textContent=message;
  };
  const started=performance.now(),elapsed=()=>{element('export-elapsed').textContent=`Elapsed: ${Math.floor((performance.now()-started)/1000)} seconds`;};
  element('export-message').textContent='Preparing finding aid…';elapsed();exportDialog.showModal();
  const timer=setInterval(elapsed,1000);
  try{
    await new Promise(resolve=>setTimeout(resolve,0));verify();
    let source=doc;
    // Flat PDFs already have the metadata required for export.
    if(doc.inspection.collection.value){
      const blob=await active.sourceBlob(doc);verify();
      source=await isolated.open(blob,doc.name,fraction=>{verify();element('export-message').textContent=`Preparing archive · ${Math.round(fraction*100)}% read`;});verify();
    }
    const result=await metadataCsv(source,(parent,file,stream)=>isolated.child(parent,file,stream,false),verify,progress,child=>isolated.release(child),options);
    verify();element('export-message').textContent=`Saving finding aid for ${result.rows.toLocaleString()} emails…`;
    await saveBlob(result.blob,doc.name.replace(/\.pdf$/i,'')+'-finding-aid.csv',verify,exportDialog);verify();
    status.textContent=`Exported finding aid for ${result.rows} messages.`;
  }catch(error){if(ticket===generation)status.textContent=cancelled?'Finding aid export cancelled.':`Metadata export failed: ${error instanceof Error?error.message:String(error)}`;}
  finally{
    clearInterval(timer);isolated.close();if(exportSession===isolated)exportSession=undefined;
    if(cancelExport===cancel){cancelExport=undefined;exportDialog.close();}
    if(ticket===generation&&!doc.active){const message=status.textContent;await render(ticket,false);status.textContent=message;}
  }
}
element('close').addEventListener('click',close);window.addEventListener('pagehide',close);
async function returnToParent(parent:BrowserDocument,ticket:number):Promise<void>{
  await showDocument(parent,ticket,undefined,undefined,undefined,true);
  if(portfolioView){
    portfolioReturn=undefined;
    if(parent.inspection.collection.state==='present'&&parent!==portfolioDocument)await activatePortfolio(parent,ticket);
    portfolioView.resetToTop();
  }else{
    element('navigation-panel').scrollTop=0;
    element('navigation-panel').scrollLeft=0;
  }
}
element('back').addEventListener('click',()=>{const parent=current?.parent;if(parent)void operation(ticket=>returnToParent(parent,ticket));});
element('previous').addEventListener('click',()=>void operation(async ticket=>{pageNumber=Math.max(1,pageNumber-1);await render(ticket);}));
element('next').addEventListener('click',()=>void operation(async ticket=>{pageNumber=Math.min(current?.pdf.numPages??1,pageNumber+1);await render(ticket);}));
function jumpToEnteredPage():void {
  if(busy||!current)return;
  const entered=pageInput.value.trim(),value=/^[0-9]+$/.test(entered)?Number(entered):NaN;
  if(!Number.isSafeInteger(value)||value<1||value>current.pdf.numPages){
    editingPage=false;pageInput.value=String(pageNumber);
    pageInput.setAttribute('aria-invalid','true');status.textContent=`Enter a whole page number from 1 to ${current.pdf.numPages}.`;return;
  }
  editingPage=false;pageInput.removeAttribute('aria-invalid');
  void operation(async ticket=>{pageNumber=value;await render(ticket);});
}
pageInput.addEventListener('focus',()=>pageInput.select());
pageInput.addEventListener('input',()=>{editingPage=true;pageInput.removeAttribute('aria-invalid');});
pageInput.addEventListener('change',jumpToEnteredPage);
pageInput.addEventListener('keydown',event=>{
  if(event.key==='Enter'){event.preventDefault();jumpToEnteredPage();}
  else if(event.key==='Escape'){event.preventDefault();editingPage=false;pageInput.value=String(pageNumber);pageInput.removeAttribute('aria-invalid');pageInput.select();}
});
zoom.addEventListener('change',()=>void operation(ticket=>render(ticket,false)));
for(const [id,step]of [['zoom-out',-1],['zoom-in',1]] as const)element(id).addEventListener('click',()=>void operation(async ticket=>{
  const displayed=zoom.value.startsWith('fit-')?renderedZoom:Number(zoom.value);
  const levels=[...zoom.options].map(option=>Number(option.value)).filter(Number.isFinite);
  const next=step>0?levels.find(value=>value>displayed+.0001):levels.findLast(value=>value<displayed-.0001);
  if(next!==undefined)zoom.value=String(next);await render(ticket,false);
}));

search.addEventListener('input',()=>{if(!search.value.trim())searchController.clearSearch();});
search.addEventListener('keydown',event=>{if(event.key==='Escape'){if(!event.defaultPrevented){event.preventDefault();closeSearch();}}else if(event.key==='Enter'){event.preventDefault();void (element<HTMLSelectElement>('search-scope').value==='collection'?searchController.findInCollection(event.shiftKey?-1:1):searchController.findMatch(event.shiftKey?-1:1));}});
async function followEmbeddedLink(source:BrowserDocument,action:EmbeddedAction,ticket:number):Promise<void>{
  const target=await resolveEmbeddedLink(session!,source,action,()=>check(ticket));check(ticket);
  portfolioReturn=undefined;
  searchController.clearMessageFilterForSearch();
  const path:BrowserDocument[]=[];for(let d:BrowserDocument|null=target.doc;d;d=d.parent)path.unshift(d);
  for(const doc of path)if(!messageTree.node(doc)){const row=documentTree(doc);const parent=doc.parent&&messageTree.node(doc.parent);(parent?.querySelector(':scope > ul')??tree).append(row);}
  if(portfolioView){let container:BrowserDocument|null=target.doc.inspection.collection.state==='present'?target.doc:target.doc.parent;while(container&&container.inspection.collection.state!=='present')container=container.parent;
    if(container&&container!==portfolioDocument)await activatePortfolio(container,ticket);
    const row=portfolioView?.model.rows.find(r=>r.file?.id===target.doc.parentFileId);if(row)portfolioView?.show(row);portfolioView?.revealPreview();
  }
  await showDocument(target.doc,ticket);pageNumber=target.page;await render(ticket,false);
}
const displayedOutlines=new WeakMap<HTMLElement,BrowserDocument>();
async function showOutline(doc:BrowserDocument,ticket:number):Promise<void>{
  const panel=element(portfolioView?'portfolio-outline-panel':'outline-panel');
  if(displayedOutlines.get(panel)===doc)return;
  const list=element(portfolioView?'portfolio-outline':'outline');list.replaceChildren();panel.hidden=true;if(panel instanceof HTMLDetailsElement)panel.open=false;
  try{const entries=await doc.run(doc.pdf.getOutline());check(ticket);if(current!==doc||!entries)return;let count=0;
    const pending=entries.slice().reverse().map(item=>({item,parent:list})),seen=new Set<typeof entries[number]>();
    while(pending.length){
      const {item,parent}=pending.pop()!;
      if(seen.has(item))throw new Error('Cyclic bookmark tree.');seen.add(item);
      const row=text('li','');
      const embedded=(item as typeof item & {embeddedAction?:EmbeddedAction}).embeddedAction;
      if(embedded)row.append(button(item.title,()=>void operation(active=>followEmbeddedLink(doc,embedded,active))));
      else if(item.dest)row.append(button(item.title,()=>void operation(async active=>{
        const dest=typeof item.dest==='string'?await doc.run(doc.pdf.getDestination(item.dest)):item.dest;check(active);if(!dest)throw new Error('Bookmark destination is unavailable.');
        const ref=dest[0];const index=typeof ref==='number'?ref:await doc.run(doc.pdf.getPageIndex(ref));check(active);if(index<0||index>=doc.pdf.numPages)throw new Error('Bookmark page is outside this PDF.');pageNumber=index+1;await render(active);
      })));else row.append(text('span',item.title+' (no internal page destination)'));
      parent.append(row);
      if(item.items.length){const nested=text('ul','');row.append(nested);for(const child of item.items.slice().reverse())pending.push({item:child,parent:nested});}
      if(++count%250===0){await new Promise(resolve=>setTimeout(resolve,0));check(ticket);}
    }
    panel.hidden=!list.children.length;
    displayedOutlines.set(panel,doc);
  }catch(error){if(ticket===generation&&current===doc){list.append(text('li','Bookmarks could not be read.'));panel.hidden=false;}}
}

element('search-form').addEventListener('submit',event=>{event.preventDefault();searchController.resetCursor();void (element<HTMLSelectElement>('search-scope').value==='collection'?searchController.findInCollection(1,true):searchController.findMatch(1));});
element('find').addEventListener('click',()=>void (element<HTMLSelectElement>('search-scope').value==='collection'?searchController.findInCollection():searchController.findMatch(1)));
element('find-previous').addEventListener('click',()=>void (element<HTMLSelectElement>('search-scope').value==='collection'?searchController.findInCollection(-1):searchController.findMatch(-1)));
element('cancel-search').addEventListener('click',()=>{searchController.cancelSearch();});
element('download-message').addEventListener('click',()=>void operation(async ticket=>{
  const doc=current,active=session;if(!doc||!active)return;
  const plan=displayedMessageDownload(doc);if(!plan)return;
  status.textContent=portfolioView?'Preparing active PDF…':'Preparing message PDF…';
  const blob=await messagePdf(active,doc,plan,()=>check(ticket));
  await saveBlob(blob,plan.name,()=>check(ticket));check(ticket);
  status.textContent=`Download requested: ${plan.name}${plan.pages?' · Message pages exported as PDF.':''}`;
}));
element('retry').addEventListener('click',()=>void operation(ticket=>render(ticket)));
element('restart').addEventListener('click',()=>{
  const doc=current;if(!doc||busy)return;let root=doc;while(root.parent)root=root.parent;
  void operation(async ticket=>{const blob=await session!.sourceBlob(root);check(ticket);busy=false;await openFile(blob,root.name);status.textContent='PDF reopened. Select a message to continue.';});
});

filter.addEventListener('input',()=>searchController.onFilterInput());
cancelLinked=setupLinkedOpening(openFile,message=>{status.textContent=message;});
status.textContent='Open or drop a PDF to begin.';controls();

function navigationTab(mode:'browse'|'results'):void {
  if(mode==='results'&&element<HTMLButtonElement>('results-tab').disabled)return;
  for(const name of ['browse','results'] as const){
    const tab=element<HTMLButtonElement>(name+'-tab');tab.setAttribute('aria-selected',String(name===mode));tab.tabIndex=name===mode?0:-1;
  }
  element('browse-panel').hidden=false;element('collection-results').hidden=mode!=='results';
  document.querySelector<HTMLElement>('.message-filter')!.hidden=false;if(mode==='results')openSearch(false);
}
for(const name of ['browse','results'] as const){
  const tab=element(name+'-tab');tab.addEventListener('click',()=>navigationTab(name));
  tab.addEventListener('keydown',event=>{if(event.key!=='ArrowLeft'&&event.key!=='ArrowRight')return;event.preventDefault();
    const other=name==='browse'?'results':'browse';if(element<HTMLButtonElement>(other+'-tab').disabled)return;
    navigationTab(other);element(other+'-tab').focus();
  });
}
type LayoutMode='wide'|'medium'|'compact';
type Pane='navigation'|'details';
const panePreferences:Record<LayoutMode,Record<Pane,boolean>>={wide:{navigation:false,details:false},medium:{navigation:false,details:true},compact:{navigation:true,details:true}};
const layoutForWindow=():LayoutMode=>innerWidth<800||(innerHeight<500&&innerWidth<1200)?'compact':innerWidth<1200?'medium':'wide';
let layoutMode=layoutForWindow();
let resizeTimer:ReturnType<typeof setTimeout>|undefined;
function scheduleFit():void {
  clearTimeout(resizeTimer);
  if(!current||!zoom.value.startsWith('fit-'))return;
  resizeTimer=setTimeout(()=>{
    if(busy){scheduleFit();return;}
    if(viewport.clientWidth===renderedViewportWidth&&(zoom.value!=='fit-page'||viewport.clientHeight===renderedViewportHeight))return;
    if(current&&zoom.value.startsWith('fit-'))void operation(async ticket=>{
      // A viewport refit does not change the selected message or its diagnostics.
      const message=status.textContent;await render(ticket,false);status.textContent=message;
    });
  },150);
}
function applyPaneLayout():void {
  const workspace=element('workspace');workspace.dataset.layout=layoutMode;
  document.querySelector<HTMLElement>('.reader-pane')!.inert=layoutMode==='compact'&&(!panePreferences.compact.navigation||!panePreferences.compact.details);
  for(const pane of ['navigation','details'] as const){
    const hidden=panePreferences[layoutMode][pane],panel=element(pane+'-panel'),show=element('show-'+pane),toggle=element('toggle-'+pane);
    const restoreFocus=hidden&&panel.contains(document.activeElement);
    panel.hidden=hidden;show.hidden=!hidden;toggle.setAttribute('aria-expanded',String(!hidden));show.setAttribute('aria-expanded',String(!hidden));
    workspace.classList.toggle('hide-'+pane,hidden);
    if(restoreFocus)show.focus();
  }
}
function setPaneHidden(pane:Pane,hidden:boolean,focus=true):void {
  const preferences=panePreferences[layoutMode];preferences[pane]=hidden;
  if(!hidden&&layoutMode!=='wide')preferences[pane==='navigation'?'details':'navigation']=true;
  applyPaneLayout();
  if(focus)element((hidden?'show-':'toggle-')+pane).focus();
  scheduleFit();
}
for(const pane of ['navigation','details'] as const){
  element('toggle-'+pane).addEventListener('click',()=>setPaneHidden(pane,true));
  element('show-'+pane).addEventListener('click',()=>setPaneHidden(pane,false));
}
applyPaneLayout();
setupPaneResizers(element('workspace'));
window.addEventListener('keydown',event=>{
  if(event.defaultPrevented||event.key!=='Escape'||layoutMode!=='compact'||document.querySelector('dialog[open]'))return;
  const pane=(['navigation','details'] as const).find(p=>!panePreferences.compact[p]);
  if(pane){event.preventDefault();setPaneHidden(pane,true);}
});
window.addEventListener('resize',()=>{
  const next=layoutForWindow();if(next!==layoutMode){layoutMode=next;applyPaneLayout();}
  scheduleFit();
});
new ResizeObserver(scheduleFit).observe(viewport);
element('navigation-panel').addEventListener('click',event=>{
  const target=event.target instanceof Element?event.target.closest<HTMLButtonElement>('#tree button,#filter-results button,.collection-hit,#outline button'):null;
  if(layoutMode==='compact'&&target&&!target.disabled){setPaneHidden('navigation',true,false);viewport.focus();}
},{capture:true});

element('search-scope').addEventListener('change',()=>{const collection=element<HTMLSelectElement>('search-scope').value==='collection';search.placeholder=collection?'Find across collection PDFs…':'Find in this PDF…';search.setAttribute('aria-label',collection?'Find across collection PDFs':'Find in this PDF');});

function openSearch(focus=true):void {element('search-panel').hidden=false;element('open-search').setAttribute('aria-expanded','true');if(focus)search.focus();}
function closeSearch(focus=true):void {element('search-panel').hidden=true;element('open-search').setAttribute('aria-expanded','false');if(focus)element('open-search').focus();}
element('open-search').addEventListener('click',()=>openSearch());element('close-search').addEventListener('click',()=>closeSearch());
window.addEventListener('keydown',event=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='f'&&!document.querySelector('dialog[open]')){event.preventDefault();openSearch();}else if(event.key==='Escape'&&!element('search-panel').hidden&&!document.querySelector('dialog[open]')){event.preventDefault();closeSearch();}},{capture:true});
for(const [id,kind]of [['close','close'],['close-search','close'],['previous','left'],['next','right'],['find-previous','left'],['find','right'],['toggle-navigation','left'],['toggle-details','right'],['back','up'],['zoom-out','minus'],['zoom-in','plus']] as const){const control=element(id);if(!control.hasAttribute('aria-label'))control.setAttribute('aria-label',control.textContent!.trim());if(!control.title)control.title=control.getAttribute('aria-label')!;control.replaceChildren(treeIcon(kind));control.classList.add('icon-button');}
for(const [id,kind]of [['open-search','search'],['export-csv','download'],['download-message','download']] as const)element(id).prepend(treeIcon(kind));
document.querySelector('.open-file')!.prepend(treeIcon('folder'));
// Up/Down select and open messages, including rows beyond the mounted window.
function activateMessage(next:HTMLElement|undefined):void {
  if(!next||busy)return;
  const selector=next.matches('.collection-hit')?`#collection-matches button[data-match-index="${next.dataset.matchIndex}"]`:next.closest('#filter-results')?`#filter-results button[data-message-index="${next.dataset.messageIndex}"]`:'#tree button[aria-current]';
  keyboardSelection=()=>document.querySelector<HTMLElement>(selector);
  next.focus();next.click();
}
function adjacentMessage(target:HTMLElement,direction:number):HTMLElement|undefined {return messageTree.adjacentMessage(target,direction);}
tree.addEventListener('keydown',event=>{
 const target=event.target;if(!(target instanceof HTMLElement)||event.altKey||event.ctrlKey||event.metaKey)return;
 if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();if(!busy)activateMessage(adjacentMessage(target,event.key==='ArrowDown'?1:-1));return;}
 const visible=visiblePaneControls(tree);let next:HTMLElement|undefined;
 if(event.key==='Home')next=visible[0];else if(event.key==='End')next=visible.at(-1);
 else if(event.key==='ArrowRight'&&target.tagName==='SUMMARY'){(target.parentElement as HTMLDetailsElement).open=true;event.preventDefault();}
 else if(event.key==='ArrowLeft'){const parent=target.closest('details');if(parent){parent.open=false;next=parent.querySelector('summary')!;}}
 if(next){event.preventDefault();next.focus();}
});
// Arrow keys move through pane controls; Enter/Space retain native activation.
document.addEventListener('keydown',event=>{
 if(event.defaultPrevented||event.altKey||event.ctrlKey||event.metaKey||!['ArrowUp','ArrowDown'].includes(event.key))return;
 const target=event.target;if(!(target instanceof HTMLElement)||target.closest('input,textarea,select,[contenteditable=true]'))return;
 const direction=event.key==='ArrowDown'?1:-1;
 if(viewport.contains(target)){event.preventDefault();const edge=direction===1?viewport.scrollTop+viewport.clientHeight>=viewport.scrollHeight-2:viewport.scrollTop<=2;if(!busy&&edge&&threadPreview.navigate(direction,true))return;viewport.scrollBy({top:direction*60,behavior:'instant'});return;}
 const pane=target.closest<HTMLElement>('#navigation-panel,#details-panel,.reader-pane,#search-panel');if(!pane)return;
 const neighbor=searchController.virtualNeighbor(target,direction);if(neighbor){if(neighbor.consume){event.preventDefault();activateMessage(neighbor.row);}return;}
 if(target.closest('#filter-results,#collection-matches')){event.preventDefault();const list=target.closest<HTMLElement>('#filter-results,#collection-matches')!,rows=visiblePaneControls(list),index=rows.indexOf(target);activateMessage(rows[index+direction]);return;}
 if(target===element('navigation-panel')||target===tree){event.preventDefault();activateMessage(adjacentMessage(tree.querySelector<HTMLElement>('button[aria-current]')??target,direction));return;}
 const controls=visiblePaneControls(pane),index=controls.indexOf(target),next=controls[index<0?(direction===1?0:controls.length-1):index+direction];
 if(next){event.preventDefault();next.focus();}
});
search.addEventListener('keydown',event=>{
 if(event.altKey||event.ctrlKey||event.metaKey||!['ArrowUp','ArrowDown'].includes(event.key))return;
 const list=element('collection-results').hidden?filterResults:element('collection-matches'),controls=visiblePaneControls(list);
 const next=event.key==='ArrowDown'?controls[0]:controls.at(-1);if(next){event.preventDefault();activateMessage(next);}
});

element('navigation-panel').tabIndex=0;
element('navigation-panel').addEventListener('pointerdown',event=>{if(event.target instanceof Element&&!event.target.closest('button,input,summary,a,select'))element('navigation-panel').focus({preventScroll:true});});
