import type {BrowserDocument} from './browser-session.js';
import type {AssociatedFile,EmbeddedStream,MailMessage} from './domain/inspection.js';
import type {IndexedMessage} from './message-index.js';
import {fileLabel} from './navigation.js';
import {treeIcon} from './viewer-icons.js';
import {VirtualList} from './virtual-list.js';
import {text,button,visiblePaneControls} from './viewer-elements.js';
import {isPdf} from './attachment-types.js';

type PdfLink={file:AssociatedFile;stream:EmbeddedStream};
export type PdfPlacement={row?:HTMLLIElement;list?:VirtualList<PdfLink>;index:number};
const pdfKey=(file:AssociatedFile,stream:EmbeddedStream)=>JSON.stringify([file.id,stream.locator.path]);
interface TreeActions {
  busy():boolean;
  singleMessage(doc:BrowserDocument):MailMessage|undefined;
  openDocument(doc:BrowserDocument,message:MailMessage|undefined,node:HTMLElement,fromTop:boolean):void;
  openEmbedded(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,placement:PdfPlacement):void;
}

export function messageLabel(node:HTMLButtonElement,label:string,details?:Record<string,string>):void {
  const content=text('span',''),subject=text('span',label);content.className='message-text';subject.className='message-subject';subject.title=label;content.append(subject);
  const secondary=[details?.From||details?.['Raw-From'],details?.Sent].filter(Boolean).join(' · ');
  if(secondary){const meta=text('span',''),sender=text('span',details?.From||details?.['Raw-From']||''),date=text('span',details?.Sent?.match(/^\d{4}-\d{2}-\d{2}/)?.[0]||details?.Sent||'');meta.className='message-meta';meta.title=secondary;sender.className='message-sender';date.className='message-date';meta.append(sender);if(date.textContent)meta.append(text('span','·'),date);content.append(meta);}
  for(const child of [...node.childNodes])if(child.nodeType===Node.TEXT_NODE)child.remove();node.append(content);
}
/** Tree presentation and alias/list lifetime; navigation uses the viewer's operation owner. */
export class MessageTree {
  private readonly nodes=new Map<BrowserDocument,HTMLLIElement>();
  private readonly pdfPlacements=new WeakMap<BrowserDocument,Map<string,PdfPlacement[]>>();
  private readonly documentPlacements=new WeakMap<BrowserDocument,PdfPlacement>();
  private readonly lazyMessages=new WeakMap<BrowserDocument,{list:VirtualList<MailMessage>;indices:Map<string,number>}>();
  private readonly virtualLists=new Set<{close:()=>void}>();
  private readonly documentLists=new WeakMap<BrowserDocument,Set<{close:()=>void}>>();
  private readonly browseSubjects=new WeakMap<BrowserDocument,Map<string,string>>();
  private readonly browseDisplay=new WeakMap<BrowserDocument,Map<string,Record<string,string>>>();
  constructor(private readonly tree:HTMLElement,private readonly actions:TreeActions) {}

