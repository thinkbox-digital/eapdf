import type {BrowserDocument} from './browser-session.js';
import type {AssociatedFile,EmbeddedStream} from './domain/inspection.js';
import {fileLabel} from './navigation.js';
import {isPdf,canView,sizeLabel} from './attachment-types.js';
import {treeIcon} from './viewer-icons.js';
import {fileCategory} from './file-category.js';

function folders(doc:BrowserDocument,fileId:string):string[] {
  const collection=doc.inspection.collection.value;
  const ids=new Set(collection?.entries.filter(entry=>entry.fileId===fileId).flatMap(entry=>entry.folderIds.value??[])??[]);
  return [...ids].map(id=>{
    const names:string[]=[],seen=new Set<string>();
    while(id){
      if(seen.has(id))return 'Folder unavailable';seen.add(id);
      const folder=collection?.folders.find(folder=>folder.id===id);
      if(!folder)return 'Folder unavailable';
      names.unshift(folder.name.value??'Unnamed folder');id=folder.parentId??'';
    }
    return names.join(' / ');
  }).filter(Boolean);
}
export function documentPath(doc:BrowserDocument):string {
  if(!doc.parent)return doc.name;
  const folder=doc.parentFileId?folders(doc.parent,doc.parentFileId).join('; '):'';
  return [documentPath(doc.parent),folder,doc.name].filter(Boolean).join(' / ');
}
export function assetLocation(doc:BrowserDocument,file:AssociatedFile){
  const pages=[...new Set(file.origins.flatMap(origin=>origin.pageIndex!==null&&Number.isInteger(origin.pageIndex)&&origin.pageIndex>=0&&origin.pageIndex<doc.pdf.numPages?[origin.pageIndex+1]:[]))].sort((a,b)=>a-b);
  const owners=doc.inspection.messages.filter(message=>file.owners.value?.includes(message.id));
  return {pages,owners};
}
const node=<K extends keyof HTMLElementTagNameMap>(tag:K,value:string)=>{const element=document.createElement(tag);element.textContent=value;return element;};
type Actions={download:(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream)=>void;open:(doc:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream)=>void;page:(page:number)=>void};
export class AssetPanel {
  constructor(private container:HTMLElement,private title:HTMLElement,private actions:Actions){}
  select(id:string):void {
    for(const row of this.container.querySelectorAll<HTMLElement>('[data-file-id]')){
      if(row.dataset.fileId===id){row.setAttribute('aria-current','true');row.scrollIntoView({block:'nearest'});}
      else row.removeAttribute('aria-current');
    }
  }
  show(doc:BrowserDocument):void {
    this.container.replaceChildren();
    const files=doc.inspection.files.filter(file=>!doc.selectedMessage||file.owners.value?.includes(doc.selectedMessage));
    this.title.textContent=`${doc.selectedMessage?'Files in this email':'Files in this PDF'} (${files.length})`;
    if(doc.inspection.coverage.associatedFiles!=='present')this.container.append(node('p','Some embedded files could not be listed.'));
    if(!files.length)this.container.append(node('p',doc.selectedMessage?'No assets have a known association with this message. Use All files to see the document inventory.':'No embedded assets.'));
    const category=(file:AssociatedFile)=>doc.inspection.collection.value?.entries.some(entry=>entry.fileId===file.id)&&file.streams.some(stream=>isPdf(file,stream))?(['c','ci'].includes(doc.inspection.identification.declaredProfile.value??'')?'Email PDFs':'Collection PDFs'):fileCategory(file).replace('Archive files','Other files');
    const order=['Email PDFs','Collection PDFs','Email sources','Email attachments','Other files','Technical and supporting files'],groups=new Map<string,HTMLElement>();
    files.sort((a,b)=>order.indexOf(category(a))-order.indexOf(category(b)));
    for(const file of files){
      const label=category(file);let group=groups.get(label);
      if(!group){const disclosure=node('details',''),summary=node('summary',label+' ('+files.filter(file=>category(file)===label).length+')'),chevron=treeIcon('right');chevron.classList.add('disclosure-icon');summary.prepend(chevron);disclosure.className='file-category';disclosure.open=true;disclosure.append(summary);group=node('div','');disclosure.append(group);groups.set(label,group);this.container.append(disclosure);}
      const row=node('section','');row.className='asset file-card';row.dataset.fileId=file.id;
      const heading=node('div','');heading.className='file-heading';const name=node('h3',fileLabel(file));name.className='file-name';name.title=fileLabel(file);heading.append(name);row.append(heading);
      const location=assetLocation(doc,file);
      for(const page of location.pages){const button=node('button','Go to page '+page);button.type='button';button.addEventListener('click',()=>this.actions.page(page));row.append(button);}
      const subjects=new Set(location.owners.map(owner=>doc.details.get(owner.metadataRecordId)?.Subject).filter(Boolean));
      const cached=doc.childLabels?.get(file.id)?.Subject;if(cached)subjects.add(cached);
      for(const subject of subjects)if(subject!==fileLabel(file)){const label=node('p',subject!);label.className='file-message-subject';row.append(label);}
      for(const stream of file.streams){
        const actions=node('div','');actions.className='asset-actions file-actions';
        if(file.streams.length>1)row.classList.add('multiple-streams');
        const size=stream.decodedSize.value??stream.declaredSize.value,facts=node('span',sizeLabel(size)+(file.streams.length>1?' · Variant '+(file.streams.indexOf(stream)+1):''));facts.className='file-size';heading.append(facts);
        const save=node('button','Download');save.type='button';save.dataset.stream=stream.locator.path;
        save.replaceChildren(treeIcon('download'));save.className='icon-button';save.title='Download '+fileLabel(file);save.setAttribute('aria-label',save.title);save.addEventListener('click',()=>{this.select(file.id);this.actions.download(doc,file,stream);});actions.append(save);
        if(size!==0&&canView(file,stream)){const open=node('button','View');open.type='button';open.replaceChildren(treeIcon('view'));open.className='view-action icon-button';open.title=(isPdf(file,stream)?'Open ':'View ')+fileLabel(file);open.setAttribute('aria-label','View '+fileLabel(file));open.addEventListener('click',()=>this.actions.open(doc,file,stream));actions.prepend(open);}
        row.append(actions);
      }
      if(!file.streams.length)row.append(node('p','Embedded content is missing or unreadable.'));
      group.append(row);
    }
  }
}
