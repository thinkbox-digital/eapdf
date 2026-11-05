import {saveBlob} from './downloads.js';
import {indexMessages,type MessageIndex,type IndexedMessage} from './message-index.js';
import {searchCollection} from './collection-search.js';
import {DocumentSearch,textMatches} from './document-search.js';
import {BrowserSession,type BrowserDocument} from './browser-session.js';
import {AttachmentViewer} from './attachment-viewer.js';
import {VirtualList} from './virtual-list.js';
import {ScrollingPages} from './scrolling-pages.js';
import type {AssociatedFile,EmbeddedStream,MailMessage} from './domain/inspection.js';
import {fileLabel,messagePages,folderFiles} from './navigation.js';
import {metadataCsv} from './metadata-csv.js';
import {setupLinkedOpening} from './linked-opening.js';

function element<T extends HTMLElement>(id:string):T {const node=document.getElementById(id);if(!node)throw new Error('Missing element: '+id);return node as T;}
function text<K extends keyof HTMLElementTagNameMap>(tag:K,value:string){const node=document.createElement(tag);node.textContent=value;return node;}
function button(label:string,action:()=>void){const node=text('button',label);node.type='button';node.addEventListener('click',action);return node;}
function treeIcon(kind:'folder'|'message'|'document'|'attachment'|'code'|'view'|'download'|'search'|'close'|'left'|'right'|'up'|'minus'|'plus'|'warning'):SVGSVGElement {
  const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');
  icon.setAttribute('viewBox','0 0 24 24');icon.setAttribute('aria-hidden','true');icon.setAttribute('focusable','false');icon.classList.add('tree-icon');
  const paths=kind==='search'?['M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14','m15 15 6 6']:kind==='close'?['m6 6 12 12M18 6 6 18']:kind==='left'?['m15 5-7 7 7 7']:kind==='right'?['m9 5 7 7-7 7']:kind==='up'?['m5 12 7-7 7 7M12 5v15']:kind==='minus'?['M5 12h14']:kind==='plus'?['M5 12h14M12 5v14']:kind==='warning'?['m12 3 10 18H2Z','M12 9v5M12 17v1']:kind==='attachment'?['M8 13l7-7a3 3 0 0 1 4 4L9 20a5 5 0 0 1-7-7L12 3','M5 15l9-9']:kind==='code'?['m8 6-6 6 6 6M16 6l6 6-6 6M14 3l-4 18']:kind==='view'?['M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z','M9 12a3 3 0 1 0 6 0 3 3 0 1 0-6 0']:kind==='download'?['M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5']:kind==='folder'?['M3 7V5h6l2 2h10v13H3Z','M3 10h18l-3 10H3Z']:kind==='message'?['M3 5h18v14H3Z','m3 6 9 7 9-7']:['M5 3h9l5 5v13H5Z','M14 3v6h5M8 13h8M8 17h8'];
  paths.forEach((d,index)=>{const path=document.createElementNS(icon.namespaceURI,'path');path.setAttribute('d',d);if(kind==='folder')path.setAttribute('class',index?'folder-open':'folder-closed');icon.append(path);});
  return icon;
}
const fileInput=element<HTMLInputElement>('file'),pageInput=element<HTMLInputElement>('page'),zoom=element<HTMLSelectElement>('zoom');
const status=element('status'),tree=element('tree'),files=element('files');
const surface=element('page-surface'),viewport=element('viewport'),empty=element('empty-state');
const search=element<HTMLInputElement>('search'),searchStatus=element('search-status');
const attachmentViewer=new AttachmentViewer();
const failedPages=new Set<number>();
const pages=new ScrollingPages(viewport,surface,number=>{
  pageNumber=number;syncMessageToPage();if(current){savedPages.set(current,number);status.textContent=`${current.name} · Page ${number} of ${current.pdf.numPages}`;}controls();
},message=>{status.textContent=`Unable to display PDF: ${message}`;},(number,failed)=>{
  if(failed)failedPages.add(number);else failedPages.delete(number);controls();
});
let cancelLinked=()=>{};
const finder=new DocumentSearch();let searchEpoch=0;
const selectedMessage=(doc:BrowserDocument)=>doc.inspection.messages.find(m=>m.id===savedMessages.get(doc));
let exportSession:BrowserSession|undefined;
let cancelExport:(()=>void)|undefined;
const exportDialog=element<HTMLDialogElement>('export-dialog');
element('export-cancel').addEventListener('click',()=>cancelExport?.());
exportDialog.addEventListener('cancel',event=>{event.preventDefault();cancelExport?.();});
let searchSession:BrowserSession|undefined;
let browseSession:BrowserSession|undefined;
let browseEpoch=0;
const browseCompleted=new WeakSet<BrowserDocument>();
const browseMessageCounts=new WeakMap<BrowserDocument,number>();
const browseDisplay=new WeakMap<BrowserDocument,Map<string,Record<string,string>>>();
const browseSubjects=new WeakMap<BrowserDocument,Map<string,string>>();
type CollectionResults={root:BrowserDocument;query:string;result:Awaited<ReturnType<typeof searchCollection>>;index:number;occurrence:number;total:number};
let collectionResults:CollectionResults|undefined;
let session:BrowserSession|undefined,current:BrowserDocument|undefined,busy=false,generation=0,pageNumber=1,editingPage=false,renderedZoom=1;
let renderedViewportWidth=0,renderedViewportHeight=0;
const nodes=new Map<BrowserDocument,HTMLLIElement>(),savedMessages=new Map<BrowserDocument,string|undefined>(),savedPages=new Map<BrowserDocument,number>();
type PdfLink={file:AssociatedFile;stream:EmbeddedStream};
const pdfKey=(file:AssociatedFile,stream:EmbeddedStream)=>JSON.stringify([file.id,stream.locator.path]);
const lazyPdfs=new WeakMap<BrowserDocument,Map<string,{list:VirtualList<PdfLink>;index:number}>>();
const lazyMessages=new WeakMap<BrowserDocument,{list:VirtualList<MailMessage>;indices:Map<string,number>}>();
const virtualLists=new Set<{close:()=>void}>();
let fileList:VirtualList<AssociatedFile>|undefined;
const pageOwners=new WeakMap<BrowserDocument,Map<number,MailMessage|null>>();
// PDF points use 72 units/inch; browser CSS uses 96.
const PDF_TO_CSS=96/72;
const isPdf=(file:AssociatedFile,stream:EmbeddedStream)=>stream.mimeType.value?.split(';')[0]?.toLowerCase()==='application/pdf'||/\.pdf$/i.test(fileLabel(file));
const canView=(file:AssociatedFile,stream:EmbeddedStream)=>isPdf(file,stream)||/^(text\/|message\/(rfc822|global)|image\/(png|jpeg|gif|svg\+xml)|application\/(json|xml))/.test(stream.mimeType.value??'')||/\.(png|jpe?g|gif|txt|csv|tsv|log|json|xml|html?|eml|svg|md)$/i.test(fileLabel(file));
function sizeLabel(size:number|null):string {if(size===null)return 'Size not recorded';if(size<1024)return `${size} B`;if(size<1024**2)return `${(size/1024).toFixed(1)} KiB`;return `${(size/1024**2).toFixed(1)} MiB`;}
function controls():void {
  fileInput.disabled=busy;const available=!!current;
  element<HTMLSelectElement>('search-scope').disabled=busy||!available;
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
  for(const b of document.querySelectorAll<HTMLButtonElement>('#tree button,#files button,#breadcrumbs button'))b.disabled=busy||b.dataset.unavailable==='true';
  if(!editingPage||document.activeElement!==pageInput){pageInput.value=String(pageNumber);pageInput.removeAttribute('aria-invalid');editingPage=false;}
  for(const id of ['previous','next','back','zoom-in','zoom-out']){const control=element<HTMLButtonElement>(id);control.setAttribute('aria-disabled',String(control.disabled));}
  element<HTMLButtonElement>('open-search').disabled=!available;
  pageInput.style.width=`calc(${Math.max(4,String(current?.pdf.numPages??0).length)}ch + 1.5rem)`;
  element('pages').textContent=`of ${current?.pdf.numPages??0}`;
  viewport.setAttribute('aria-busy',String(busy));
}
function check(ticket:number):void {if(ticket!==generation)throw new Error('Cancelled.');}
async function operation(action:(ticket:number)=>Promise<void>):Promise<void>{
  if(busy)return;const ticket=generation;busy=true;controls();
  try{await action(ticket);}catch(error){if(ticket===generation){element('restart').hidden=!current;status.textContent=`Unable to display PDF: ${error instanceof Error?error.message:String(error)}`;}}
  finally{if(ticket===generation){busy=false;controls();}}
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
  finishLoading();cancelExport?.();
  metadataEpoch++;metadataSession?.close();metadataSession=undefined;metadataIndex=undefined;clearTimeout(filterTimer);
  filterList?.close();filterList=undefined;filterResults.replaceChildren();filterResults.hidden=true;filter.value='';tree.hidden=false;
  for(const list of virtualLists)list.close();virtualLists.clear();fileList?.close();fileList=undefined;
  cancelLinked();stopBrowseIndex();element('subject-index-progress').hidden=true;searchSession?.close();searchSession=undefined;exportSession?.close();exportSession=undefined;
  generation++;searchEpoch++;collectionResults=undefined;failedPages.clear();finder.clear();pages.clear();attachmentViewer.close();session?.close();session=undefined;current=undefined;
  nodes.clear();savedPages.clear();savedMessages.clear();busy=false;pageNumber=1;editingPage=false;
  element('restart').hidden=true;empty.hidden=false;element('collection-results').hidden=true;element('collection-matches').replaceChildren();element('outline').replaceChildren();element('outline-panel').hidden=true;
  for(const panel of [tree,files,element('breadcrumbs')])panel.replaceChildren();
  element('declared-profile').textContent='';element('declared-profile').hidden=true;
  element('header-filename').textContent='';element('header-filename').title='';element('message-count').textContent='0';element('selection-details').replaceChildren();element('files-title').textContent='Files in this PDF';closeSearch(false);
  element('file-count').textContent='0';search.value='';searchStatus.textContent='';
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
    const scale=await pageScale(doc,pageNumber);check(ticket);
    renderedViewportWidth=viewport.clientWidth;renderedViewportHeight=viewport.clientHeight;
    empty.hidden=true;await pages.show(doc,scale,pageNumber,search.value);check(ticket);renderedZoom=scale/PDF_TO_CSS;
    empty.hidden=true;status.textContent=`${doc.name} · Page ${pageNumber} of ${doc.pdf.numPages}`;savedPages.set(doc,pageNumber);if(followMessage)syncMessageToPage();
  }catch(error){if(ticket===generation&&current===doc)failedPages.add(pageNumber);throw error;}
}

