import {PortfolioModel,type PortfolioRow} from './portfolio.js';
import {PortfolioView} from './portfolio-view.js';
import {detectDocument,type Detection} from './document-kind.js';
import type {BrowserDocument} from './browser-session.js';
import type {CollectionViewHost} from './collection-view-contract.js';
import {element} from './viewer-elements.js';
import {isPdf} from './attachment-types.js';
import {saveBlob} from './downloads.js';
/** Optional collection presentation. Normal EA-PDF navigation remains in the viewer. */
export class CollectionView {
 private view:PortfolioView|undefined;
 private document:BrowserDocument|undefined;
 private readonly models=new WeakMap<BrowserDocument,PortfolioModel>();
 private returning:{doc:BrowserDocument;page:number;message?:string}|undefined;
 constructor(private readonly host:CollectionViewHost) {
element('portfolio-show').addEventListener('click',()=>this.view?.reveal());
element('portfolio-toggle').addEventListener('click',()=>void this.host.operation(async ticket=>{
  if(!this.host.current)return;
  if(this.view){const saved=this.returning,doc=saved?.doc??this.host.current;this.view.close();this.view=undefined;this.document=undefined;this.returning=undefined;await this.host.showDocument(doc,ticket,doc.inspection.messages.find(m=>m.id===saved?.message));if(saved){this.host.page=saved.page;await this.host.render(ticket,false);}this.host.showInformation(doc);this.host.prepareBrowseSubjects(doc,ticket);}
  else{this.returning={doc:this.host.current,page:this.host.page,message:this.host.savedMessages.get(this.host.current)};let doc=this.host.current;while(doc.parent&&!doc.inspection.collection.value)doc=doc.parent;if(!doc.inspection.collection.value){while(doc.parent)doc=doc.parent;}await this.activate(doc,ticket);}
}));

 }
 get active():boolean{return !!this.view;}
 get outlinePanel():string{return this.view?'portfolio-outline-panel':'outline-panel';}
 get outlineList():string{return this.view?'portfolio-outline':'outline';}
 controls(busy:boolean):void{this.view?.setBusy(busy);element<HTMLButtonElement>('portfolio-toggle').disabled=element<HTMLButtonElement>('portfolio-show').disabled=busy;}
 keyboardSelection(focused:Element|null):(()=>HTMLElement|null)|undefined{
  if(focused instanceof HTMLElement&&focused.matches('#portfolio-panel [data-portfolio-row]'))return ()=>element('portfolio-panel').querySelector<HTMLElement>('button[aria-current]');
  return undefined;
 }
 reset():void{this.view?.close();this.view=undefined;this.document=undefined;this.returning=undefined;element('portfolio-toggle').hidden=element('portfolio-show').hidden=true;}
 folderPath(doc:BrowserDocument):readonly string[]{return doc.parent&&doc.parentFileId?this.models.get(doc.parent)?.folderPath(doc.parentFileId)??[]:[];}
 information(root:Detection,detection:Detection,doc:BrowserDocument):void{
  element('portfolio-toggle').hidden=!root.collection&&!detection.collection;
  element('portfolio-toggle').textContent=this.view?(detectDocument((this.returning?.doc??doc).inspection).kind==='eapdf'?'Email view':'PDF view'):'Portfolio view';
  element('portfolio-show').hidden=!this.view?.hidden;
 }
 documentLabel():string{return 'PDF Portfolio';}
 async prepare(doc:BrowserDocument,ticket:number):Promise<void>{
  if(doc.parent?.inspection.collection.state==='present'&&!this.models.has(doc.parent)){
   const data=await this.host.session!.portfolio(doc.parent);this.host.check(ticket);this.models.set(doc.parent,new PortfolioModel(data,doc.parent.inspection));
  }
 }
 sync(doc:BrowserDocument):void{this.view?.syncDocument(doc.parent===this.document?doc.parentFileId:undefined);}
 async crumb(doc:BrowserDocument,ticket:number):Promise<void>{if(this.view)this.returning=undefined;if(this.view&&doc.inspection.collection.state==='present'&&doc!==this.document)await this.activate(doc,ticket);}
 async opened(doc:BrowserDocument,ticket:number,initial=false):Promise<boolean>{
  if((initial||this.view)&&detectDocument(doc.inspection).kind==='portfolio'){await this.activate(doc,ticket,true);return true;}return false;
 }
 async parent(doc:BrowserDocument,ticket:number):Promise<boolean>{if(!this.view)return false;this.returning=undefined;if(doc.inspection.collection.state==='present'&&doc!==this.document)await this.activate(doc,ticket);this.view.resetToTop();return true;}
 async embeddedTarget(doc:BrowserDocument,ticket:number):Promise<void>{
  this.returning=undefined;if(!this.view)return;
  let container:BrowserDocument|null=doc.inspection.collection.state==='present'?doc:doc.parent;while(container&&container.inspection.collection.state!=='present')container=container.parent;
  if(container&&container!==this.document)await this.activate(container,ticket);
  const row=this.view?.model.rows.find(r=>r.file?.id===doc.parentFileId);if(row)this.view?.show(row);this.view?.revealPreview();
 }
private async openRow(row:PortfolioRow,ticket:number,reveal=true):Promise<void>{
  const parent=this.document,file=row.file;if(!parent||!file)throw new Error('This collection member is unavailable.');
  const stream=file.streams.find(s=>s.efKeys.includes('/UF'))??file.streams[0];if(!stream)throw new Error('This file has no readable embedded content.');
  this.view?.show(row,reveal&&isPdf(file,stream));
  if(isPdf(file,stream)){await this.host.openChild(parent,file,stream,ticket);if(reveal)this.returning=undefined;return;}
  const data=await this.host.session!.bytes(parent,file,stream);this.host.check(ticket);
  await this.host.attachmentViewer.open(data,row.name,stream.mimeType.value??'',()=>element('portfolio-panel').querySelector<HTMLButtonElement>('button[aria-current]')?.focus({preventScroll:true}));this.host.check(ticket);
  this.host.status.textContent=`Viewing ${row.name}`;
}
private async activate(doc:BrowserDocument,ticket:number,initial=false):Promise<void>{
  const data=await this.host.session!.portfolio(doc);this.host.check(ticket);
  this.host.stopBrowseIndex();this.view?.close();this.document=doc;
  const model=new PortfolioModel(data,doc.inspection);this.models.set(doc,model);
  this.view=new PortfolioView(element('portfolio-panel'),model,{
    open:row=>void this.host.operation(active=>this.openRow(row,active)),
    cover:()=>void this.host.operation(async active=>{this.view?.revealPreview();await this.host.showDocument(doc,active);this.returning=undefined;}),
    thumbnail:async row=>{if(row.directory){await this.host.session!.ensure(doc);const result=await doc.run(doc.pdf.getDocumentAssets({folderThumbnail:row.directory.path}));this.host.check(ticket);return result.thumbnail;}const file=row.file,stream=file?.streams[0];if(!file||!stream)throw new Error('No preview available.');const bytes=await this.host.session!.bytes(doc,file,stream);this.host.check(ticket);return bytes;},
    download:row=>void this.host.operation(async active=>{const file=row.file,stream=file?.streams.find(s=>s.efKeys.includes('/UF'))??file?.streams[0];if(!file||!stream)throw new Error('This collection member is unavailable.');const bytes=await this.host.session!.bytes(doc,file,stream);this.host.check(active);await saveBlob(new Blob([new Uint8Array(bytes)],{type:'application/octet-stream'}),row.name,()=>this.host.check(active));this.host.check(active);}),
    parent:doc.parent?()=>void this.host.operation(active=>this.host.returnToParent(doc.parent!,active)):undefined,
  });
  this.view.syncDocument(this.host.current?.parent===doc?this.host.current.parentFileId:undefined);
  this.host.showInformation(this.host.current??doc);
  await this.host.showOutline(this.host.current??doc,ticket);
  if(initial){const target=model.initial();if(target==='invalid'){element('document-notice').textContent='The Portfolio initial document is missing or ambiguous. Showing the collection cover.';element('document-notice').hidden=false;}else if(target==='empty'){this.host.pages.clear();this.host.surface.hidden=true;this.host.empty.hidden=true;this.host.status.textContent='The portfolio has no files to preview.';}else if(target!=='container')await this.openRow(target,ticket,false);else {const plan=this.host.openingPlans.get(doc);if(plan){this.host.page=plan.page;await this.host.render(ticket,false);}}}
}
}