  node(doc:BrowserDocument):HTMLLIElement|undefined {return this.nodes.get(doc);}
  closeLists():void {for(const list of this.virtualLists)list.close();this.virtualLists.clear();}
  clear():void {this.nodes.clear();}
  private rememberList(doc:BrowserDocument,list:{close:()=>void}):void {
    this.virtualLists.add(list);let owned=this.documentLists.get(doc);
    if(!owned){owned=new Set();this.documentLists.set(doc,owned);}owned.add(list);
  }
  messageRow(doc:BrowserDocument,message?:MailMessage):HTMLButtonElement|undefined {
    const root=this.nodes.get(doc),lazy=this.lazyMessages.get(doc),index=message?lazy?.indices.get(message.id):undefined;
    const ensured=index===undefined?undefined:lazy?.list.rowAt(index)?.querySelector<HTMLButtonElement>('button');
    return ensured??(message?root?.querySelector<HTMLButtonElement>(`:scope > ul > li > button[data-message-id="${CSS.escape(message.id)}"]`):root?.querySelector<HTMLButtonElement>(':scope > button[data-document-id]'))??undefined;
  }
  adjacentMessage(target:HTMLElement,direction:number):HTMLElement|undefined {
  for(const [doc,row] of this.nodes){
    const messageIndex=target.dataset.messageId?this.lazyMessages.get(doc)?.indices.get(target.dataset.messageId):undefined;
    const messages=this.lazyMessages.get(doc);
    if(messages&&messageIndex!==undefined&&messageIndex+direction>=0&&messageIndex+direction<doc.inspection.messages.length)
      return messages.list.rowAt(messageIndex+direction)?.querySelector<HTMLButtonElement>('button')??undefined;
    const owner=target.dataset.embeddedFileId?doc:row.querySelector(':scope > button')===target?doc.parent:undefined;
    const fileId=target.dataset.embeddedFileId??doc.parentFileId,stream=target.dataset.embeddedStream??doc.parentStreamPath;
    const lazy=owner&&fileId&&stream?this.pdfPlacements.get(owner)?.get(JSON.stringify([fileId,stream]))?.find(p=>p.row?.contains(target)):undefined;
    if(lazy?.list){const next=lazy.list.rowAt(lazy.index+direction)?.querySelector<HTMLButtonElement>('button');if(next)return next;}
  }
  const rows=visiblePaneControls(this.tree).filter(n=>n.matches('button[data-message-id],button[data-embedded-file-id]'));
  const index=rows.indexOf(target);return rows[index<0?(direction===1?0:rows.length-1):index+direction];
}