function markSelection(node:HTMLElement|undefined):void {for(const n of tree.querySelectorAll('[aria-current]'))n.removeAttribute('aria-current');node?.setAttribute('aria-current','true');for(let parent=node?.parentElement;parent&&parent!==tree;parent=parent.parentElement)if(parent instanceof HTMLDetailsElement)parent.open=true;else if(parent.tagName==='UL'&&parent.hidden){parent.hidden=false;parent.parentElement?.querySelector(':scope > .document-disclosure')?.setAttribute('aria-expanded','true');}}
// Update selection only: following the page must never navigate back to a
// message's first page. Cache ownership instead of rescanning DParts on scroll.
function syncMessageToPage():void {
  const doc=current,root=doc?nodes.get(doc):undefined;
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
  const lazy=lazyMessages.get(doc),index=message?lazy?.indices.get(message.id):undefined;
  const ensured=index===undefined?undefined:lazy?.list.rowAt(index)?.querySelector<HTMLButtonElement>('button');
  const row=ensured??(message?root?.querySelector<HTMLButtonElement>(`:scope > ul > li > button[data-message-id="${CSS.escape(message.id)}"]`):root?.querySelector<HTMLButtonElement>(':scope > button[data-document-id]'));
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
  path.forEach((d,i)=>{if(i)bar.append(text('span','›'));const b=button(d.name,()=>void operation(ticket=>showDocument(d,ticket)));b.title=d.name;bar.append(b);});
}
function showInformation(doc:BrowserDocument):void {
  const id=doc.inspection.identification,profile=element('declared-profile');
  profile.textContent=id.declaredProfile.value?`EA-PDF ${id.eapdfVersion.value??'?'}${id.declaredProfile.value}`:'No EA-PDF profile declared';profile.hidden=false;
  for(const diagnostic of doc.inspection.diagnostics)console.debug('[EA-PDF inspection]',doc.name,diagnostic);
  breadcrumbs(doc);element('header-filename').textContent=doc.name;element('header-filename').title=doc.name;
  let root=doc;while(root.parent)root=root.parent;element('message-count').textContent=String(browseMessageCounts.get(root)??root.inspection.messages.length);
}
function refreshInspection(doc:BrowserDocument):void {
  if(current!==doc)return;showInformation(doc);showFiles(doc,singleMessage(doc)?undefined:selectedMessage(doc));
  const mismatches=doc.inspection.diagnostics.filter(d=>d.code==='CHECKSUM_MISMATCH');
  if(mismatches.length){status.textContent='Warning: an embedded file differs from its recorded checksum.';}
}
function fileCategory(file:AssociatedFile):string {
  const premis=/(?:^|[_. -])premis(?:[_. -]|$)/i.test(fileLabel(file))&&/\.xml$/i.test(fileLabel(file))
    ||file.streams.some(stream=>/premis/i.test(stream.mimeType.value??''));
  if(premis||file.afRelationship.value==='Supplement')return 'Technical and supporting files';
  if(file.afRelationship.value==='Source')return 'Email sources';
  return file.owners.value?.length?'Email attachments':'Archive files';
}
const fileCategoryOrder=['Email sources','Email attachments','Archive files','Technical and supporting files'];
function showFiles(doc:BrowserDocument,message?:MailMessage):void {
  fileList?.close();fileList=undefined;files.replaceChildren();const selected=doc.inspection.files.filter(f=>!message||f.owners.value?.includes(message.id)).sort((a,b)=>fileCategoryOrder.indexOf(fileCategory(a))-fileCategoryOrder.indexOf(fileCategory(b)));element('file-count').textContent=String(selected.length);
  element('files-title').textContent=message||singleMessage(doc)?'Files in this email':'Files in this PDF';
  const detailList=element('selection-details');detailList.replaceChildren();
  const selectedMessage=message??singleMessage(doc),metadata=selectedMessage?doc.details.get(selectedMessage.metadataRecordId):undefined;
  const source=selectedMessage?selected.find(f=>f.afRelationship.value==='Source'):undefined;
  const collection=doc.parent?.inspection.collection.value;const folderNames=new Set<string>();
  if(collection)for(const entry of collection.entries.filter(e=>e.fileId===doc.parentFileId))for(let id of entry.folderIds.value??[]){const names:string[]=[],seen=new Set<string>();while(id&&!seen.has(id)){seen.add(id);const folder=collection.folders.find(f=>f.id===id);if(!folder)break;if(folder.name.value)names.unshift(folder.name.value);id=folder.parentId??'';}if(names.length)folderNames.add(names.join(' / '));}
  for(const [key,value]of [['Source file',source?fileLabel(source):doc.name],['Message-ID',selectedMessage?.internetMessageId.value],['Folder',[...folderNames].join('; ')||metadata?.Folder||metadata?.['Folder paths']]])if(value){detailList.append(text('dt',key!),text('dd',value));}
  if(doc.inspection.coverage.associatedFiles!=='present'){const note=text('p','Some embedded files could not be listed.');note.className='file-note';files.append(note);}
  if(message&&doc.inspection.diagnostics.some(d=>d.code==='FILE_EMAIL_ASSOCIATION_MISSING')){const note=text('p','Some files could not be linked to an email.');note.className='file-note';files.append(note);}
  if(!selected.length){if(doc.inspection.coverage.associatedFiles==='present'){const note=text('p',message&&doc.inspection.files.length?'No files are associated with this email in the PDF.':'No embedded files in this selection.');note.className='muted';files.append(note);}}
  const list=text('ul','');files.append(list);
  const createFile=(file:AssociatedFile)=>{
    const row=text('li','');row.className='file-card';const label=text('strong',fileLabel(file));label.className='file-name';label.title=fileLabel(file);const icon=treeIcon(/\.eml$/i.test(fileLabel(file))?'message':/\.(xml|json|html?)$/i.test(fileLabel(file))?'code':file.afRelationship.value!=='Supplement'&&file.afRelationship.value!=='Source'&&file.owners.value?.length?'attachment':'document');icon.classList.add('file-icon');const heading=text('div','');heading.className='file-heading';heading.append(label);row.append(icon,heading);
    const category=fileCategory(file);
    if(selected.length>256){const role=text('p',category);role.className='file-role';row.append(role);}
    const owner=doc.inspection.messages.find(m=>file.owners.value?.includes(m.id));
    const description=(owner?doc.details.get(owner.metadataRecordId)?.Subject:undefined);
    if(description&&description!==fileLabel(file)){const detail=text('p',description);detail.className='file-description';detail.title=description;row.append(detail);}
    for(const stream of file.streams){
      const size=stream.decodedSize.value??stream.declaredSize.value;
      const facts=text('span',sizeLabel(size)+(file.streams.length>1?' · '+stream.efKeys.join('/'):'') );facts.className='file-size';heading.append(facts);
      const actions=text('div','');actions.className='file-actions';if(file.streams.length>1){row.classList.add('multiple-streams');actions.setAttribute('role','group');actions.setAttribute('aria-label',stream.efKeys.join('/'));const streamLabel=text('span',stream.efKeys.join('/'));streamLabel.className='stream-label';actions.append(streamLabel);}row.append(actions);
      const save=button(file.afRelationship.value==='Source'&&/\.eml$/i.test(fileLabel(file))?'Download email (.eml)':'Download',()=>void operation(async ticket=>{status.textContent=`Reading ${fileLabel(file)}…`;const data=await session!.bytes(doc,file,stream);check(ticket);await saveBlob(new Blob([new Uint8Array(data)],{type:'application/octet-stream'}),fileLabel(file),()=>check(ticket));check(ticket);refreshInspection(doc);status.textContent=`Download requested: ${fileLabel(file)} (${sizeLabel(data.length)}).${doc.inspection.diagnostics.some(d=>d.code==='CHECKSUM_MISMATCH')?' Warning: recorded checksum mismatch.':''}`;}));save.replaceChildren(treeIcon('download'));save.classList.add('icon-button');save.setAttribute('aria-label',`Download ${fileLabel(file)}`);save.title=`Download ${fileLabel(file)}`;save.dataset.fileId=file.id;save.dataset.stream=stream.locator.path;actions.append(save);
      if(size===0){save.dataset.unavailable='true';save.disabled=true;save.setAttribute('aria-disabled','true');const note=text('p','This file is empty. The original may have been empty, or this is a placeholder.');note.className='file-note empty-file-notice';const warning=treeIcon('warning');warning.removeAttribute('aria-hidden');warning.setAttribute('role','img');warning.setAttribute('aria-label','Warning');note.prepend(warning);row.append(note);}
      else if(canView(file,stream)){
        const view=button(isPdf(file,stream)?'Open PDF':'View',()=>void operation(async ticket=>{
          if(isPdf(file,stream)){await openChild(doc,file,stream,ticket);return;}
          status.textContent=`Reading ${fileLabel(file)}…`;const data=await session!.bytes(doc,file,stream);check(ticket);refreshInspection(doc);await attachmentViewer.open(data,fileLabel(file),stream.mimeType.value??'');check(ticket);status.textContent=`${doc.name} · Page ${pageNumber} of ${doc.pdf.numPages}`;
        }));view.className='view-action';view.replaceChildren(treeIcon('view'));view.classList.add('icon-button');view.setAttribute('aria-label',`View ${fileLabel(file)}`);view.title=`${isPdf(file,stream)?'Open':'View'} ${fileLabel(file)}`;view.dataset.viewFileId=file.id;view.dataset.viewStream=stream.locator.path;actions.prepend(view);
      }
    }
    if(!file.streams.length){const note=text('p','Embedded content is missing or cannot be read.');note.className='file-note';row.append(note);}
    for(const b of row.querySelectorAll<HTMLButtonElement>('button'))b.disabled=busy||b.dataset.unavailable==='true';
    return row;
  };
  if(selected.length>256)fileList=new VirtualList(list,files.closest('aside')!,selected,createFile,220);
  else {
    const groups=new Map<string,AssociatedFile[]>();
    for(const file of selected){const category=fileCategory(file),name=category==='Archive files'?'Other files':category;const group=groups.get(name)??[];group.push(file);groups.set(name,group);}
    for(const [name,group]of groups){const section=text('li',''),disclosure=text('details',''),heading=text('summary',''),title=text('span',name),count=text('span',String(group.length)),items=text('ul','');section.className='file-category';heading.className='file-category-heading';count.className='badge';const chevron=treeIcon('right');chevron.classList.add('disclosure-icon');heading.append(chevron,title,count);disclosure.open=name==='Email sources'||name==='Email attachments';disclosure.append(heading,items);section.append(disclosure);list.append(section);for(const file of group)items.append(createFile(file));}
  }
}
function singleMessage(doc:BrowserDocument):MailMessage|undefined {
  return !doc.inspection.collection.value&&doc.inspection.messages.length===1?doc.inspection.messages[0]:undefined;
}
async function showDocument(doc:BrowserDocument,ticket:number,message?:MailMessage,node?:HTMLElement,edge?:-1|1):Promise<void>{
  message??=singleMessage(doc);
  await session!.ensure(doc);if(!doc.inspection.collection.value)await session!.associateFiles(doc);check(ticket);message=message?doc.inspection.messages.find(m=>m.id===message!.id):undefined;if(current!==doc)finder.clear();if(current)savedPages.set(current,pageNumber);current=doc;editingPage=false;savedMessages.set(doc,message?.id);
  const destinations=message?messagePages(doc.inspection,message):[];pageNumber=edge===1?1:edge===-1?doc.pdf.numPages:destinations[0]??savedPages.get(doc)??1;
  markSelection(node??nodes.get(doc)?.querySelector<HTMLButtonElement>(':scope > button')??undefined);showInformation(doc);showFiles(doc,singleMessage(doc)?undefined:message);searchStatus.textContent='';
  await render(ticket,false);
  await showOutline(doc,ticket);
  if(edge===-1)viewport.scrollTop=viewport.scrollHeight-viewport.clientHeight;
  if(edge){nodes.get(doc)?.querySelector('button')?.scrollIntoView({block:'nearest'});status.textContent=`${doc.name} · Page ${pageNumber} of ${doc.pdf.numPages} · ${edge===1?'Next':'Previous'} file in this folder`;}
  if(message&&!destinations.length)status.textContent+=' — Message page mapping unavailable; showing document page.';
  if(doc.inspection.coverage.metadata==='unavailable'){
    status.textContent+=' — Email metadata unavailable.';
  }
}
async function openChild(parent:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,ticket:number,edge?:-1|1):Promise<void>{
  status.textContent=`Opening ${fileLabel(file)}…`;const child=await session!.child(parent,file,stream,false);check(ticket);
  const lazy=lazyPdfs.get(parent)?.get(pdfKey(file,stream));
  if(lazy)lazy.list.rowAt(lazy.index);
  let row=nodes.get(child);
  if(!row){
    const parentRow=nodes.get(parent);const target=[...(parentRow?.querySelectorAll<HTMLButtonElement>('[data-embedded-file-id]')??[])].find(b=>b.dataset.embeddedFileId===file.id&&b.dataset.embeddedStream===stream.locator.path);
    row=documentTree(child);if(lazy)lazy.list.replaceRow(lazy.index,row);else if(target)target.closest('li')!.replaceWith(row);else parentRow?.querySelector(':scope > ul')?.append(row);
  }
  const single=!child.inspection.collection.value&&child.inspection.messages.length===1?child.inspection.messages[0]:undefined;
  await showDocument(child,ticket,single,row.querySelector('button')??undefined,edge);
  prepareBrowseSubjects(child,ticket);
}
// An edge gesture changes files only inside the immediate containing folder.
function scrollFile(direction:-1|1):boolean {
  const doc=current,parent=doc?.parent;
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
  metadataIndex=undefined;
  element('subject-index-status').textContent='Subject indexing stopped. Unread subjects still show filenames.';
  element('subject-index-cancel').hidden=true;
}
element('subject-index-cancel').addEventListener('click',stopBrowseIndex);
function prepareBrowseSubjects(doc:BrowserDocument,ticket:number):void {
  if(!doc.inspection.collection.value||browseCompleted.has(doc)||browseSession)return;
  const main=session!,epoch=++browseEpoch,isolated=new BrowserSession({},{reuseWorker:true});browseSession=isolated;
  const subjects=browseSubjects.get(doc)??new Map<string,string>();browseSubjects.set(doc,subjects);
  const counts=new Map<string,number>();const display=new Map<string,Record<string,string>>();browseDisplay.set(doc,display);
  const checkIndex=()=>{check(ticket);if(epoch!==browseEpoch)throw new Error('Subject indexing cancelled.');};
  const update=(messages:readonly IndexedMessage[])=>{
    checkIndex();
    for(const message of messages){
      if(message.path.length!==1)continue;
      const step=message.path[0]!,key=JSON.stringify([step.fileId,step.streamPath]),count=(counts.get(key)??0)+1;counts.set(key,count);
      if(count===1){subjects.set(key,message.label);display.set(key,{From:message.sender??'',Sent:message.sent??''});}else{subjects.delete(key);display.delete(key);}
    }
    // Update mounted rows in place, preserving focus, expansion and scroll position.
    for(const node of nodes.get(doc)?.querySelectorAll<HTMLButtonElement>('[data-embedded-file-id]')??[]){
      const label=subjects.get(JSON.stringify([node.dataset.embeddedFileId,node.dataset.embeddedStream]));
      node.querySelector('.message-text')?.remove();messageLabel(node,label||node.title,display.get(JSON.stringify([node.dataset.embeddedFileId,node.dataset.embeddedStream])));if(label){node.querySelector('.tree-icon')?.replaceWith(treeIcon('message'));}
    }
  };
  element('subject-index-progress').hidden=false;element('subject-index-progress').dataset.complete='false';element('subject-index-cancel').hidden=false;
  element('subject-index-status').textContent='Reading subjects in the background… You can browse now.';
  const work=(async()=>{
    try{
      // Yield so the loaded document and enabled controls paint before indexing starts.
      await new Promise(resolve=>setTimeout(resolve,0));checkIndex();
      let indexed=main.cachedMessageIndex(doc);
      if(indexed)update(indexed.messages);
      else{
        const blob=await main.sourceBlob(doc);checkIndex();const copy=await isolated.open(blob,doc.name);checkIndex();
        indexed=await indexMessages(isolated,copy,checkIndex,count=>{
          checkIndex();element('subject-index-status').textContent=`Reading subjects · ${count} emails. You can browse now.`;
        },update);
        checkIndex();element('subject-index-status').textContent=`Subjects read · ${indexed.messages.length} emails. Linking supporting files…`;
        await isolated.associateFiles(copy);checkIndex();
        main.adoptAssociations(doc,isolated,copy);if(current===doc)refreshInspection(doc);
      }
      checkIndex();browseCompleted.add(doc);browseMessageCounts.set(doc,indexed.messages.length);
      const collection=doc.inspection.collection.value;if(collection)for(const summary of nodes.get(doc)?.querySelectorAll<HTMLElement>('summary[data-folder-id]')??[]){const members=new Set<string>();for(const entry of collection.entries)for(let id of entry.folderIds.value??[]){const seen=new Set<string>();while(id&&!seen.has(id)){seen.add(id);if(id===summary.dataset.folderId)members.add(entry.fileId);id=collection.folders.find(f=>f.id===id)?.parentId??'';}}const count=indexed.messages.filter(m=>m.path[0]&&members.has(m.path[0].fileId)).length;const pill=text('span',String(count));pill.className='folder-count';summary.querySelector('.folder-count')?.remove();summary.append(pill);}
      element('subject-index-status').textContent=indexed.incomplete.length?`Subjects partially indexed: ${indexed.incomplete.join(' ')}`:`Subjects ready · ${indexed.messages.length} emails.`;
      element('subject-index-cancel').hidden=true;element('subject-index-progress').dataset.complete=String(!indexed.incomplete.length);element('message-count').textContent=String(indexed.messages.length);return indexed;
    }finally{isolated.close();if(browseSession===isolated)browseSession=undefined;}
  })();
  if(!doc.parent)metadataIndex=work;
  void work.catch(error=>{
    if(ticket!==generation||epoch!==browseEpoch)return;
    if(!doc.parent&&metadataIndex===work)metadataIndex=undefined;
    element('subject-index-status').textContent=`Subject indexing could not finish: ${error instanceof Error?error.message:String(error)}`;
    element('subject-index-cancel').hidden=true;
  });
}

