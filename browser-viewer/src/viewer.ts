import {saveBlob} from './downloads.js';
import {indexMessages,type MessageIndex,type IndexedMessage} from './message-index.js';
import {searchCollection} from './collection-search.js';
import {DocumentSearch} from './document-search.js';
import {BrowserSession,type BrowserDocument} from './browser-session.js';
import {AttachmentViewer} from './attachment-viewer.js';
import {VirtualList} from './virtual-list.js';
import {ScrollingPages} from './scrolling-pages.js';
import type {AssociatedFile,EmbeddedStream,MailMessage,TextObservation} from './domain/inspection.js';
import {fileLabel,messagePages,folderFiles} from './navigation.js';
import {metadataCsv} from './metadata-csv.js';
import {setupLinkedOpening} from './linked-opening.js';

function element<T extends HTMLElement>(id:string):T {const node=document.getElementById(id);if(!node)throw new Error('Missing element: '+id);return node as T;}
function text<K extends keyof HTMLElementTagNameMap>(tag:K,value:string){const node=document.createElement(tag);node.textContent=value;return node;}
function button(label:string,action:()=>void){const node=text('button',label);node.type='button';node.addEventListener('click',action);return node;}
function treeIcon(kind:'folder'|'message'|'document'):SVGSVGElement {
  const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');
  icon.setAttribute('viewBox','0 0 24 24');icon.setAttribute('aria-hidden','true');icon.setAttribute('focusable','false');icon.classList.add('tree-icon');
  const paths=kind==='folder'?['M3 7V5h6l2 2h10v13H3Z','M3 10h18l-3 10H3Z']:kind==='message'?['M3 5h18v14H3Z','m3 6 9 7 9-7']:['M5 3h9l5 5v13H5Z','M14 3v6h5M8 13h8M8 17h8'];
  paths.forEach((d,index)=>{const path=document.createElementNS(icon.namespaceURI,'path');path.setAttribute('d',d);if(kind==='folder')path.setAttribute('class',index?'folder-open':'folder-closed');icon.append(path);});
  return icon;
}
const fileInput=element<HTMLInputElement>('file'),pageInput=element<HTMLInputElement>('page'),zoom=element<HTMLSelectElement>('zoom');
const status=element('status'),tree=element('tree'),files=element('files'),info=element('document-info'),diagnostics=element('diagnostics');
const surface=element('page-surface'),viewport=element('viewport'),empty=element('empty-state');
const search=element<HTMLInputElement>('search'),searchStatus=element('search-status');
const attachmentViewer=new AttachmentViewer();
const pages=new ScrollingPages(viewport,surface,number=>{
  pageNumber=number;syncMessageToPage();if(current){savedPages.set(current,number);status.textContent=`${current.name} — Page ${number} of ${current.pdf.numPages}`;}controls();
},message=>{status.textContent=`Unable to display PDF: ${message}`;});
let cancelLinked=()=>{};
const finder=new DocumentSearch();let searchEpoch=0;
const selectedMessage=(doc:BrowserDocument)=>doc.inspection.messages.find(m=>m.id===savedMessages.get(doc));
let exportSession:BrowserSession|undefined;
let searchSession:BrowserSession|undefined;
let session:BrowserSession|undefined,current:BrowserDocument|undefined,busy=false,generation=0,pageNumber=1,editingPage=false;
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
  pageInput.disabled=zoom.disabled=search.disabled=element<HTMLButtonElement>('find').disabled=busy||!available;
  element<HTMLButtonElement>('previous').disabled=busy||!available||pageNumber<=1;
  element<HTMLButtonElement>('next').disabled=busy||!available||pageNumber>=(current?.pdf.numPages??0);
  element<HTMLButtonElement>('export-csv').disabled=busy||!available;
  for(const id of ['retry','restart','find-previous'])element<HTMLButtonElement>(id).disabled=busy||!available;
  element<HTMLButtonElement>('back').disabled=busy||!current?.parent;
  element<HTMLButtonElement>('zoom-out').disabled=busy||!available||Number(zoom.value)<=0.5;
  element<HTMLButtonElement>('zoom-in').disabled=busy||!available||zoom.selectedIndex>=zoom.options.length-1;
  element<HTMLButtonElement>('close').disabled=!session;
  for(const b of document.querySelectorAll<HTMLButtonElement>('#tree button,#files button,#breadcrumbs button'))b.disabled=busy||b.dataset.unavailable==='true';
  if(!editingPage||document.activeElement!==pageInput){pageInput.value=String(pageNumber);pageInput.removeAttribute('aria-invalid');editingPage=false;}
  element('pages').textContent=`of ${current?.pdf.numPages??0}`;
  viewport.setAttribute('aria-busy',String(busy));
}
function check(ticket:number):void {if(ticket!==generation)throw new Error('Cancelled.');}
async function operation(action:(ticket:number)=>Promise<void>):Promise<void>{
  if(busy)return;const ticket=generation;busy=true;controls();
  try{await action(ticket);}catch(error){if(ticket===generation){element('restart').hidden=!current;status.textContent=`Unable to display PDF: ${error instanceof Error?error.message:String(error)}`;}}
  finally{if(ticket===generation){busy=false;controls();}}
}
function close():void {
  metadataEpoch++;metadataSession?.close();metadataSession=undefined;metadataIndex=undefined;clearTimeout(filterTimer);
  filterList?.close();filterList=undefined;filterResults.replaceChildren();filterResults.hidden=true;filter.value='';tree.hidden=false;
  for(const list of virtualLists)list.close();virtualLists.clear();fileList?.close();fileList=undefined;diagnosticList?.close();diagnosticList=undefined;
  cancelLinked();searchSession?.close();searchSession=undefined;exportSession?.close();exportSession=undefined;
  generation++;searchEpoch++;finder.clear();pages.clear();attachmentViewer.close();session?.close();session=undefined;current=undefined;
  nodes.clear();savedPages.clear();savedMessages.clear();busy=false;pageNumber=1;editingPage=false;
  element('restart').hidden=true;empty.hidden=false;element('reading-notes').hidden=true;element('collection-results').hidden=true;element('collection-matches').replaceChildren();element('outline').replaceChildren();element('outline-panel').hidden=true;
  for(const panel of [tree,files,info,diagnostics,element('breadcrumbs')])panel.replaceChildren();
  element('file-count').textContent=element('note-count').textContent='0';search.value='';searchStatus.textContent='';
  status.textContent='Closed. Open or drop a PDF to begin.';controls();
}
async function render(ticket:number,followMessage=true):Promise<void>{
  const doc=current;if(!doc||!session)return;await session.ensure(doc);check(ticket);
  status.textContent=`Rendering page ${pageNumber}…`;
  let scale=Number(zoom.value)*PDF_TO_CSS;
  if(zoom.value.startsWith('fit-')){const page=await doc.run(doc.pdf.getPage(pageNumber));try{const v=page.getViewport({scale:1});scale=(viewport.clientWidth-60)/v.width;if(zoom.value==='fit-page')scale=Math.min(scale,(viewport.clientHeight-60)/v.height);}finally{page.cleanup();}check(ticket);}
  empty.hidden=true;await pages.show(doc,Math.max(0.1,scale),pageNumber,search.value);check(ticket);
  empty.hidden=true;status.textContent=`${doc.name} — Page ${pageNumber} of ${doc.pdf.numPages}`;savedPages.set(doc,pageNumber);if(followMessage)syncMessageToPage();
}