  attachChild(parent:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,child:BrowserDocument,placement?:PdfPlacement):HTMLLIElement {
  const previous=this.documentPlacements.get(child);
  placement??=previous??this.pdfPlacements.get(parent)?.get(pdfKey(file,stream))?.[0];
  placement?.list?.rowAt(placement.index);
  let row=this.nodes.get(child);
  if(!row)row=this.build(child);
  if(placement&&previous!==placement){
    // One opened subtree follows the chosen alias; restore a link at its old location.
    // Both locations retain the same file/stream identity and cached document.
    this.documentPlacements.delete(child);
    if(previous)this.replacePdfPlacement(previous,this.pdfTreeRow(parent,{file,stream},previous));
    this.documentPlacements.set(child,placement);this.replacePdfPlacement(placement,row,true);
  }else if(!placement&&!row.isConnected)this.nodes.get(parent)?.querySelector(':scope > ul')?.append(row);
    return row;
  }
  evict(doc:BrowserDocument):void {
    const row=this.nodes.get(doc);this.nodes.delete(doc);
    for(const list of this.documentLists.get(doc)??[]){list.close();this.virtualLists.delete(list);}this.documentLists.delete(doc);
    this.pdfPlacements.delete(doc);this.lazyMessages.delete(doc);this.browseSubjects.delete(doc);this.browseDisplay.delete(doc);
    const parent=doc.parent,file=parent?.inspection.files.find(f=>f.id===doc.parentFileId),stream=file?.streams.find(s=>s.locator.path===doc.parentStreamPath);
    const placement=this.documentPlacements.get(doc);this.documentPlacements.delete(doc);
    if(parent&&file&&stream&&placement)this.replacePdfPlacement(placement,this.pdfTreeRow(parent,{file,stream},placement));else row?.remove();
  }
  subjectUpdater(doc:BrowserDocument):(messages:readonly IndexedMessage[])=>void {
  const subjects=this.browseSubjects.get(doc)??new Map<string,string>();this.browseSubjects.set(doc,subjects);
  const counts=new Map<string,number>();const display=new Map<string,Record<string,string>>();this.browseDisplay.set(doc,display);
  return (messages:readonly IndexedMessage[])=>{
    const changed=new Set<string>();
    for(const message of messages){
      if(message.path.length!==1)continue;
      const step=message.path[0]!,key=JSON.stringify([step.fileId,step.streamPath]),count=(counts.get(key)??0)+1;counts.set(key,count);changed.add(key);
      if(count===1){subjects.set(key,message.label);display.set(key,{From:message.sender??'',Sent:message.sent??''});}else{subjects.delete(key);display.delete(key);}
    }
    // Update mounted rows in place, preserving focus, expansion and scroll position.
    for(const node of this.nodes.get(doc)?.querySelectorAll<HTMLButtonElement>('[data-embedded-file-id]')??[]){
      const key=JSON.stringify([node.dataset.embeddedFileId,node.dataset.embeddedStream]);if(!changed.has(key))continue;
      const label=subjects.get(key);
      node.querySelector('.message-text')?.remove();messageLabel(node,label||node.title,display.get(JSON.stringify([node.dataset.embeddedFileId,node.dataset.embeddedStream])));if(label){node.querySelector('.tree-icon')?.replaceWith(treeIcon('message'));}
    }
  };
  }
  updateFolderCounts(doc:BrowserDocument,messages:readonly IndexedMessage[]):void {
      const collection=doc.inspection.collection.value;if(collection)for(const summary of this.nodes.get(doc)?.querySelectorAll<HTMLElement>('summary[data-folder-id]')??[]){const members=new Set<string>();for(const entry of collection.entries)for(let id of entry.folderIds.value??[]){const seen=new Set<string>();while(id&&!seen.has(id)){seen.add(id);if(id===summary.dataset.folderId)members.add(entry.fileId);id=collection.folders.find(f=>f.id===id)?.parentId??'';}}const count=messages.filter(m=>m.path[0]&&members.has(m.path[0].fileId)).length;const pill=text('span',String(count));pill.className='folder-count';summary.querySelector('.folder-count')?.remove();summary.append(pill);}
  }

markSelection(node:HTMLElement|undefined):void {for(const n of this.tree.querySelectorAll('[aria-current]'))n.removeAttribute('aria-current');node?.setAttribute('aria-current','true');for(let parent=node?.parentElement;parent&&parent!==this.tree;parent=parent.parentElement)if(parent instanceof HTMLDetailsElement)parent.open=true;else if(parent.tagName==='UL'&&parent.hidden){parent.hidden=false;parent.parentElement?.querySelector(':scope > .document-disclosure')?.setAttribute('aria-expanded','true');}}

private replacePdfPlacement(placement:PdfPlacement,row:HTMLLIElement,reveal=false):void {
  if(placement.list){
    if(reveal)placement.list.replaceRow(placement.index,row);else placement.list.invalidateRow(placement.index,row);
  }else placement.row?.replaceWith(row);
  placement.row=row;
}

private pdfTreeRow(doc:BrowserDocument,{file,stream}:PdfLink,placement:PdfPlacement):HTMLLIElement{
    for(const [child,existing] of this.nodes)if(child.parent===doc&&child.parentFileId===file.id&&child.parentStreamPath===stream.locator.path&&this.documentPlacements.get(child)===placement)return existing;
    const subject=this.browseSubjects.get(doc)?.get(pdfKey(file,stream));
    const item=text('li',''),openFile=button(subject||fileLabel(file),()=>this.actions.openEmbedded(doc,file,stream,placement));
    openFile.disabled=this.actions.busy();openFile.dataset.embeddedFileId=file.id;openFile.dataset.embeddedStream=stream.locator.path;openFile.title=fileLabel(file);openFile.prepend(treeIcon(subject?'message':'document'));messageLabel(openFile,subject||fileLabel(file),this.browseDisplay.get(doc)?.get(pdfKey(file,stream)));item.append(openFile);return item;
}

build(doc:BrowserDocument):HTMLLIElement {
  const single=this.actions.singleMessage(doc),label=single?(doc.details.get(single.metadataRecordId)?.Subject||doc.name):doc.name;
  const row=text('li',''),open=button(label,()=>this.actions.openDocument(doc,single,open,true));open.dataset.documentId=doc.inspection.documentId;open.title=doc.name;if(single)open.dataset.messageId=single.id;open.prepend(treeIcon(single?'message':'document'));messageLabel(open,label,single?doc.details.get(single.metadataRecordId):undefined);open.classList.add('root-document');row.append(open);this.nodes.set(doc,row);
  const list=text('ul','');row.append(list);const collection=doc.inspection.collection.value;
  // Collection metadata describes the member emails, not body pages in the cover PDF.
  if(!collection&&!single){
    const makeMessage=(message:MailMessage,index:number)=>{
      const item=text('li',''),label=doc.details.get(message.metadataRecordId)?.Subject??`Message ${index+1}`;
      const choose=button(label,()=>this.actions.openDocument(doc,message,choose,false));choose.disabled=this.actions.busy();choose.dataset.messageId=message.id;choose.prepend(treeIcon('message'));messageLabel(choose,label,doc.details.get(message.metadataRecordId));item.append(choose);return item;
    };
    if(doc.inspection.messages.length>256){
      const virtual=new VirtualList(list,this.tree.closest('aside')!,doc.inspection.messages,makeMessage,40);
      this.lazyMessages.set(doc,{list:virtual,indices:new Map(doc.inspection.messages.map((m,i)=>[m.id,i]))});this.rememberList(doc,virtual);
    }else doc.inspection.messages.forEach((message,index)=>list.append(makeMessage(message,index)));
  }

  const folders=new Map<string,HTMLUListElement>(),folderRows:HTMLDetailsElement[]=[];
  if(collection){
    const orderedFolders=[...collection.folders].sort((a,b)=>(a.name.value??'').localeCompare(b.name.value??'',undefined,{numeric:true,sensitivity:'base'}));
    for(const folder of orderedFolders)folders.set(folder.id,text('ul',''));
    for(const folder of orderedFolders){
      const item=text('li',''),details=text('details',''),label=text('summary',''),name=text('span',folder.name.value??'Folder');name.className='folder-name';label.append(name);label.dataset.folderId=folder.id;const chevron=treeIcon('right');chevron.classList.add('disclosure-icon');label.prepend(chevron,treeIcon('folder'));details.open=false;details.append(label,folders.get(folder.id)!);folderRows.push(details);item.append(details);
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
    const targets=entries.every(e=>e.folderIds.state==='present')&&ids.size
      ?new Set([...ids].map(id=>folders.get(id)??attachments)):new Set([attachments]);
    for(const target of targets){const group=groups.get(target)??[];group.push({file,stream});groups.set(target,group);}
  }
  const positions=new Map<string,PdfPlacement[]>();this.pdfPlacements.set(doc,positions);
  for(const [target,items] of groups){
    const placements=items.map((item,index)=>{const placement:PdfPlacement={index},key=pdfKey(item.file,item.stream),aliases=positions.get(key)??[];aliases.push(placement);positions.set(key,aliases);return placement;});
    const makePdf=(item:PdfLink,index:number)=>{const placement=placements[index]!,row=this.pdfTreeRow(doc,item,placement);placement.row=row;return row;};
    if(items.length>256){
      let memberList=target;
      if(target.children.length){const item=text('li',''),nested=text('ul','');item.append(nested);target.append(item);memberList=nested;}
      const virtual=new VirtualList(memberList,this.tree.closest('aside')!,items,makePdf,40);this.rememberList(doc,virtual);
      for(const placement of placements)placement.list=virtual;
    }else items.forEach((item,index)=>target.append(makePdf(item,index)));
  }
  // Empty folders have no disclosure control; populated folders start collapsed.
  for(const details of folderRows)if(!details.querySelector(':scope > ul')!.children.length){const label=text('div','');label.className='folder-label';label.append(...details.querySelector('summary')!.childNodes);label.querySelector('.disclosure-icon')?.remove();details.replaceWith(label);}
  if([...row.children].some(child=>child.tagName==='UL'&&child.children.length)){
    const toggle=button('',()=>{const expanded=toggle.getAttribute('aria-expanded')!=='true';toggle.setAttribute('aria-expanded',String(expanded));for(const child of row.children)if(child.tagName==='UL')(child as HTMLElement).hidden=!expanded;});toggle.className='document-disclosure icon-button';toggle.setAttribute('aria-label',`Expand or collapse ${doc.name}`);toggle.title=`Expand or collapse ${doc.name}`;toggle.setAttribute('aria-expanded','true');toggle.append(treeIcon('right'));open.classList.add('has-disclosure');row.classList.add('document-row');row.append(toggle);
  }
  return row;
}
}