function messageLabel(node:HTMLButtonElement,label:string,details?:Record<string,string>):void {
  const content=text('span',''),subject=text('span',label);content.className='message-text';subject.className='message-subject';subject.title=label;content.append(subject);
  const secondary=[details?.From||details?.['Raw-From'],details?.Sent].filter(Boolean).join(' · ');
  if(secondary){const meta=text('span',''),sender=text('span',details?.From||details?.['Raw-From']||''),date=text('span',details?.Sent?.match(/^\d{4}-\d{2}-\d{2}/)?.[0]||details?.Sent||'');meta.className='message-meta';meta.title=secondary;sender.className='message-sender';date.className='message-date';meta.append(sender);if(date.textContent)meta.append(text('span','·'),date);content.append(meta);}
  for(const child of [...node.childNodes])if(child.nodeType===Node.TEXT_NODE)child.remove();node.append(content);
}
function documentTree(doc:BrowserDocument):HTMLLIElement {
  const single=singleMessage(doc),label=single?(doc.details.get(single.metadataRecordId)?.Subject||doc.name):doc.name;
  const row=text('li',''),open=button(label,()=>void operation(ticket=>showDocument(doc,ticket,single,open)));open.dataset.documentId=doc.inspection.documentId;open.title=doc.name;if(single)open.dataset.messageId=single.id;open.prepend(treeIcon(single?'message':'document'));messageLabel(open,label,single?doc.details.get(single.metadataRecordId):undefined);open.classList.add('root-document');row.append(open);nodes.set(doc,row);
  const list=text('ul','');row.append(list);const collection=doc.inspection.collection.value;
  // Collection metadata describes the member emails, not body pages in the cover PDF.
  if(!collection&&!single){
    const makeMessage=(message:MailMessage,index:number)=>{
      const item=text('li',''),label=doc.details.get(message.metadataRecordId)?.Subject??`Message ${index+1}`;
      const choose=button(label,()=>void operation(ticket=>showDocument(doc,ticket,message,choose)));choose.disabled=busy;choose.dataset.messageId=message.id;choose.prepend(treeIcon('message'));messageLabel(choose,label,doc.details.get(message.metadataRecordId));item.append(choose);return item;
    };
    if(doc.inspection.messages.length>256){
      const virtual=new VirtualList(list,tree.closest('aside')!,doc.inspection.messages,makeMessage,40);
      lazyMessages.set(doc,{list:virtual,indices:new Map(doc.inspection.messages.map((m,i)=>[m.id,i]))});virtualLists.add(virtual);
    }else doc.inspection.messages.forEach((message,index)=>list.append(makeMessage(message,index)));
  }

  const folders=new Map<string,HTMLUListElement>(),folderRows:HTMLDetailsElement[]=[];
  if(collection){
    for(const folder of collection.folders)folders.set(folder.id,text('ul',''));
    for(const folder of collection.folders){
      const item=text('li',''),details=text('details',''),label=text('summary',folder.name.value??'Folder');label.dataset.folderId=folder.id;const chevron=treeIcon('right');chevron.classList.add('disclosure-icon');label.prepend(chevron,treeIcon('folder'));details.open=true;details.append(label,folders.get(folder.id)!);folderRows.push(details);item.append(details);
      const seen=new Set([folder.id]);let p=folder.parentId,valid=true;
      while(p){if(seen.has(p)){valid=false;break;}seen.add(p);const ancestor=collection.folders.find(f=>f.id===p);if(!ancestor){valid=false;break;}p=ancestor.parentId;}
      (valid&&folder.parentId?folders.get(folder.parentId)!:list).append(item);
    }
  }
  // Keep embedded PDFs separate from virtual message rows so remounting messages
  // never removes attachment navigation. Each folder retains its own member list.
  const groups=new Map<HTMLElement,PdfLink[]>();
  const entriesByFile=new Map<string,NonNullable<typeof collection>['entries'][number][]>();
  for(const entry of collection?.entries??[]){const group=entriesByFile.get(entry.fileId)??[];group.push(entry);entriesByFile.set(entry.fileId,group);}
  const attachments=collection?list:text('ul','');if(!collection)row.append(attachments);
  for(const file of doc.inspection.files)for(const stream of file.streams){
    if(!isPdf(file,stream))continue;
    const entries=entriesByFile.get(file.id)??[],ids=new Set(entries.flatMap(e=>e.folderIds.value??[]));
    const folder=ids.size===1&&entries.every(e=>e.folderIds.state==='present')?[...ids][0]:undefined;
    const target=folder?folders.get(folder)??attachments:attachments;
    const group=groups.get(target)??[];group.push({file,stream});groups.set(target,group);
  }
  const positions=new Map<string,{list:VirtualList<PdfLink>;index:number}>();lazyPdfs.set(doc,positions);
  const makePdf=({file,stream}:PdfLink)=>{
    for(const [child,existing] of nodes)if(child.parent===doc&&child.parentFileId===file.id&&child.parentStreamPath===stream.locator.path)return existing;
    const subject=browseSubjects.get(doc)?.get(pdfKey(file,stream));
    const item=text('li',''),openFile=button(subject||fileLabel(file),()=>void operation(ticket=>openChild(doc,file,stream,ticket)));
    openFile.disabled=busy;openFile.dataset.embeddedFileId=file.id;openFile.dataset.embeddedStream=stream.locator.path;openFile.title=fileLabel(file);openFile.prepend(treeIcon(subject?'message':'document'));messageLabel(openFile,subject||fileLabel(file),browseDisplay.get(doc)?.get(pdfKey(file,stream)));item.append(openFile);return item;
  };
  for(const [target,items] of groups){
    if(items.length>256){
      let memberList=target;
      if(target.children.length){const item=text('li',''),nested=text('ul','');item.append(nested);target.append(item);memberList=nested;}
      const virtual=new VirtualList(memberList,tree.closest('aside')!,items,makePdf,40);virtualLists.add(virtual);
      items.forEach((item,index)=>positions.set(pdfKey(item.file,item.stream),{list:virtual,index}));
    }else for(const item of items)target.append(makePdf(item));
  }
  // Empty folders have no disclosure control; populated folders start expanded.
  for(const details of folderRows)if(!details.querySelector(':scope > ul')!.children.length){const label=text('div','');label.className='folder-label';label.append(...details.querySelector('summary')!.childNodes);label.querySelector('.disclosure-icon')?.remove();details.replaceWith(label);}
  if([...row.children].some(child=>child.tagName==='UL'&&child.children.length)){
    const toggle=button('',()=>{const expanded=toggle.getAttribute('aria-expanded')!=='true';toggle.setAttribute('aria-expanded',String(expanded));for(const child of row.children)if(child.tagName==='UL')(child as HTMLElement).hidden=!expanded;});toggle.className='document-disclosure icon-button';toggle.setAttribute('aria-label',`Expand or collapse ${doc.name}`);toggle.title=`Expand or collapse ${doc.name}`;toggle.setAttribute('aria-expanded','true');toggle.append(treeIcon('right'));open.classList.add('has-disclosure');row.classList.add('document-row');row.append(toggle);
  }
  return row;
}
async function openFile(file:Blob,name:string):Promise<void>{
  if(busy)return;close();zoom.value='fit-width';element('linked-file').hidden=true;session=new BrowserSession();const active=session;
  await operation(async ticket=>{
    status.textContent=`Opening ${name}…`;
    tree.hidden=true;
    element('loading-message').textContent='Inspecting the email archive. Large files may take a while.';
    element('loading-progress').hidden=false;
    element('loading-cancel').textContent='Cancel';
    loadingTimer=setTimeout(()=>{if(ticket===generation)loadingDialog.showModal();},1000);
    try {
    const doc=await active.open(file,name,fraction=>{if(ticket===generation)status.textContent=`Inspecting ${name} · ${Math.round(fraction*100)}%`;},null,undefined,undefined,async preview=>{check(ticket);empty.hidden=true;await pages.show(preview,await pageScale(preview,1),1,'',false);check(ticket);status.textContent='First page ready. Email and file inspection continues…';},message=>{check(ticket);status.textContent=message;element('loading-message').textContent=message;});check(ticket);
    tree.append(documentTree(doc));await showDocument(doc,ticket);
    if(ticket===generation){finishLoading();prepareBrowseSubjects(doc,ticket);}
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
fileInput.addEventListener('change',()=>{const file=fileInput.files?.[0];fileInput.value='';if(file)void openFile(file,file.name);});
window.addEventListener('dragover',event=>{if(event.dataTransfer?.types.includes('Files')){event.preventDefault();viewport.classList.add('drop-target');}});
window.addEventListener('dragleave',event=>{if(!event.relatedTarget)viewport.classList.remove('drop-target');});
window.addEventListener('drop',event=>{event.preventDefault();viewport.classList.remove('drop-target');const file=event.dataTransfer?.files[0];if(file)void openFile(file,file.name);});
element('export-csv').addEventListener('click',()=>void operation(async ticket=>{
  const doc=current,active=session;if(!doc||!active)return;
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
    const result=await metadataCsv(source,(parent,file,stream)=>isolated.child(parent,file,stream,false),verify,progress,child=>isolated.release(child));
    verify();element('export-message').textContent=`Saving finding aid for ${result.rows.toLocaleString()} emails…`;
    await saveBlob(result.blob,doc.name.replace(/\.pdf$/i,'')+'-finding-aid.csv',verify,exportDialog);verify();
    status.textContent=`Exported finding aid for ${result.rows} messages.`;
  }catch(error){if(ticket===generation)status.textContent=cancelled?'Finding aid export cancelled.':`Metadata export failed: ${error instanceof Error?error.message:String(error)}`;}
  finally{
    clearInterval(timer);isolated.close();if(exportSession===isolated)exportSession=undefined;
    if(cancelExport===cancel){cancelExport=undefined;exportDialog.close();}
    if(ticket===generation&&!doc.active){const message=status.textContent;await render(ticket,false);status.textContent=message;}
  }
}));
element('close').addEventListener('click',close);window.addEventListener('pagehide',close);
element('back').addEventListener('click',()=>{if(current?.parent){const parent=current.parent;void operation(ticket=>showDocument(parent,ticket,parent.inspection.messages.find(m=>m.id===savedMessages.get(parent))));}});
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
function clearSearch():void {
  searchEpoch++;searchSession?.close();collectionResults=undefined;finder.clear();pages.highlight('');
  element('collection-matches').replaceChildren();element('collection-search-summary').textContent='';searchStatus.textContent='';
  element<HTMLButtonElement>('results-tab').disabled=true;navigationTab('browse');
}
search.addEventListener('input',()=>{if(!search.value.trim())clearSearch();});
search.addEventListener('keydown',event=>{if(event.key==='Escape'){if(!event.defaultPrevented){event.preventDefault();closeSearch();}}else if(event.key==='Enter'){event.preventDefault();void (element<HTMLSelectElement>('search-scope').value==='collection'?findInCollection(event.shiftKey?-1:1):findMatch(event.shiftKey?-1:1));}});
async function showOutline(doc:BrowserDocument,ticket:number):Promise<void>{
  const panel=element('outline-panel'),list=element('outline');list.replaceChildren();panel.hidden=true;
  try{const entries=await doc.run(doc.pdf.getOutline());check(ticket);if(current!==doc||!entries)return;let count=0;
    const pending=entries.slice().reverse().map(item=>({item,parent:list})),seen=new Set<typeof entries[number]>();
    while(pending.length){
      const {item,parent}=pending.pop()!;
      if(seen.has(item))throw new Error('Cyclic bookmark tree.');seen.add(item);
      const row=text('li','');
      if(item.dest)row.append(button(item.title,()=>void operation(async active=>{
        const dest=typeof item.dest==='string'?await doc.run(doc.pdf.getDestination(item.dest)):item.dest;check(active);if(!dest)throw new Error('Bookmark destination is unavailable.');
        const ref=dest[0];const index=typeof ref==='number'?ref:await doc.run(doc.pdf.getPageIndex(ref));check(active);if(index<0||index>=doc.pdf.numPages)throw new Error('Bookmark page is outside this PDF.');pageNumber=index+1;await render(active);
      })));else row.append(text('span',item.title+' (no internal page destination)'));
      parent.append(row);
      if(item.items.length){const nested=text('ul','');row.append(nested);for(const child of item.items.slice().reverse())pending.push({item:child,parent:nested});}
      if(++count%250===0){await new Promise(resolve=>setTimeout(resolve,0));check(ticket);}
    }
    panel.hidden=!list.children.length;
  }catch(error){if(ticket===generation&&current===doc){list.append(text('li','Bookmarks could not be read.'));panel.hidden=false;}}
}
async function showCollectionMatch(cached:CollectionResults,index:number,occurrence:number,ticket:number):Promise<void>{
  const match=cached.result.matches[index];if(!match)return;
  let doc=cached.root;
  for(const step of match.path){
    const file=doc.inspection.files.find(f=>f.id===step.fileId),stream=file?.streams.find(s=>s.locator.path===step.streamPath);
    if(!file||!stream)throw new Error('Search destination is no longer available.');
    if(current?.parent===doc&&current.parentFileId===step.fileId&&current.parentStreamPath===step.streamPath)doc=current;
    else{await openChild(doc,file,stream,ticket);doc=current!;}
  }
  if(current!==doc)await showDocument(doc,ticket);
  if(pageNumber!==match.page){pageNumber=match.page;await render(ticket,false);}
  check(ticket);syncMessageToPage();pages.highlight(cached.query,{page:match.page,index:occurrence});
  cached.index=index;cached.occurrence=occurrence;
  const number=cached.result.matches.slice(0,index).reduce((n,m)=>n+m.count,0)+occurrence+1;
  searchStatus.textContent=`Match ${number} of ${cached.total} in collection${cached.result.incomplete.length?' (incomplete search)':''}.`;
  for(const node of element('collection-matches').querySelectorAll<HTMLButtonElement>('button[data-match-index]')){
    if(Number(node.dataset.matchIndex)===index)node.setAttribute('aria-current','true');else node.removeAttribute('aria-current');
  }
  const active=element('collection-matches').querySelector<HTMLElement>('button[aria-current]');
  if(active&&!element('collection-results').hidden){
    const panel=element('search-panel'),bounds=panel.getBoundingClientRect(),box=active.getBoundingClientRect();
    const top=bounds.top;
    if(box.top<top)panel.scrollTop-=Math.ceil(top-box.top);
    else if(box.bottom>bounds.bottom)panel.scrollTop+=Math.ceil(box.bottom-bounds.bottom);
  }
}
async function findInCollection(direction:1|-1=1,first=false):Promise<void>{
  if(!current||!session||!search.value.trim())return;
  await operation(async ticket=>{
    const query=search.value.trim();let root=current!;while(root.parent)root=root.parent;
    const cached=collectionResults;
    if(cached&&cached.root===root&&cached.query.toLowerCase()===query.toLowerCase()){
      if(!cached.result.matches.length){searchStatus.textContent=cached.result.incomplete.length?'No matches found in an incomplete collection search.':'No matching text in this collection.';return;}
      let index=cached.index,occurrence=cached.occurrence+direction;
      if(index<0){index=direction===1?0:cached.result.matches.length-1;occurrence=direction===1?0:cached.result.matches[index]!.count-1;}
      else if(occurrence>=cached.result.matches[index]!.count){index=(index+1)%cached.result.matches.length;occurrence=0;}
      else if(occurrence<0){index=(index+cached.result.matches.length-1)%cached.result.matches.length;occurrence=cached.result.matches[index]!.count-1;}
      navigationTab('results');
      await showCollectionMatch(cached,first?0:index,first?0:occurrence,ticket);return;
    }
    collectionResults=undefined;
    const epoch=++searchEpoch;const checkSearch=()=>{check(ticket);if(epoch!==searchEpoch)throw new Error('Search cancelled.');};
    const main=session!,isolated=new BrowserSession({},{reuseWorker:true});searchSession=isolated;
    element('cancel-search').hidden=false;const list=element('collection-matches');list.replaceChildren();element<HTMLButtonElement>('results-tab').disabled=false;navigationTab('results');
    element('collection-search-summary').textContent='Searching collection…';
    try{
      const blob=await main.sourceBlob(root);checkSearch();const copy=await isolated.open(blob,root.name);checkSearch();
      const result=await searchCollection(isolated,copy,query,checkSearch,count=>{searchStatus.textContent=`Searching collection · ${count} pages…`;});checkSearch();
      const saved:CollectionResults={root,query,result,index:-1,occurrence:0,total:result.matches.reduce((n,m)=>n+m.count,0)};collectionResults=saved;
      const groups=new Map<string,HTMLUListElement>();
      for(const [index,match] of result.matches.entries()){
        const key=JSON.stringify(match.path);let group=groups.get(key);
        if(!group){
          const row=text('li','');row.className='collection-result';
          // The stored path still identifies the destination; show only its file name.
          const name=match.path.length?match.document.split(' / ').at(-1)!:'Collection overview';
          const heading=text('h3','');heading.className='result-heading';heading.title=name;heading.append(treeIcon('document'),text('span',name));
          group=text('ul','');group.className='result-pages';row.append(heading,group);list.append(row);groups.set(key,group);
        }
        const row=text('li',''),choose=button('',()=>void operation(async active=>{
          if(collectionResults!==saved)return;search.value=saved.query;element<HTMLSelectElement>('search-scope').value='collection';
          await showCollectionMatch(saved,index,0,active);
        }));choose.className='collection-hit';choose.dataset.matchIndex=String(index);
        const meta=text('span','');meta.className='match-meta';const selected=text('span','✓ Viewing');selected.className='match-selected';
        meta.append(text('span',`Page ${match.page} · ${match.count} ${match.count===1?'match':'matches'}`),selected);
        const excerpt=text('span','');excerpt.className='match-excerpt';let offset=0;excerpt.append(document.createTextNode('“'));
        for(const found of textMatches(match.excerpt,query)){
          excerpt.append(document.createTextNode(match.excerpt.slice(offset,found.start)),text('mark',match.excerpt.slice(found.start,found.end)));offset=found.end;
        }
        excerpt.append(document.createTextNode(match.excerpt.slice(offset)+'”'));
        choose.append(meta,excerpt);row.append(choose);group.append(row);
      }
      element('collection-search-summary').textContent=`${result.matches.length} matching pages. ${result.incomplete.length?'Incomplete: '+result.incomplete.join(' '):'All reachable collection PDFs were searched.'}`;
      if(result.matches.length){const index=direction===1?0:result.matches.length-1;await showCollectionMatch(saved,index,direction===1?0:result.matches[index]!.count-1,ticket);}
      else searchStatus.textContent=result.incomplete.length?'No matches found in an incomplete collection search.':'No matching text in this collection.';
    }catch(error){if(epoch!==searchEpoch){searchStatus.textContent='Search cancelled.';element('collection-search-summary').textContent='Search cancelled; no complete result set was produced.';}else throw error;}
    finally{isolated.close();if(searchSession===isolated)searchSession=undefined;element('cancel-search').hidden=true;}
  });
}
async function findMatch(direction:1|-1):Promise<void>{
  const query=search.value.trim(),doc=current;if(!query||!doc)return;
  await operation(async ticket=>{
    const epoch=++searchEpoch;const checkSearch=()=>{check(ticket);if(epoch!==searchEpoch)throw new Error('Search cancelled.');};
    element('cancel-search').hidden=false;searchStatus.textContent='Searching this PDF…';
    try{await session!.ensure(doc);const result=await finder.find(doc,query,pageNumber,direction,checkSearch);checkSearch();
      if('page'in result){pageNumber=result.page;await render(ticket);checkSearch();pages.highlight(query,result);searchStatus.textContent=`Match ${result.index+1} of ${result.count} on page ${result.page}`;}
      else searchStatus.textContent=result.incomplete?'Search limit reached; results are incomplete.':'No matching text in this document.';
    }catch(error){if(epoch!==searchEpoch){searchStatus.textContent='Search cancelled.';return;}throw error;}
    finally{element('cancel-search').hidden=true;}
  });
}
element('search-form').addEventListener('submit',event=>{event.preventDefault();finder.clear();void (element<HTMLSelectElement>('search-scope').value==='collection'?findInCollection(1,true):findMatch(1));});
element('find').addEventListener('click',()=>void (element<HTMLSelectElement>('search-scope').value==='collection'?findInCollection():findMatch(1)));
element('find-previous').addEventListener('click',()=>void (element<HTMLSelectElement>('search-scope').value==='collection'?findInCollection(-1):findMatch(-1)));
element('cancel-search').addEventListener('click',()=>{searchEpoch++;searchSession?.close();searchStatus.textContent='Cancelling search…';});
element('retry').addEventListener('click',()=>void operation(ticket=>render(ticket)));
element('restart').addEventListener('click',()=>{
  const doc=current;if(!doc||busy)return;let root=doc;while(root.parent)root=root.parent;
  void operation(async ticket=>{const blob=await session!.sourceBlob(root);check(ticket);busy=false;await openFile(blob,root.name);status.textContent='PDF reopened. Select a message to continue.';});
});
const filter=element<HTMLInputElement>('message-filter');
const filterResults=text('ul','');filterResults.id='filter-results';filterResults.hidden=true;tree.before(filterResults);
let filterList:VirtualList<IndexedMessage>|undefined;
let metadataSession:BrowserSession|undefined,metadataIndex:Promise<MessageIndex>|undefined,metadataEpoch=0,filterRevision=0;
let filterTimer:ReturnType<typeof setTimeout>|undefined;
async function filterMessages():Promise<void>{
  filterList?.close();filterList=undefined;filterResults.replaceChildren();
  const query=filter.value.trim().toLowerCase();tree.hidden=!!query;filterResults.hidden=!query;
  if(!query||!current||!session)return;
  const ticket=generation,epoch=metadataEpoch,revision=filterRevision;let root=current;while(root.parent)root=root.parent;
  const checkIndex=()=>{check(ticket);if(epoch!==metadataEpoch)throw new Error('Email search cancelled.');};
  const cachedIndex=session.cachedMessageIndex(root);
  if(!metadataIndex&&cachedIndex)metadataIndex=Promise.resolve(cachedIndex);
  if(!metadataIndex){
    const main=session,isolated=new BrowserSession({},{reuseWorker:true});metadataSession=isolated;
    metadataIndex=(async()=>{try{
      const blob=await main.sourceBlob(root);checkIndex();const copy=await isolated.open(blob,root.name);checkIndex();
      return await indexMessages(isolated,copy,checkIndex,count=>{if(filter.value.trim())element('filter-status').textContent=`Reading email list · ${count} emails…`;});
    }finally{isolated.close();if(metadataSession===isolated)metadataSession=undefined;}})();
  }
  element('filter-status').textContent='Finding emails…';
  try{
    const result=await metadataIndex;checkIndex();if(revision!==filterRevision||query!==filter.value.trim().toLowerCase())return;
    const matches=result.messages.filter(m=>m.searchText.includes(query));
    const messageCounts=new Map<string,number>();
    for(const item of result.messages){const key=JSON.stringify(item.path);messageCounts.set(key,(messageCounts.get(key)??0)+1);}
    const create=(item:IndexedMessage)=>{const row=text('li',''),name=item.document.split(' / ').at(-1)!;
      const choose=button(item.label,()=>void operation(async active=>{
      let doc=root;
      for(const step of item.path){const file=doc.inspection.files.find(f=>f.id===step.fileId),stream=file?.streams.find(s=>s.locator.path===step.streamPath);
        if(!file||!stream)throw new Error('Email destination is no longer available.');await openChild(doc,file,stream,active);doc=current!;}
      const message=doc.inspection.messages.find(m=>m.id===item.messageId);if(!message)throw new Error('Email metadata is no longer available.');
      filter.value='';filterResults.hidden=true;tree.hidden=false;await showDocument(doc,active,message);
    }));choose.title=name;choose.prepend(treeIcon('message'));messageLabel(choose,item.label,{From:item.sender??'',Sent:item.sent??''});row.append(choose);
      return row;};
    if(matches.length>256)filterList=new VirtualList(filterResults,tree.closest('aside')!,matches,create,60);else for(const item of matches)filterResults.append(create(item));
    element('filter-status').textContent=`${matches.length} matching emails. ${result.incomplete.length?'Search incomplete: '+result.incomplete.join(' '):'All reachable email metadata searched.'}`;
  }catch(error){if(ticket===generation&&epoch===metadataEpoch&&revision===filterRevision){metadataIndex=undefined;element('filter-status').textContent=`Email search could not finish: ${error instanceof Error?error.message:String(error)}`;}}
}
filter.addEventListener('input',()=>{filterRevision++;clearTimeout(filterTimer);if(!filter.value.trim()){filterList?.close();filterList=undefined;filterResults.replaceChildren();filterResults.hidden=true;tree.hidden=false;element('filter-status').textContent='';return;}filterTimer=setTimeout(()=>void filterMessages(),200);});
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
window.addEventListener('keydown',event=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='f'&&!document.querySelector('dialog[open]')){event.preventDefault();openSearch();}else if(event.key==='Escape'&&!element('search-panel').hidden){event.preventDefault();closeSearch();}},{capture:true});
for(const [id,kind]of [['close','close'],['close-search','close'],['previous','left'],['next','right'],['find-previous','left'],['find','right'],['toggle-navigation','left'],['toggle-details','right'],['back','up'],['zoom-out','minus'],['zoom-in','plus']] as const){const control=element(id);if(!control.hasAttribute('aria-label'))control.setAttribute('aria-label',control.textContent!.trim());if(!control.title)control.title=control.getAttribute('aria-label')!;control.replaceChildren(treeIcon(kind));control.classList.add('icon-button');}
for(const [id,kind]of [['open-search','search'],['export-csv','download']] as const)element(id).prepend(treeIcon(kind));
document.querySelector('.open-file')!.prepend(treeIcon('folder'));
// Native buttons/disclosures retain their semantics; arrows supplement Tab navigation.
tree.addEventListener('keydown',event=>{const target=event.target;if(!(target instanceof HTMLElement))return;const visible=[...tree.querySelectorAll<HTMLElement>('button,summary')].filter(node=>node.getClientRects().length&&!node.closest('[hidden]')),index=visible.indexOf(target);if(index<0)return;let next:HTMLElement|undefined;
 if(event.key==='ArrowDown')next=visible[index+1];else if(event.key==='ArrowUp')next=visible[index-1];else if(event.key==='Home')next=visible[0];else if(event.key==='End')next=visible.at(-1);else if(event.key==='ArrowRight'&&target.tagName==='SUMMARY'){(target.parentElement as HTMLDetailsElement).open=true;event.preventDefault();}else if(event.key==='ArrowLeft'){const parent=target.closest('details');if(parent){parent.open=false;next=parent.querySelector('summary')!;}}
 if(next){event.preventDefault();next.focus();}
});
