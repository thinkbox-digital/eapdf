import {BrowserSession,type BrowserDocument} from './browser-session.js';
import {ScrollingPages} from './scrolling-pages.js';
import {fileLabel,messagePages,messagePageOwners,folderFiles} from './navigation.js';
import {resolveEmbeddedLink} from './embedded-navigation.js';
import {AssetPanel,documentPath} from './asset-panel.js';
import {setupPaneResizers} from './pane-resizer.js';
import {saveBlob} from './downloads.js';
import {isPdf,canView} from './attachment-types.js';
import {AttachmentViewer} from './attachment-viewer.js';
import {treeIcon} from './viewer-icons.js';
import {messageLabel} from './message-label.js';
import type {AssociatedFile,EmbeddedStream} from './domain/inspection.js';

const element=<T extends HTMLElement>(id:string)=>document.getElementById(id) as T;
const input=element<HTMLInputElement>('file'),pageInput=element<HTMLInputElement>('page'),zoom=element<HTMLSelectElement>('zoom');
const back=element<HTMLButtonElement>('back'),previous=element<HTMLButtonElement>('previous'),next=element<HTMLButtonElement>('next'),close=element<HTMLButtonElement>('close');
const viewport=element<HTMLElement>('viewport'),surface=element<HTMLElement>('page-surface'),tree=element<HTMLElement>('tree'),workspace=element('workspace');
const assetList=element('assets'),allFiles=element<HTMLButtonElement>('all-files');
let session=new BrowserSession(),current:BrowserDocument|undefined,busy=false,generation=0;
const status=(text:string)=>{element('status').textContent=text;};
const attachmentViewer=new AttachmentViewer();
const pages=new ScrollingPages(viewport,surface,page=>{if(current)current.page=page;syncMessageToPage();controls();},status,()=>{},action=>{
  void perform(async check=>{
    const target=await resolveEmbeddedLink(session,current!,action,check);check();
    if(target.file&&target.stream){await activate(target.doc);check();await followAsset(target.doc,target.file,target.stream,check);}
    else await activate(target.doc,target.page);
    check();await session.prune(current!);
  });
});
const assets=new AssetPanel(assetList,element('assets-title'),{
  download:(doc,file,stream)=>void perform(check=>download(doc,file,stream,check)),
  open:(doc,file,stream)=>void perform(check=>followAsset(doc,file,stream,check)),
  page:page=>void perform(async()=>{current!.page=page;await render();status(notice(current!));}),
});
pages.embeddedLinkLabel='Open linked attachment';
for(const [id,kind,label]of [['back','up','Return to parent PDF'],['previous','left','Previous page'],['next','right','Next page'],['close','close','Close PDF']] as const){const control=element(id);control.replaceChildren(treeIcon(kind));control.classList.add('icon-button');control.title=label;control.setAttribute('aria-label',label);}
document.querySelector('.open')!.prepend(treeIcon('folder'));
setupPaneResizers(workspace,20);
function pane(name:string,show:boolean):void {
  workspace.classList.toggle('hide-'+name,!show);
  element('toggle-'+name).setAttribute('aria-expanded',String(show));
}
for(const name of ['navigation','details'])element('toggle-'+name).addEventListener('click',()=>pane(name,workspace.classList.contains('hide-'+name)));
function showAssets():void {
  if(!current)return;assets.show(current);allFiles.hidden=!current.selectedMessage;
}
allFiles.addEventListener('click',()=>{if(current){current.selectedMessage=undefined;showAssets();}});
function controls():void {
  const doc=current;
  for(const control of [pageInput,zoom,previous,next,back])control.disabled=busy||!doc;
  back.disabled=busy||!doc?.parent;previous.disabled=busy||!doc||doc.page<=1;next.disabled=busy||!doc||doc.page>=doc.pdf.numPages;
  back.hidden=!doc?.parent;
  close.disabled=!doc&&!busy;pageInput.value=String(doc?.page??1);element('pages').textContent=`of ${doc?.pdf.numPages??0}`;
  tree.inert=assetList.inert=busy;allFiles.disabled=busy;viewport.setAttribute('aria-busy',String(busy));
  for(const name of ['navigation','details'])element<HTMLButtonElement>('toggle-'+name).disabled=!doc;
}
async function perform(operation:(check:()=>void)=>Promise<void>):Promise<void>{
  if(busy)return;
  const token=generation;busy=true;controls();status('Opening…');
  const check=()=>{if(token!==generation)throw Error('Operation cancelled.');};
  try{await operation(check);check();}
  catch(error){if(token===generation){status(error instanceof Error?error.message:String(error));if(current)await session.prune(current);}}
  finally{if(token===generation){busy=false;controls();}}
}
async function render():Promise<void>{
  const doc=current;if(!doc)return;
  const token=generation,pdfPage=await doc.run(doc.pdf.getPage(doc.page));
  const view=pdfPage.getViewport({scale:1});pdfPage.cleanup();
  if(token!==generation)throw Error('Operation cancelled.');
  const width=(viewport.clientWidth-64)/view.width,height=(viewport.clientHeight-48)/view.height;
  const scale=Math.max(.1,Math.min(3,zoom.value==='fit-width'?width:zoom.value==='fit-page'?Math.min(width,height):Number(zoom.value)));
  await pages.show(doc,scale,doc.page,'');
  if(token!==generation)throw Error('Operation cancelled.');
  syncMessageToPage();
}
const pageOwners=new WeakMap<BrowserDocument,ReturnType<typeof messagePageOwners>>();
function syncMessageToPage():void {
  const doc=current;if(!doc)return;
  let owners=pageOwners.get(doc);if(!owners){owners=messagePageOwners(doc.inspection);pageOwners.set(doc,owners);}
  const id=owners.get(doc.page)?.id;if(doc.selectedMessage!==id){doc.selectedMessage=id;showAssets();}
  const buttons=[...tree.querySelectorAll<HTMLButtonElement>('button')];
  const selected=buttons.find(button=>button.dataset.documentId===doc.inspection.documentId&&button.dataset.messageId===id)??(doc.parentFileId?buttons.find(button=>button.dataset.fileId===doc.parentFileId):undefined);
  if(selected?.hasAttribute('aria-current'))return;
  for(const button of buttons)button.removeAttribute('aria-current');if(!selected)return;selected.setAttribute('aria-current','true');
  const panel=element('navigation-panel'),bounds=panel.getBoundingClientRect(),box=selected.getBoundingClientRect();
  if(box.top<bounds.top)panel.scrollTop-=bounds.top-box.top;else if(box.bottom>bounds.bottom)panel.scrollTop+=box.bottom-bounds.bottom;
}
function notice(doc:BrowserDocument):string {
  const coverage=doc.inspection.coverage;
  const incomplete=['metadata','documentParts','associatedFiles'].some(key=>!['present','missing'].includes(String(coverage[key as keyof typeof coverage])));
  const diagnostics=doc.inspection.diagnostics;
  return doc.name+(incomplete?' — incomplete inventory; some messages or files may be unavailable.':diagnostics.length?' — document inspection reported warnings.':'');
}
async function activate(doc:BrowserDocument,page=doc.page):Promise<void>{
  const token=generation,changed=current!==doc;
  current=doc;doc.page=page;element('empty-state').hidden=true;element('filename').textContent=doc.name;
  element('breadcrumbs').textContent=documentPath(doc);element('breadcrumbs').hidden=false;
  navigation(doc);showAssets();if(changed)element('details-panel').scrollTop=0;controls();await render();if(token===generation)status(notice(doc));
}
function row(label:string,action:(check:()=>void)=>Promise<void>,container:HTMLElement,kind:'message'|'document'='message',details?:Record<string,string>):HTMLButtonElement {
  const button=document.createElement('button');button.textContent=label;button.type='button';
  button.append(treeIcon(kind));messageLabel(button,label,details);
  button.addEventListener('click',()=>void perform(async check=>{
    await action(check);check();if(button.isConnected){tree.querySelector('[aria-current]')?.removeAttribute('aria-current');button.setAttribute('aria-current','true');}
  }));container.append(button);return button;
}
async function download(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,check:()=>void):Promise<void>{
  const {bytes,warnings}=await session.bytes(doc,file,stream,check,false);check();
  await saveBlob(new Blob([new Uint8Array(bytes)],{type:'application/octet-stream'}),fileLabel(file),check);check();
  status('Download requested: '+fileLabel(file)+(warnings.length?' Warning: '+warnings.join(' '):''));
}
async function followAsset(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,check:()=>void):Promise<void>{
  doc.selectedMessage=undefined;showAssets();pane('details',true);assets.select(file.id);
  if(isPdf(file,stream)){const child=await session.child(doc,file,stream);check();await activate(child);check();await session.prune(child);}
  else if(canView(file,stream)){const {bytes}=await session.bytes(doc,file,stream,check,false);check();await attachmentViewer.open(bytes,fileLabel(file),stream.mimeType.value??'');check();status(notice(doc));}
  else await download(doc,file,stream,check);
}
pages.onAttachment=(page,annotation)=>void perform(async check=>{
  const doc=current!,ref=typeof annotation==='string'?/^(\d+)R(\d+)?$/.exec(annotation):null;
  const target=ref?{target:`object:${ref[1]}:${ref[2]??0}`}:await doc.run(doc.pdf.getDocumentAssets({embeddedTarget:{page:page-1,annotation}}));check();
  const files=doc.inspection.files.filter(file=>file.origins.some(origin=>origin.kind==='annotation'&&origin.locator.path===target.target));
  if(files.length!==1)throw Error('Attachment link is missing or ambiguous.');
  const file=files[0]!,stream=file.streams.find(stream=>stream.efKeys.includes('/UF'))??file.streams[0];
  if(!stream)throw Error('Attachment link has no readable content.');
  await followAsset(doc,file,stream,check);
});
function messageRows(doc:BrowserDocument,container:HTMLElement):void {
  for(const [index,message] of doc.inspection.messages.entries()){
    const record=doc.details.get(message.metadataRecordId),label=record?.Subject||record?.subject||`Message ${index+1}`;
    const body=messagePages(doc.inspection,message);
    if(!body.length&&doc.inspection.collection.value)continue;
    const button=row(label,async()=>{doc.selectedMessage=message.id;showAssets();if(body.length){doc.page=body[0]!;await render();status(notice(doc));}else status('Email metadata is available, but its body page is not identified in this PDF.');},container,'message',record);
    button.dataset.documentId=doc.inspection.documentId;button.dataset.messageId=message.id;
  }
}
function navigation(active:BrowserDocument):void {
  const doc=active.parent?.inspection.collection.value?active.parent:active;tree.replaceChildren();
  if(doc.inspection.collection.value){const note=document.createElement('p');note.className='collection-note';note.textContent='Open an embedded PDF below to read its message and files.';tree.append(note);}
  messageRows(doc,tree);
  const collection=doc.inspection.collection.value,folders=new Map<string,HTMLElement>();
  // Resolve parent folders iteratively; malformed cycles fall back to the root.
  const pending=[...(collection?.folders??[])];
  for(let pass=0;pending.length&&pass<=32;pass++)for(let index=pending.length-1;index>=0;index--){
    const folder=pending[index]!;if(folder.parentId&&!folders.has(folder.parentId))continue;
    const details=document.createElement('details'),summary=document.createElement('summary'),contents=document.createElement('div');
    summary.textContent=folder.name.value??'Folder';summary.prepend(treeIcon('folder'));details.open=true;details.append(summary,contents);
    (folders.get(folder.parentId??'')??tree).append(details);folders.set(folder.id,contents);pending.splice(index,1);
  }
  for(const file of doc.inspection.files){
    const folderIds=collection?.entries.find(entry=>entry.fileId===file.id)?.folderIds.value;
    const container=folderIds?.length===1?folders.get(folderIds[0]!)??tree:tree;
    const stream=file.streams.find(stream=>stream.efKeys.includes('/UF'))??file.streams[0];
    if(stream&&isPdf(file,stream)){
      const label=doc.childLabels?.get(file.id),button=row(label?.Subject??fileLabel(file),check=>followAsset(doc,file,stream,check),container,'document',label);button.dataset.fileId=file.id;
      if(active.parentFileId===file.id&&active.inspection.messages.length>1)messageRows(active,container);
    }
  }
}
let lastWheel=0,wheelConsumed=false;
viewport.addEventListener('wheel',event=>{
  if(event.ctrlKey||event.metaKey||Math.abs(event.deltaY)<=Math.abs(event.deltaX))return;
  const now=performance.now();if(now-lastWheel>200)wheelConsumed=false;lastWheel=now;
  const doc=current,parent=doc?.parent,direction=event.deltaY>0?1:-1;
  if(wheelConsumed||busy||!doc||!parent||doc.inspection.collection.value||!doc.inspection.messages.length)return;
  if(direction===1?viewport.scrollTop+viewport.clientHeight<viewport.scrollHeight-2:viewport.scrollTop>2)return;
  const siblings=folderFiles(parent.inspection,doc.parentFileId??''),index=siblings.findIndex(file=>file.id===doc.parentFileId);if(index<0||siblings[index]!.streams.filter(stream=>isPdf(siblings[index]!,stream)).length!==1)return;
  wheelConsumed=true;event.preventDefault();void perform(async check=>{
    for(let next=index+direction;next>=0&&next<siblings.length;next+=direction){
      const file=siblings[next]!,streams=file.streams.filter(stream=>isPdf(file,stream));if(!streams.length)continue;
      if(streams.length!==1)throw Error('Ambiguous embedded PDF streams.');
      const child=await session.child(parent,file,streams[0]!);check();
      if(child.inspection.collection.value){await session.release(child);status('Stopped at a nested collection. Select it to continue.');return;}
      if(!['present','missing'].includes(child.inspection.coverage.metadata)){await session.release(child);throw Error('Unreadable message metadata.');}
      if(!child.inspection.messages.length){await session.release(child);continue;}
      await activate(child,direction===1?1:child.pdf.numPages);check();await session.prune(child);return;
    }
    status(direction===1?'End of messages in this folder.':'Beginning of messages in this folder.');
  });
},{passive:false});
function reset():void {
  attachmentViewer.close();
  ++generation;pages.clear();session.close();session=new BrowserSession();current=undefined;busy=false;
  tree.replaceChildren();assetList.replaceChildren();element('assets-title').textContent='Files in this PDF';allFiles.hidden=true;
  pane('navigation',false);pane('details',false);element('breadcrumbs').hidden=true;
  element('empty-state').hidden=false;input.value='';controls();status('Open or drop a PDF.');
}
function open(blob:File):void {
  reset();void perform(async check=>{const doc=await session.open(blob,blob.name);check();pane('navigation',true);pane('details',true);await activate(doc);});
}
input.addEventListener('change',()=>{const file=input.files?.[0];if(file)open(file);});
close.addEventListener('click',reset);
back.addEventListener('click',()=>void perform(async check=>{
  const child=current!,parent=child.parent!;pages.clear();await session.release(child);check();await activate(parent);
}));
for(const [button,step] of [[previous,-1],[next,1]] as const)button.addEventListener('click',()=>void perform(async()=>{
  current!.page+=step;await render();status(notice(current!));
}));
pageInput.addEventListener('keydown',event=>{if(event.key!=='Enter'||!current)return;
  const value=Number(pageInput.value);
  if(!Number.isInteger(value)||value<1||value>current.pdf.numPages){pageInput.value=String(current.page);status('Enter a valid page number.');return;}
  void perform(async()=>{current!.page=value;await render();status(notice(current!));});
});
zoom.addEventListener('change',()=>void perform(async()=>{await render();status(notice(current!));}));
let resizeTimer:ReturnType<typeof setTimeout>;
new ResizeObserver(()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{if(current&&!busy&&zoom.value.startsWith('fit-'))void perform(async()=>{await render();status(notice(current!));});},150);}).observe(viewport);
document.addEventListener('dragover',event=>{event.preventDefault();viewport.classList.add('drop-target');});
document.addEventListener('dragleave',()=>viewport.classList.remove('drop-target'));
document.addEventListener('drop',event=>{event.preventDefault();viewport.classList.remove('drop-target');const file=event.dataTransfer?.files[0];if(file)open(file);});
window.addEventListener('pagehide',()=>session.close());
input.disabled=false;reset();