function pair(parent:HTMLElement,label:string,value:string):void {parent.append(text('dt',label),text('dd',value));}
function observation(value:TextObservation):string {return value.value??value.state;}
function markSelection(node:HTMLElement|undefined):void {for(const n of tree.querySelectorAll('[aria-current]'))n.removeAttribute('aria-current');node?.setAttribute('aria-current','true');for(let parent=node?.parentElement;parent&&parent!==tree;parent=parent.parentElement)if(parent instanceof HTMLDetailsElement)parent.open=true;}
// Update selection only: following the page must never navigate back to a
// message's first page. Cache ownership instead of rescanning DParts on scroll.
function syncMessageToPage():void {
  const doc=current,root=doc?nodes.get(doc):undefined;
  if(!doc||!root||doc.inspection.collection.value||doc.inspection.messages.length<2)return;
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
  const row=ensured??(message?root.querySelector<HTMLButtonElement>(`:scope > ul > li > button[data-message-id="${CSS.escape(message.id)}"]`):root.querySelector<HTMLButtonElement>(':scope > button[data-document-id]'));
  if(!row||(savedMessages.get(doc)===message?.id&&row.hasAttribute('aria-current')))return;
  savedMessages.set(doc,message?.id);markSelection(row);showFiles(doc,message);
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
let diagnosticList:VirtualList<BrowserDocument['inspection']['diagnostics'][number]>|undefined;
function showInformation(doc:BrowserDocument):void {
  info.replaceChildren();const list=text('dl',''),id=doc.inspection.identification;
  pair(list,'Document',doc.name);pair(list,'Declared profile',id.declaredProfile.value?`${id.eapdfVersion.value??'?'}${id.declaredProfile.value}`:id.declaredProfile.state);
  pair(list,'EA-PDF revision',observation(id.eapdfRevision));pair(list,'File size',sizeLabel(doc.inspection.source.byteLength));info.append(list);
  diagnosticList?.close();diagnosticList=undefined;diagnostics.replaceChildren();
  const notes=doc.inspection.diagnostics;
  if(notes.length>256){
    const list=text('ul','');list.style.listStyle='none';list.style.padding='0';diagnostics.append(list);
    diagnosticList=new VirtualList(list,diagnostics.closest('aside')!,notes,d=>{const row=text('li','');row.append(text('p',`${d.message} (${d.code})`));return row;},42);
  }else for(const d of notes)diagnostics.append(text('p',`${d.message} (${d.code})`));
  element('reading-notes').hidden=doc.inspection.diagnostics.length===0;
  element('note-count').textContent=String(doc.inspection.diagnostics.length);breadcrumbs(doc);
}
function refreshInspection(doc:BrowserDocument):void {
  if(current!==doc)return;showInformation(doc);showFiles(doc,singleMessage(doc)?undefined:selectedMessage(doc));
  const mismatches=doc.inspection.diagnostics.filter(d=>d.code==='CHECKSUM_MISMATCH');
  if(mismatches.length){element('reading-notes').closest('details')!.open=true;status.textContent='Warning: an embedded file differs from its recorded checksum. See Reading notes.';}
}
function showFiles(doc:BrowserDocument,message?:MailMessage):void {
  fileList?.close();fileList=undefined;files.replaceChildren();const selected=doc.inspection.files.filter(f=>!message||f.owners.value?.includes(message.id));element('file-count').textContent=String(selected.length);
  files.append(text('p',`Files stored in ${doc.name}.`));
  if(message){const note=text('p','Files for this email. Select the document name for all files stored in this PDF.');note.className='file-note';files.append(note);}
  if(doc.inspection.coverage.associatedFiles!=='present'){const note=text('p','Embedded files could not be fully inspected. This list may be incomplete; see Reading notes.');note.className='file-note';files.append(note);}
  if(!selected.length){if(doc.inspection.coverage.associatedFiles==='present'){const note=text('p',message&&doc.inspection.files.length?'No files are associated with this email in the PDF. See Reading notes for document errors.':'No embedded files in this selection.');note.className='muted';files.append(note);}}
  const list=text('ul','');files.append(list);
  const createFile=(file:AssociatedFile)=>{
    const row=text('li','');row.className='file-card';const label=text('strong',fileLabel(file));label.className='file-name';row.append(label);
    const category=file.afRelationship.value==='Source'?'Email source':file.afRelationship.value==='Supplement'?'Technical or supporting file':file.owners.value?.length?'Email attachment':'Archive file';
    row.append(text('p',category));
    if(!message)for(const id of file.owners.value??[]){
      const index=doc.inspection.messages.findIndex(m=>m.id===id),owner=doc.inspection.messages[index];if(!owner)continue;
      const label=`Email ${index+1}: ${doc.details.get(owner.metadataRecordId)?.Subject||owner.internetMessageId.value||owner.mailGuid.value||'Untitled'}`;
      const link=button(label,()=>void operation(ticket=>showDocument(doc,ticket,owner)));link.className='file-owner';row.append(link);
    }
    for(const stream of file.streams){
      const size=stream.decodedSize.value??stream.declaredSize.value,type=stream.mimeType.value?.split(';')[0]||fileLabel(file).split('.').at(-1)?.toUpperCase()||'File';
      const facts=text('p',`${type} · ${sizeLabel(size)}${file.streams.length>1?' · '+stream.efKeys.join('/'):''}`);facts.className='file-facts';row.append(facts);
      const actions=text('div','');actions.className='file-actions';row.append(actions);
      const save=button(file.afRelationship.value==='Source'&&/\.eml$/i.test(fileLabel(file))?'Download email (.eml)':'Download',()=>void operation(async ticket=>{status.textContent=`Reading ${fileLabel(file)}…`;const data=await session!.bytes(doc,file,stream);check(ticket);await saveBlob(new Blob([new Uint8Array(data)],{type:'application/octet-stream'}),fileLabel(file),()=>check(ticket));check(ticket);refreshInspection(doc);status.textContent=`Download requested: ${fileLabel(file)} (${sizeLabel(data.length)}).${doc.inspection.diagnostics.some(d=>d.code==='CHECKSUM_MISMATCH')?' Warning: recorded checksum mismatch; see Reading notes.':''}`;}));save.dataset.fileId=file.id;save.dataset.stream=stream.locator.path;actions.append(save);
      if(size===0){const note=text('p','Empty embedded file (zero bytes). It may be an empty original or a placeholder.');note.className='file-note';row.append(note);}
      else if(canView(file,stream)){
        const view=button(isPdf(file,stream)?'Open PDF':'View',()=>void operation(async ticket=>{
          if(isPdf(file,stream)){await openChild(doc,file,stream,ticket);return;}
          status.textContent=`Reading ${fileLabel(file)}…`;const data=await session!.bytes(doc,file,stream);check(ticket);refreshInspection(doc);await attachmentViewer.open(data,fileLabel(file),stream.mimeType.value??'');check(ticket);status.textContent=`${doc.name} — Page ${pageNumber} of ${doc.pdf.numPages}`;
        }));view.className='view-action';view.dataset.viewFileId=file.id;view.dataset.viewStream=stream.locator.path;actions.prepend(view);
      }
    }
    if(!file.streams.length){const note=text('p','Embedded content is missing or cannot be read.');note.className='file-note';row.append(note);}
    for(const b of row.querySelectorAll<HTMLButtonElement>('button'))b.disabled=busy;
    return row;
  };
  if(selected.length>256)fileList=new VirtualList(list,files.closest('aside')!,selected,createFile,220);
  else {
    const groups=new Map<string,AssociatedFile[]>();
    for(const file of selected){const name=file.afRelationship.value==='Source'?'Email sources':file.afRelationship.value==='Supplement'?'Technical and supporting files':file.owners.value?.length?'Email attachments':'Archive files';const group=groups.get(name)??[];group.push(file);groups.set(name,group);}
    for(const [name,group]of groups){const section=text('li',''),heading=text('h3',name),items=text('ul','');section.append(heading,items);list.append(section);for(const file of group)items.append(createFile(file));}
  }
}
function singleMessage(doc:BrowserDocument):MailMessage|undefined {
  return !doc.inspection.collection.value&&doc.inspection.messages.length===1?doc.inspection.messages[0]:undefined;
}
async function showDocument(doc:BrowserDocument,ticket:number,message?:MailMessage,node?:HTMLElement,edge?:-1|1):Promise<void>{
  message??=singleMessage(doc);
  await session!.ensure(doc);await session!.associateFiles(doc);check(ticket);message=message?doc.inspection.messages.find(m=>m.id===message!.id):undefined;if(current!==doc){info.closest('details')!.open=true;finder.clear();}if(current)savedPages.set(current,pageNumber);current=doc;editingPage=false;savedMessages.set(doc,message?.id);
  const destinations=message?messagePages(doc.inspection,message):[];pageNumber=edge===1?1:edge===-1?doc.pdf.numPages:destinations[0]??savedPages.get(doc)??1;
  markSelection(node??nodes.get(doc)?.querySelector<HTMLButtonElement>(':scope > button')??undefined);showInformation(doc);showFiles(doc,singleMessage(doc)?undefined:message);searchStatus.textContent='';
  await render(ticket,false);
  await showOutline(doc,ticket);
  if(edge===-1)viewport.scrollTop=viewport.scrollHeight-viewport.clientHeight;
  if(edge){nodes.get(doc)?.querySelector('button')?.scrollIntoView({block:'nearest'});status.textContent=`${doc.name} — Page ${pageNumber} of ${doc.pdf.numPages} · ${edge===1?'Next':'Previous'} file in this folder`;}
  if(message&&!destinations.length)status.textContent+=' — Message page mapping unavailable; showing document page.';
  if(doc.inspection.coverage.metadata==='unavailable'){
    element('diagnostics').closest('details')!.open=true;
    status.textContent+=' — Email metadata unavailable; see Reading notes.';
  }
}
async function openChild(parent:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,ticket:number,edge?:-1|1):Promise<void>{
  status.textContent=`Opening ${fileLabel(file)}…`;const child=await session!.child(parent,file,stream);check(ticket);
  const lazy=lazyPdfs.get(parent)?.get(pdfKey(file,stream));
  if(lazy)lazy.list.rowAt(lazy.index);
  let row=nodes.get(child);
  if(!row){
    const parentRow=nodes.get(parent);const target=[...(parentRow?.querySelectorAll<HTMLButtonElement>('[data-embedded-file-id]')??[])].find(b=>b.dataset.embeddedFileId===file.id&&b.dataset.embeddedStream===stream.locator.path);
    row=documentTree(child);if(lazy)lazy.list.replaceRow(lazy.index,row);else if(target)target.closest('li')!.replaceWith(row);else parentRow?.querySelector(':scope > ul')?.append(row);
  }
  const single=!child.inspection.collection.value&&child.inspection.messages.length===1?child.inspection.messages[0]:undefined;
  await showDocument(child,ticket,single,row.querySelector('button')??undefined,edge);
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
        const child=await session!.child(parent,file,streams[0]!);check(ticket);
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
function documentTree(doc:BrowserDocument):HTMLLIElement {
  const single=singleMessage(doc),label=single?(doc.details.get(single.metadataRecordId)?.Subject||doc.name):doc.name;
  const row=text('li',''),open=button(label,()=>void operation(ticket=>showDocument(doc,ticket,single,open)));open.dataset.documentId=doc.inspection.documentId;open.title=doc.name;if(single)open.dataset.messageId=single.id;open.prepend(treeIcon(single?'message':'document'));row.append(open);nodes.set(doc,row);
  const list=text('ul','');row.append(list);const collection=doc.inspection.collection.value;
  // Collection metadata describes the member emails, not body pages in the cover PDF.
  if(!collection&&!single){
    const makeMessage=(message:MailMessage,index:number)=>{
      const item=text('li',''),label=doc.details.get(message.metadataRecordId)?.Subject??`Message ${index+1}`;
      const choose=button(label,()=>void operation(ticket=>showDocument(doc,ticket,message,choose)));choose.disabled=busy;choose.dataset.messageId=message.id;choose.prepend(treeIcon('message'));item.append(choose);return item;
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
      const item=text('li',''),details=text('details',''),label=text('summary',folder.name.value??'Folder');label.prepend(treeIcon('folder'));details.append(label,folders.get(folder.id)!);folderRows.push(details);item.append(details);
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
    const item=text('li',''),openFile=button(fileLabel(file),()=>void operation(ticket=>openChild(doc,file,stream,ticket)));
    openFile.disabled=busy;openFile.dataset.embeddedFileId=file.id;openFile.dataset.embeddedStream=stream.locator.path;openFile.title=fileLabel(file);openFile.prepend(treeIcon('document'));item.append(openFile);return item;
  };
  for(const [target,items] of groups){
    if(items.length>256){
      let memberList=target;
      if(target.children.length){const item=text('li',''),nested=text('ul','');item.append(nested);target.append(item);memberList=nested;}
      const virtual=new VirtualList(memberList,tree.closest('aside')!,items,makePdf,40);virtualLists.add(virtual);
      items.forEach((item,index)=>positions.set(pdfKey(item.file,item.stream),{list:virtual,index}));
    }else for(const item of items)target.append(makePdf(item));
  }
  // Empty folders have no disclosure control. All other folders start closed.
  for(const details of folderRows)if(!details.querySelector(':scope > ul')!.children.length){const label=text('div','');label.className='folder-label';label.append(...details.querySelector('summary')!.childNodes);details.replaceWith(label);}
  return row;
}
async function openFile(file:Blob,name:string):Promise<void>{
  if(busy)return;close();element('linked-file').hidden=true;session=new BrowserSession();const active=session;
  await operation(async ticket=>{
    status.textContent=`Opening ${name}…`;
    const doc=await active.open(file,name,fraction=>{if(ticket===generation)status.textContent=`Inspecting ${name} · ${Math.round(fraction*100)}%`;},null,undefined,undefined,async preview=>{check(ticket);empty.hidden=true;await pages.show(preview,PDF_TO_CSS,1,'');check(ticket);status.textContent='First page ready. Email and file inspection continues…';});check(ticket);
    tree.append(documentTree(doc));zoom.value='1';await showDocument(doc,ticket);
  });
}
fileInput.addEventListener('change',()=>{const file=fileInput.files?.[0];fileInput.value='';if(file)void openFile(file,file.name);});
window.addEventListener('dragover',event=>{if(event.dataTransfer?.types.includes('Files')){event.preventDefault();viewport.classList.add('drop-target');}});
window.addEventListener('dragleave',event=>{if(!event.relatedTarget)viewport.classList.remove('drop-target');});
window.addEventListener('drop',event=>{event.preventDefault();viewport.classList.remove('drop-target');const file=event.dataTransfer?.files[0];if(file)void openFile(file,file.name);});
element('export-csv').addEventListener('click',()=>void operation(async ticket=>{
  const doc=current,active=session;if(!doc||!active)return;
  const isolated=new BrowserSession();exportSession=isolated;
  try{
    status.textContent='Preparing CSV finding aid…';
    const blob=await active.sourceBlob(doc);check(ticket);
    const source=await isolated.open(blob,doc.name);check(ticket);
    const result=await metadataCsv(source,(parent,file,stream)=>isolated.child(parent,file,stream),()=>check(ticket),
      count=>{check(ticket);status.textContent=`Exporting finding aid · ${count} messages…`;});
    check(ticket);await saveBlob(result.blob,doc.name.replace(/\.pdf$/i,'')+'-finding-aid.csv',()=>check(ticket));check(ticket);
    status.textContent=`Exported finding aid for ${result.rows} messages.`;
  }catch(error){if(ticket===generation)status.textContent=`Metadata export failed: ${error instanceof Error?error.message:String(error)}`;}
  finally{
    isolated.close();if(exportSession===isolated)exportSession=undefined;
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
  zoom.selectedIndex=Math.max(2,Math.min(zoom.options.length-1,zoom.selectedIndex+step));await render(ticket,false);
}));
search.addEventListener('input',()=>{if(!search.value){pages.highlight('');searchStatus.textContent='';}});
search.addEventListener('keydown',event=>{if(event.key==='Escape'){search.value='';pages.highlight('');searchStatus.textContent='';}});
async function showOutline(doc:BrowserDocument,ticket:number):Promise<void>{
  const panel=element('outline-panel'),list=element('outline');list.replaceChildren();panel.hidden=true;
  try{const entries=await doc.run(doc.pdf.getOutline());check(ticket);if(current!==doc||!entries)return;let count=0;
    const add=(items:typeof entries,parent:HTMLElement,depth:number)=>{if(depth>16)return;for(const item of items){if(++count>1000)return;const row=text('li','');
      if(item.dest)row.append(button(item.title,()=>void operation(async active=>{
        const dest=typeof item.dest==='string'?await doc.run(doc.pdf.getDestination(item.dest)):item.dest;check(active);if(!dest)throw new Error('Bookmark destination is unavailable.');
        const ref=dest[0];const index=typeof ref==='number'?ref:await doc.run(doc.pdf.getPageIndex(ref));check(active);if(index<0||index>=doc.pdf.numPages)throw new Error('Bookmark page is outside this PDF.');pageNumber=index+1;await render(active);
      })));else row.append(text('span',item.title+' (no internal page destination)'));
      parent.append(row);if(item.items.length){const nested=text('ul','');row.append(nested);add(item.items,nested,depth+1);}
    }};add(entries,list,0);panel.hidden=!list.children.length;
  }catch(error){if(ticket===generation&&current===doc){list.append(text('li','Bookmarks could not be read.'));panel.hidden=false;}}
}
async function findInCollection():Promise<void>{
  if(!current||!session||!search.value.trim())return;
  await operation(async ticket=>{
    const epoch=++searchEpoch;const checkSearch=()=>{check(ticket);if(epoch!==searchEpoch)throw new Error('Search cancelled.');};
    let root=current!;while(root.parent)root=root.parent;const main=session!;const isolated=new BrowserSession();searchSession=isolated;
    element('cancel-search').hidden=false;const list=element('collection-matches');list.replaceChildren();element('collection-results').hidden=false;
    try{
      const blob=await main.sourceBlob(root);checkSearch();const copy=await isolated.open(blob,root.name);checkSearch();
      const result=await searchCollection(isolated,copy,search.value,checkSearch,count=>{searchStatus.textContent=`Searching collection · ${count} pages…`;});checkSearch();
      for(const match of result.matches){const row=text('li',''),choose=button(`${match.document} · page ${match.page}`,()=>void operation(async active=>{
        let doc=root;for(const step of match.path){const file=doc.inspection.files.find(f=>f.id===step.fileId),stream=file?.streams.find(s=>s.locator.path===step.streamPath);if(!file||!stream)throw new Error('Search destination is no longer available.');await openChild(doc,file,stream,active);doc=current!;}
        await showDocument(doc,active);pageNumber=match.page;await render(active);
      }));row.append(choose,text('p',match.excerpt));list.append(row);}
      element('collection-search-summary').textContent=`${result.matches.length} matching pages. ${result.incomplete.length?'Incomplete: '+result.incomplete.join(' '):'All reachable collection PDFs were searched.'}`;
      searchStatus.textContent=result.incomplete.length?'Collection search incomplete; see results.':'Collection search complete.';
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
      if('page'in result){pageNumber=result.page;await render(ticket);checkSearch();pages.highlight(query,result);searchStatus.textContent=`Match ${result.index+1} of ${result.count}${result.count===2000?'+':''} on page ${result.page}`;}
      else searchStatus.textContent=result.incomplete?'Search limit reached; results are incomplete.':'No matching text in this document.';
    }catch(error){if(epoch!==searchEpoch){searchStatus.textContent='Search cancelled.';return;}throw error;}
    finally{element('cancel-search').hidden=true;}
  });
}
element('search-form').addEventListener('submit',event=>{event.preventDefault();void (element<HTMLSelectElement>('search-scope').value==='collection'?findInCollection():findMatch(1));});
element('find-previous').addEventListener('click',()=>void findMatch(-1));
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
  if(!metadataIndex){
    const main=session,isolated=new BrowserSession();metadataSession=isolated;
    metadataIndex=(async()=>{try{
      const blob=await main.sourceBlob(root);checkIndex();const copy=await isolated.open(blob,root.name);checkIndex();
      return await indexMessages(isolated,copy,checkIndex,count=>{if(filter.value.trim())searchStatus.textContent=`Reading email list · ${count} emails…`;});
    }finally{isolated.close();if(metadataSession===isolated)metadataSession=undefined;}})();
  }
  searchStatus.textContent='Finding emails…';
  try{
    const result=await metadataIndex;checkIndex();if(revision!==filterRevision||query!==filter.value.trim().toLowerCase())return;
    const matches=result.messages.filter(m=>m.searchText.includes(query));
    const create=(item:IndexedMessage)=>{const row=text('li',''),choose=button(item.label,()=>void operation(async active=>{
      let doc=root;
      for(const step of item.path){const file=doc.inspection.files.find(f=>f.id===step.fileId),stream=file?.streams.find(s=>s.locator.path===step.streamPath);
        if(!file||!stream)throw new Error('Email destination is no longer available.');await openChild(doc,file,stream,active);doc=current!;}
      const message=doc.inspection.messages.find(m=>m.id===item.messageId);if(!message)throw new Error('Email metadata is no longer available.');
      filter.value='';filterResults.hidden=true;tree.hidden=false;await showDocument(doc,active,message);
    }));choose.title=item.document;row.append(choose,text('small',item.document));return row;};
    if(matches.length>256)filterList=new VirtualList(filterResults,tree.closest('aside')!,matches,create,60);else for(const item of matches)filterResults.append(create(item));
    searchStatus.textContent=`${matches.length} matching emails. ${result.incomplete.length?'Search incomplete: '+result.incomplete.join(' '):'All reachable email metadata searched.'}`;
  }catch(error){if(ticket===generation&&epoch===metadataEpoch&&revision===filterRevision){metadataIndex=undefined;searchStatus.textContent=`Email search could not finish: ${error instanceof Error?error.message:String(error)}`;}}
}
filter.addEventListener('input',()=>{filterRevision++;clearTimeout(filterTimer);if(!filter.value.trim()){filterList?.close();filterList=undefined;filterResults.replaceChildren();filterResults.hidden=true;tree.hidden=false;searchStatus.textContent='';return;}filterTimer=setTimeout(()=>void filterMessages(),200);});
cancelLinked=setupLinkedOpening(openFile,message=>{status.textContent=message;});
status.textContent='Open or drop a PDF to begin.';controls();

for(const [id,panel,flag]of [['toggle-navigation','navigation-panel','hide-navigation'],['toggle-details','details-panel','hide-details']] as const){
  element(id).addEventListener('click',()=>{const hidden=!element(panel).hidden;element(panel).hidden=hidden;element(id).setAttribute('aria-expanded',String(!hidden));element('workspace').classList.toggle(flag,hidden);if(current&&!busy&&zoom.value.startsWith('fit-'))void operation(ticket=>render(ticket,false));});
}
let resizeTimer:ReturnType<typeof setTimeout>|undefined;
window.addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{if(current&&!busy&&zoom.value.startsWith('fit-'))void operation(ticket=>render(ticket,false));},150);});

element('search-scope').addEventListener('change',()=>{const collection=element<HTMLSelectElement>('search-scope').value==='collection';search.placeholder=collection?'Find across collection PDFs…':'Find in this PDF…';search.setAttribute('aria-label',collection?'Find across collection PDFs':'Find in this PDF');});
