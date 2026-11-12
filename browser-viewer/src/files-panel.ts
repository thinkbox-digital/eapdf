import {filesInMessageOrder} from './files-message-order.js';
import type {BrowserDocument} from './browser-session.js';
import type {AssociatedFile,EmbeddedStream,MailMessage} from './domain/inspection.js';
import type {IndexedMessage} from './message-index.js';
import {fileLabel} from './navigation.js';
import {treeIcon} from './viewer-icons.js';
import {VirtualList} from './virtual-list.js';
import {text,button} from './viewer-elements.js';
import {isPdf,canView,sizeLabel} from './attachment-types.js';

interface FilePanelElements { files:HTMLElement; count:HTMLElement; title:HTMLElement; details:HTMLElement; }
interface FilePanelActions {
  busy():boolean;
  conversationsEnabled():boolean;
  index(doc:BrowserDocument):readonly IndexedMessage[]|undefined;
  singleMessage(doc:BrowserDocument):MailMessage|undefined;
  download(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream):void;
  view(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream):void;
}

function fileCategory(file:AssociatedFile):string {
  const premis=/(?:^|[_. -])premis(?:[_. -]|$)/i.test(fileLabel(file))&&/\.xml$/i.test(fileLabel(file))
    ||file.streams.some(stream=>/premis/i.test(stream.mimeType.value??''));
  if(premis||file.afRelationship.value==='Supplement')return 'Technical and supporting files';
  if(file.afRelationship.value==='Source')return 'Email sources';
  return file.owners.value?.length?'Email attachments':'Archive files';
}
const fileCategoryOrder=['Email sources','Email attachments','Archive files','Technical and supporting files'];

/** Shorten known generator descriptions without changing stored provenance or other descriptions. */
export function sourceFileDescription(description:string,isSource:boolean):string {
  const legacyAttachment='Attached PST email reconstructed as RFC 822 with headers, body, and nested attachments';
  if(!isSource&&(description===legacyAttachment||description.startsWith(legacyAttachment+';')))
    return 'Attached email exported from PST'+description.slice(legacyAttachment.length);
  if(!isSource)return description;
  if(description.startsWith('Exported RFC 822 conversion source. MBOX preprocessing:'))return 'Email source extracted from MBOX';
  if(description.startsWith('Exported RFC 822 conversion source. PST preprocessing:'))return 'Email source exported from PST';
  if(description==='Original RFC 822 source'||description==='Original international email source')return 'Original EML source';
  return description;
}

/** Attachment presentation only; navigation and cancellation remain in the viewer. */
export class FilesPanel {
  private list:VirtualList<AssociatedFile>|undefined;
  private descriptionFrame=0;
  private readonly descriptionResize=new ResizeObserver(()=>this.scheduleDescriptions());
  private scheduleDescriptions():void {
    if(this.descriptionFrame)return;
    this.descriptionFrame=requestAnimationFrame(()=>{
      this.descriptionFrame=0;
      for(const block of this.elements.files.querySelectorAll<HTMLElement>('.file-description-block')){
        const preview=block.querySelector<HTMLElement>('.file-description')!,disclosure=block.querySelector<HTMLDetailsElement>('details')!;
        if(disclosure.open||!preview.clientWidth)continue;
        const needed=block.dataset.shortened==='true'||preview.scrollHeight>preview.clientHeight+1;
        if(disclosure.hidden===needed)disclosure.hidden=!needed;
      }
    });
  }
  private readonly descriptions=new WeakMap<BrowserDocument,Map<string,string>>();
  constructor(private readonly elements:FilePanelElements,private readonly actions:FilePanelActions) {}
  hasDescriptions(doc:BrowserDocument):boolean {return this.descriptions.has(doc);}
  setDescriptions(doc:BrowserDocument,values:Map<string,string>):void {this.descriptions.set(doc,values);}
  close():void {this.list?.close();this.list=undefined;this.descriptionResize.disconnect();cancelAnimationFrame(this.descriptionFrame);this.descriptionFrame=0;}
  show(doc:BrowserDocument,message?:MailMessage):void {
  const {files,count,title,details}=this.elements;
  this.descriptionResize.observe(files);
  this.list?.close();this.list=undefined;files.replaceChildren();const selected=filesInMessageOrder(doc.inspection,doc.inspection.files.filter(f=>!message||f.owners.value?.includes(message.id)),file=>fileCategoryOrder.indexOf(fileCategory(file)),this.actions.index(doc),doc.details);count.textContent=String(selected.length);
  title.textContent=message||this.actions.singleMessage(doc)?'Files in this email':'Files in this PDF';
  const detailList=details;detailList.replaceChildren();
  const selectedMessage=message??this.actions.singleMessage(doc),metadata=selectedMessage?doc.details.get(selectedMessage.metadataRecordId):undefined;
  const collection=doc.parent?.inspection.collection.value;const folderNames=new Set<string>();
  if(collection)for(const entry of collection.entries.filter(e=>e.fileId===doc.parentFileId))for(let id of entry.folderIds.value??[]){const names:string[]=[],seen=new Set<string>();while(id&&!seen.has(id)){seen.add(id);const folder=collection.folders.find(f=>f.id===id);if(!folder)break;if(folder.name.value)names.unshift(folder.name.value);id=folder.parentId??'';}if(names.length)folderNames.add(names.join(' / '));}
  for(const [key,value]of [['Folder',[...folderNames].join('; ')||metadata?.Folder||metadata?.['Folder paths']]])if(value){detailList.append(text('dt',key!),text('dd',value));}
  const disclosure=detailList.closest('details');if(disclosure)disclosure.hidden=!detailList.childElementCount;
  if(doc.inspection.coverage.associatedFiles!=='present'){const note=text('p','Some embedded files could not be listed.');note.className='file-note';files.append(note);}
  if(message&&doc.inspection.diagnostics.some(d=>d.code==='FILE_EMAIL_ASSOCIATION_MISSING')){const note=text('p','Some files could not be linked to an email.');note.className='file-note';files.append(note);}
  if(!selected.length){if(doc.inspection.coverage.associatedFiles==='present'){const note=text('p',message&&doc.inspection.files.length?'No files are associated with this email in the PDF.':'No embedded files in this selection.');note.className='muted';files.append(note);}}
  const list=text('ul','');files.append(list);
  const createFile=(file:AssociatedFile)=>{
    const row=text('li','');row.className='file-card';row.dataset.associatedFileId=file.id;const label=text('strong',fileLabel(file));label.className='file-name';label.title=fileLabel(file);const icon=treeIcon(/\.eml$/i.test(fileLabel(file))?'message':/\.(xml|json|html?)$/i.test(fileLabel(file))?'code':file.afRelationship.value!=='Supplement'&&file.afRelationship.value!=='Source'&&file.owners.value?.length?'attachment':'document');icon.classList.add('file-icon');const heading=text('div','');heading.className='file-heading';heading.append(label);row.append(icon,heading);
    const storedDescription=this.descriptions.get(doc)?.get(file.locator.path);
    if(storedDescription){
      const block=text('div','');block.className='file-description-block';
      const shortened=sourceFileDescription(storedDescription,file.afRelationship.value==='Source');block.dataset.shortened=String(shortened!==storedDescription);
      const disclosure=text('details','');disclosure.className='file-description-disclosure';disclosure.hidden=shortened===storedDescription;
      const summary=text('summary',''),preview=text('p',shortened);
      preview.className='file-description';
      const more=text('span','Show more'),less=text('span','Show less');more.className='description-more';less.className='description-less';
      summary.append(more,less);
      const full=text('p',storedDescription);full.className='file-description-full';
      disclosure.append(summary,full);disclosure.addEventListener('toggle',()=>this.scheduleDescriptions());block.append(preview,disclosure);row.append(block);
    }
    const category=fileCategory(file);
    if(selected.length>256){const role=text('p',category);role.className='file-role';row.append(role);}
    const owners=doc.inspection.messages.filter(m=>file.owners.value?.includes(m.id));
    if(category==='Email attachments'&&owners.length){
      const links=text('div','');links.className='file-message-links';row.append(links);
      for(const owner of owners){
        const subject=doc.details.get(owner.metadataRecordId)?.Subject;
        const item=text('div','');item.className='file-message-owner';
        if(subject){const description=text('span',subject);description.className='file-message-subject';description.title=subject;item.append(description);}
        item.dataset.ownerMessageId=owner.id;links.append(item);
      }
    }else{
      const description=owners[0]?doc.details.get(owners[0].metadataRecordId)?.Subject:undefined;
      if(description&&description!==fileLabel(file)){const detail=text('p',description);detail.className='file-description';detail.title=description;row.append(detail);}
    }
    for(const stream of file.streams){
      const size=stream.decodedSize.value??stream.declaredSize.value;
      const facts=text('span',sizeLabel(size)+(file.streams.length>1?' · '+stream.efKeys.join('/'):'') );facts.className='file-size';heading.append(facts);
      const actions=text('div','');actions.className='file-actions';if(file.streams.length>1){row.classList.add('multiple-streams');actions.setAttribute('role','group');actions.setAttribute('aria-label',stream.efKeys.join('/'));const streamLabel=text('span',stream.efKeys.join('/'));streamLabel.className='stream-label';actions.append(streamLabel);}row.append(actions);
      const save=button(file.afRelationship.value==='Source'&&/\.eml$/i.test(fileLabel(file))?'Download email (.eml)':'Download',()=>this.actions.download(doc,file,stream));save.replaceChildren(treeIcon('download'));save.classList.add('icon-button');save.setAttribute('aria-label',`Download ${fileLabel(file)}`);save.title=`Download ${fileLabel(file)}`;save.dataset.fileId=file.id;save.dataset.stream=stream.locator.path;actions.append(save);
      if(size===0){save.title+=' (empty file)';}
      else if(canView(file,stream)){
        const view=button(isPdf(file,stream)?'Open PDF':'View',()=>this.actions.view(doc,file,stream));view.className='view-action';view.replaceChildren(treeIcon('view'));view.classList.add('icon-button');view.setAttribute('aria-label',`View ${fileLabel(file)}`);view.title=`${isPdf(file,stream)?'Open':'View'} ${fileLabel(file)}`;view.dataset.viewFileId=file.id;view.dataset.viewStream=stream.locator.path;actions.prepend(view);
      }
    }
    if(!file.streams.length){const note=text('p','Embedded content is missing or cannot be read.');note.className='file-note';row.append(note);}
    for(const b of row.querySelectorAll<HTMLButtonElement>('button'))b.disabled=this.actions.busy()||b.dataset.unavailable==='true';
    this.scheduleDescriptions();
    return row;
  };
  const declaredProfile=doc.inspection.identification.declaredProfile;
  const isolated=declaredProfile.state==='present'&&['si','mi','ci'].includes(declaredProfile.value??'');
  const emptyNotice=()=>{const note=text('p','This profile of EA-PDF does not embed source messages.');note.className='file-note empty-file-notice';const warning=treeIcon('warning');warning.removeAttribute('aria-hidden');warning.setAttribute('role','img');warning.setAttribute('aria-label','Warning');note.prepend(warning);return note;};
  if(selected.length>256){if(isolated)list.before(emptyNotice());this.list=new VirtualList(list,files.closest('aside')!,selected,createFile,220);}
  else {
    const groups=new Map<string,AssociatedFile[]>();
    for(const file of selected){const category=fileCategory(file),name=category==='Archive files'?'Other files':category;const group=groups.get(name)??[];group.push(file);groups.set(name,group);}
    if(isolated&&!groups.size)list.before(emptyNotice());
    const noticeGroup=groups.has('Email attachments')?'Email attachments':groups.keys().next().value;
    for(const [name,group]of groups){const section=text('li',''),disclosure=text('details',''),heading=text('summary',''),title=text('span',name),count=text('span',String(group.length)),items=text('ul','');section.className='file-category';heading.className='file-category-heading';count.className='badge';const chevron=treeIcon('right');chevron.classList.add('disclosure-icon');heading.append(chevron,title,count);const defaultOpen=name==='Email sources'||name==='Email attachments'||name==='Technical and supporting files'||name==='Other files';disclosure.dataset.defaultOpen=String(defaultOpen);disclosure.open=!this.actions.conversationsEnabled()&&defaultOpen;disclosure.append(heading);if(isolated&&name===noticeGroup)disclosure.append(emptyNotice());disclosure.append(items);section.append(disclosure);list.append(section);for(const file of group)items.append(createFile(file));}
  }
}
}
