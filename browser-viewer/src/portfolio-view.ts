import {scatterPositions} from './portfolio-scatter.js';
import {PortfolioSplitter} from './portfolio-splitter.js';
import {PortfolioThumbnails,type ThumbnailData} from './portfolio-thumbnails.js';
import {treeIcon} from './viewer-icons.js';
import {PortfolioModel,type PortfolioRow,type Scalar} from './portfolio.js';
const node=<K extends keyof HTMLElementTagNameMap>(tag:K,text='')=>{const n=document.createElement(tag);n.textContent=text;return n;};
const button=(label:string,action:()=>void)=>{const n=node('button',label);n.type='button';n.addEventListener('click',action);return n;};
const iconButton=(label:string,icon:Parameters<typeof treeIcon>[0],action:()=>void,compact=false)=>{const b=button(compact?'':label,action);b.prepend(treeIcon(icon));b.title=label;b.setAttribute('aria-label',label);b.className=compact?'quiet icon-button':'quiet portfolio-command';return b;};
export class PortfolioView {
  private readonly scatterSeed=crypto.getRandomValues(new Uint32Array(1))[0]!;private readonly resizeObserver:ResizeObserver;
  private readonly splitter:PortfolioSplitter;private readonly thumbnails:PortfolioThumbnails;private readonly collapsed=new Set<string>();
  private folder:string|null;private query='';private layout:string;private previousLayout='D';private offset=0;private selected='';private busy=false;
  private sort:readonly Scalar[];private ascending:readonly Scalar[];private readonly columns:Set<string>;
  private readonly body=node('div');private readonly summary=node('p');private readonly location=node('div');private readonly layouts=node('select');
  private readonly search=node('input');private timer:ReturnType<typeof setTimeout>|undefined;private readonly visibleText=new Map<string,string>();
  private readonly content=node('div');private currentRows:PortfolioRow[]=[];
  private readonly metadata=node('details');private readonly metadataTitle=node('summary','Selected file details');private readonly metadataValues=node('dl');
  private readonly badge=node('span');private readonly navigation=node('nav');
  private history:(string|null)[]=[];private historyIndex=0;
  private splitDirection='V';private splitPosition=35;private sidebarWidth=35;
  constructor(readonly host:HTMLElement,readonly model:PortfolioModel,private readonly actions:{open:(row:PortfolioRow)=>void;cover:()=>void;download:(row:PortfolioRow)=>void;thumbnail:(row:PortfolioRow)=>Promise<ThumbnailData>;parent?:()=>void}) {
    this.thumbnails=new PortfolioThumbnails(this.body,actions.thumbnail);
    this.resizeObserver=new ResizeObserver(()=>this.arrangeFreeform());this.resizeObserver.observe(this.body);
    this.splitter=new PortfolioSplitter(document.getElementById('workspace')!,host,position=>this.setSize(position));
    this.folder=model.root;this.layout=model.layout();if(this.layout!=='H')this.previousLayout=this.layout;this.sort=model.data.sort;this.ascending=model.data.ascending;this.columns=new Set(model.fields.filter(f=>['F','Size'].includes(f.subtype)).map(f=>f.key));
    const extent=document.getElementById('workspace')!.getBoundingClientRect().width;let pixels=425;
    try{const saved=Number(localStorage.getItem('eapdf.portfolio.width'));if(Number.isFinite(saved)&&saved>=220)pixels=saved;}catch{/* Storage may be disabled. */}
    this.sidebarWidth=extent>0?Math.min(70,Math.max(220,Math.min(pixels,extent-280))/extent*100):35;
    this.history=[this.folder];
    host.replaceChildren();host.removeAttribute('style');host.hidden=false;host.setAttribute('aria-label','PDF Portfolio');
    const heading=node('div');heading.className='portfolio-heading panel-heading';const title=node('h2','Portfolio');title.prepend(treeIcon('folder'));this.badge.className='badge';title.append(this.badge);heading.append(title);
    heading.append(iconButton('Hide portfolio pane','left',()=>this.hide(),true));
    this.navigation.className='portfolio-navigation';this.navigation.setAttribute('aria-label','Portfolio navigation');
    if(actions.parent)this.navigation.append(iconButton('Parent portfolio','up',actions.parent));this.navigation.append(iconButton('Cover page','document',actions.cover));
    const tools=node('div');tools.className='portfolio-tools';this.search.type='search';this.search.placeholder='Filter files and metadata';this.search.setAttribute('aria-label','Filter portfolio files and metadata');
    this.search.addEventListener('input',()=>{clearTimeout(this.timer);this.timer=setTimeout(()=>{this.query=this.search.value.trim().toLocaleLowerCase();this.offset=0;this.render();},150);});
    this.layouts.setAttribute('aria-label','Portfolio layout');for(const [value,label] of [['D','Details'],['T','Tiles'],['Tree','Folder tree'],['FilmStrip','Filmstrip'],['Linear','Preview and details'],['FreeForm','Freeform'],['H','Hide portfolio']] as const){const option=node('option',label);option.value=value;this.layouts.append(option);}this.layouts.value=this.layout;
    this.layouts.addEventListener('change',()=>{if(this.layout!=='H')this.previousLayout=this.layout;this.layout=this.layouts.value;if(this.layout==='Linear')this.metadata.open=true;this.offset=0;this.applyLayout(false,true);this.render();});
    const fields=node('details');fields.className='portfolio-columns';fields.append(node('summary','Details columns'));
    for(const field of model.fields){const label=node('label'),input=node('input');input.type='checkbox';input.checked=this.columns.has(field.key);input.addEventListener('change',()=>{if(input.checked)this.columns.add(field.key);else this.columns.delete(field.key);this.render();});label.append(input,document.createTextNode(field.label));fields.append(label);}
    const filter=node('label');filter.className='portfolio-filter';filter.append(treeIcon('search'),this.search);
    const options=node('details');options.className='portfolio-options';const optionTitle=node('summary','View options');options.append(optionTitle,fields);
    tools.append(filter,this.layouts,options);
    this.location.className='portfolio-path';this.summary.className='portfolio-summary';this.summary.setAttribute('role','status');this.body.className='portfolio-scroll';this.body.tabIndex=0;this.metadata.className='portfolio-selection';this.metadata.open=true;this.metadata.append(this.metadataTitle,this.metadataValues);this.body.append(this.content,this.metadata);
    const warnings=node('p',[...new Set(model.notices)].join(' '));warnings.className='portfolio-notice';warnings.hidden=!warnings.textContent;warnings.setAttribute('role','status');
    const bookmarks=node('details');bookmarks.id='portfolio-outline-panel';bookmarks.hidden=true;bookmarks.append(node('summary','PDF bookmarks'));const outline=node('ul');outline.id='portfolio-outline';bookmarks.append(outline);
    heading.insertBefore(this.navigation,heading.lastChild);host.append(heading,tools,this.location,warnings,bookmarks,this.summary,this.body);
    for(const row of model.rows){let text=row.name;for(const field of model.fields){if(text.length>=65536)break;const display=model.display(row,field),detail=model.detail(row,field);text+='\n'+(display+(detail!==display?'\n'+detail:'')).slice(0,65536-text.length);}this.visibleText.set(row.id,text.toLocaleLowerCase());}
    host.addEventListener('keydown',this.keydown);
    this.applyLayout(true);this.render();
  }
  private keydown=(event:KeyboardEvent)=>{
    const target=event.target;if(!(target instanceof HTMLElement)||(!target.matches('button[data-portfolio-row]')&&target!==this.body)||this.busy)return;
    if(event.ctrlKey||event.metaKey||event.altKey)return;
    const index=this.currentRows.findIndex(r=>r.id===(target.dataset.portfolioRow??this.selected)),row=this.currentRows[index];
    if(this.layout==='Tree'&&row?.kind==='folder'&&['ArrowRight','ArrowLeft'].includes(event.key)){event.preventDefault();if(event.key==='ArrowRight')this.collapsed.delete(row.id);else this.collapsed.add(row.id);this.render();this.focusRow(row.id);return;}
    if(event.key==='Backspace'){event.preventDefault();this.up();return;}
    if(!['ArrowDown','ArrowUp','Home','End'].includes(event.key))return;
    event.preventDefault();const next=this.currentRows[event.key==='Home'?0:event.key==='End'?this.currentRows.length-1:index<0?0:index+(event.key==='ArrowDown'?1:-1)];
    if(next){this.offset=Math.floor(this.currentRows.indexOf(next)/100)*100;this.select(next);}
  };
  private focusRow(id:string):void{this.content.querySelector<HTMLButtonElement>(`button[data-portfolio-row="${CSS.escape(id)}"]`)?.focus({preventScroll:true});}
  private visit(folder:string|null,record=true):void{this.folder=folder;this.offset=0;this.query='';this.search.value='';if(record){this.history=this.history.slice(0,this.historyIndex+1);this.history.push(folder);this.historyIndex=this.history.length-1;}this.render();this.body.focus({preventScroll:true});}
  private up():void{if(this.folder!==this.model.root)this.visit(this.model.data.folders.find(f=>f.path===this.folder)?.parent??this.model.root);}
  get hidden():boolean{return this.layout==='H';}
  close():void{this.resizeObserver.disconnect();this.splitter.close();this.thumbnails.close();clearTimeout(this.timer);this.host.removeEventListener('keydown',this.keydown);this.host.replaceChildren();this.host.hidden=true;delete document.body.dataset.portfolio;delete document.body.dataset.portfolioSplit;document.getElementById('workspace')!.style.removeProperty('--portfolio-size');}
  setBusy(value:boolean):void{this.busy=value;for(const control of this.host.querySelectorAll<HTMLButtonElement|HTMLSelectElement|HTMLInputElement>('button,select,input'))control.disabled=value||control.dataset.unavailable==='true';}
  private hide():void{if(this.layout!=='H')this.previousLayout=this.layout;this.layout='H';this.layouts.value='H';this.applyLayout(false);}
  reveal():void{if(this.layout==='H'){this.layout=this.previousLayout;this.layouts.value=this.layout;}this.applyLayout(false);this.render();}
  revealPreview():void{if(matchMedia('(max-width:750px)').matches){this.hide();return;}if(this.layout!=='H'&&this.splitDirection==='N'){this.splitDirection='V';this.splitPosition=this.sidebarWidth;this.applyLayout(false);}}
  show(row:PortfolioRow,reveal=true):void{if(reveal)this.revealPreview();this.selected=row.id;if(!this.currentRows.some(r=>r.id===row.id)){this.folder=row.folder;this.offset=0;this.query='';this.search.value='';}this.render();}
  resetToTop():void{clearTimeout(this.timer);this.selected='';this.visit(this.model.root);this.body.scrollTop=0;this.body.scrollLeft=0;}
  syncDocument(fileId:string|undefined):void{const row=fileId?this.model.rows.find(r=>r.file?.id===fileId):undefined;if(this.selected===(row?.id??''))return;if(row)this.show(row,false);else{this.selected='';this.render();}}
  private setSize(position:number,remember=true):void{
    this.splitPosition=position;if(this.splitDirection==='V'){this.sidebarWidth=position;if(remember){try{localStorage.setItem('eapdf.portfolio.width',String(document.getElementById('workspace')!.getBoundingClientRect().width*position/100));}catch{/* Keep resizing available without storage. */}}}
    document.getElementById('workspace')!.style.setProperty('--portfolio-size',`${position}%`);
    this.splitter.update(this.splitDirection,position,this.layout!=='H'&&this.splitDirection!=='N');
  }
  private applyLayout(initial:boolean,userLayout=false):void{
    document.body.dataset.portfolio=this.layout;this.host.hidden=this.layout==='H';document.getElementById('portfolio-show')!.hidden=this.layout!=='H';
    if(initial){
      const split=this.model.data.split,direction=split?.direction;
      this.splitDirection=typeof direction==='string'&&['H','V','N'].includes(direction)?direction:this.model.data.view==='C'?'N':this.layout==='D'?'H':'V';
      this.splitPosition=typeof split?.position==='number'&&split.position>=0&&split.position<=100?split.position:this.splitDirection==='H'?55:35;
      if(this.splitDirection==='V')this.splitPosition=this.sidebarWidth;
    }else if(userLayout&&this.layout!=='H'){
      // User-selected presentations share the sidebar; only the initial view
      // follows the document's split preference. Keep the chosen sidebar width.
      this.splitDirection='V';this.splitPosition=this.sidebarWidth;
    }
    document.body.dataset.portfolioSplit=this.layout==='H'?'N':this.splitDirection;
    const position=this.splitPosition;
    this.setSize(position,false);
    const colors=new Map(this.model.data.colors);for(const [key,css] of [['Background','--portfolio-background'],['CardBackground','--portfolio-card'],['CardBorder','--portfolio-border'],['PrimaryText','--portfolio-primary'],['SecondaryText','--portfolio-secondary']]){const v=colors.get(key!);if(v?.length===3&&v.every(c=>typeof c==='number'&&c>=0&&c<=1))this.host.style.setProperty(css!,`rgb(${v.map(c=>Math.round(Number(c)*255)).join(',')})`);}
  }
  private select(row:PortfolioRow):void{
    if(this.busy)return;
    if(row.kind==='folder'){this.selected=row.id;if(this.layout==='Tree'){if(this.collapsed.has(row.id))this.collapsed.delete(row.id);else this.collapsed.add(row.id);this.render();this.focusRow(row.id);return;}this.visit(row.id);return;}
    this.selected=row.id;this.render();this.content.querySelector<HTMLButtonElement>(`button[data-portfolio-row="${CSS.escape(row.id)}"]`)?.focus({preventScroll:true});this.actions.open(row);
  }
  private arrangeFreeform():void{
    if(this.layout!=='FreeForm')return;const board=this.content.querySelector<HTMLElement>('.portfolio-scatter');if(!board)return;
    const cards=[...board.querySelectorAll<HTMLElement>('.portfolio-card')];
    const layout=scatterPositions(cards.map(c=>c.dataset.member!),board.clientWidth,this.scatterSeed);
    cards.forEach((card,i)=>{const p=layout.positions[i]!;card.style.left=p.x+'px';card.style.top=p.y+'px';card.style.width=layout.cardWidth+'px';});board.style.height=layout.height+'px';
  }
  private render():void {
    const ancestry:{id:string;name:string}[]=[];let cursor=this.folder;const seen=new Set<string>();while(cursor&&!seen.has(cursor)){seen.add(cursor);const folder=this.model.data.folders.find(f=>f.path===cursor);if(!folder)break;ancestry.unshift({id:folder.path,name:typeof folder.name==='string'?folder.name:'Folder'});cursor=folder.parent;}
    this.location.replaceChildren();this.location.setAttribute('aria-label','Portfolio folder path');
    const back=iconButton('Previous folder','left',()=>{this.historyIndex--;this.visit(this.history[this.historyIndex]!,false);},true),forward=iconButton('Next folder','right',()=>{this.historyIndex++;this.visit(this.history[this.historyIndex]!,false);},true),up=iconButton('Up one folder','up',()=>this.up(),true);
    back.dataset.unavailable=String(this.historyIndex===0);forward.dataset.unavailable=String(this.historyIndex>=this.history.length-1);up.dataset.unavailable=String(this.folder===this.model.root);this.location.append(back,forward,up);
    const path=node('nav');path.className='portfolio-breadcrumbs';path.setAttribute('aria-label','Portfolio folders');const root=button('All files',()=>this.visit(this.model.root));path.append(root);
    for(const folder of ancestry.filter(f=>f.id!==this.model.root)){const crumb=button(folder.name,()=>this.visit(folder.id));if(folder.id===this.folder)crumb.setAttribute('aria-current','location');path.append(node('span','›'),crumb);}this.location.append(path);
    const visible=this.model.rows.filter(row=>this.query?this.visibleText.get(row.id)!.includes(this.query):this.layout==='Tree'||row.folder===this.folder);
    this.currentRows=this.model.sorted(visible,this.sort,this.ascending);
    if(this.layout==='Tree'&&!this.query){const ordered:PortfolioRow[]=[],visited=new Set<string>(),children=new Map<string|null,PortfolioRow[]>();for(const row of this.currentRows){const group=children.get(row.folder)??[];group.push(row);children.set(row.folder,group);}const pending=[...(children.get(this.model.root)??[])].reverse();while(pending.length){const row=pending.pop()!;if(visited.has(row.id))continue;visited.add(row.id);ordered.push(row);if(row.kind==='folder'&&!this.collapsed.has(row.id)){const group=children.get(row.id)??[];for(let i=group.length-1;i>=0;i--)pending.push(group[i]!);}}this.currentRows=ordered;}
    this.badge.textContent=String(this.currentRows.length);
    for(const old of this.navigation.querySelectorAll('[data-file-step]'))old.remove();
    const files=this.currentRows.filter(r=>r.kind==='file'),selected=files.findIndex(r=>r.id===this.selected);
    for(const [direction,label,icon] of [[-1,'Previous file','left'],[1,'Next file','right']] as const){const index=selected<0?0:selected+direction,command=iconButton(label,icon,()=>{const row=files[index];if(row){this.offset=Math.floor(this.currentRows.indexOf(row)/100)*100;this.select(row);}},true);command.title=files[index]?`${label}: ${files[index]!.name}`:label;command.dataset.fileStep=String(direction);command.dataset.unavailable=String(!files[index]||(selected<0&&direction===-1));this.navigation.append(command);}
    const slice=this.currentRows.slice(this.offset,this.offset+100);
    this.summary.textContent=`${this.currentRows.length} ${this.query?'matching items':'items'}${this.currentRows.length>100?` · Showing ${this.offset+1}–${Math.min(this.currentRows.length,this.offset+100)}`:''}`;
    this.thumbnails.reset();this.content.replaceChildren();this.content.className='portfolio-items';this.content.dataset.layout=this.layout;
    const columns=this.model.fields.filter(f=>this.layout==='D'?this.columns.has(f.key):f.visible);
    const chosen=this.model.rows.find(row=>row.id===this.selected);this.metadata.hidden=!['D','Linear'].includes(this.layout)||!chosen;
    if(this.layout==='Linear')this.body.prepend(this.metadata);else this.body.append(this.metadata);this.metadataValues.replaceChildren();
    this.metadataTitle.textContent=chosen?'Selected file details: '+chosen.name:'Selected file details';
    if(chosen){for(const field of this.model.fields){const value=this.model.detail(chosen,field);if(value)this.metadataValues.append(node('dt',field.label),node('dd',value));}}
    const fileButton=(row:PortfolioRow)=>{const b=button(row.name,()=>this.select(row));b.dataset.portfolioRow=row.id;b.className='portfolio-file';if(row.id===this.selected)b.setAttribute('aria-current','true');b.title=row.name;b.setAttribute('aria-label',row.name);if(this.layout==='D'){b.removeAttribute('title');const cut=Math.max(0,row.name.length-12),start=node('span',row.name.slice(0,cut)),end=node('span',row.name.slice(cut));start.className='portfolio-name-start';end.className='portfolio-name-end';const name=node('span');name.className='portfolio-name';name.append(start,end);b.replaceChildren(name);b.dataset.fullName=row.name;}if(row.kind==='folder'&&this.layout==='Tree')b.setAttribute('aria-expanded',String(!this.collapsed.has(row.id)));b.prepend(treeIcon(row.kind==='folder'?'folder':/\.(eml|msg|mbox|pst)$/i.test(row.name)?'message':/\.pdf$/i.test(row.name)?'document':'attachment'));if(row.kind==='folder'&&this.layout==='Tree'){const chevron=treeIcon('right');chevron.classList.add('portfolio-disclosure');b.prepend(chevron);}return b;};
    if(this.layout==='D'){
      const table=node('table'),head=node('thead'),tr=node('tr');
      const displayed=columns.some(f=>f.subtype==='F')?columns:[{key:'__name',label:'Name',subtype:'F',order:0,visible:true},...columns];
      table.classList.toggle('compact-details',displayed.every(f=>['F','Size'].includes(f.subtype)));
      const colgroup=node('colgroup');for(const field of displayed){const col=node('col');col.dataset.fieldType=field.subtype;colgroup.append(col);}const actionColumn=node('col');actionColumn.className='portfolio-actions-column';colgroup.append(actionColumn);table.append(colgroup);
      for(const field of displayed){const th=node('th');th.scope='col';const key=field.key==='__name'?'F':field.key;const active=this.sort[0]===key;th.setAttribute('aria-sort',active?(this.ascending[0]===false?'descending':'ascending'):'none');th.append(button(field.label,()=>{this.ascending=[active?this.ascending[0]===false:true];this.sort=[key];this.render();}));tr.append(th);}tr.append(node('th',''));head.append(tr);table.append(head);const tbody=node('tbody');
      for(const row of slice){const line=node('tr');if(row.id===this.selected)line.classList.add('selected');for(const field of displayed){const td=node('td');td.dataset.fieldType=field.subtype;if(field.subtype!=='F')td.title=this.model.detail(row,field);td.append(field.subtype==='F'?fileButton(row):document.createTextNode(this.model.display(row,field).slice(0,1024)));line.append(td);}const actions=node('td');actions.className='portfolio-row-actions';const stack=node('div');stack.className='portfolio-action-stack';actions.append(stack);if(row.kind==='file')stack.append(iconButton('View','view',()=>this.select(row),true),iconButton('Download','download',()=>this.actions.download(row),true));line.append(actions);tbody.append(line);}table.append(tbody);this.content.append(table);
    }else if(this.layout==='Linear'){
      const label=node('h3','Files');label.className='portfolio-list-heading';this.content.append(label);
      if(!chosen)this.content.append(node('p','Select a file to see its preview and details.'));
      for(const row of slice){const line=node('div');line.className='portfolio-linear-row';line.append(fileButton(row));if(row.kind==='file'){const actions=node('div');actions.className='portfolio-action-stack';actions.append(iconButton('View','view',()=>this.select(row),true),iconButton('Download','download',()=>this.actions.download(row),true));line.append(actions);}this.content.append(line);}
    }else{
      const board=this.layout==='FreeForm'?node('div'):this.content;if(board!==this.content){board.className='portfolio-scatter';this.content.append(board);}
      for(const row of slice){const card=node('article');card.className='portfolio-card';if(row.id===this.selected)card.classList.add('selected');card.dataset.member=row.id;const type=row.kind==='folder'?'Folder':row.name.split('.').at(-1)?.toUpperCase()??'File';const icon=node('div');icon.className='portfolio-file-type';icon.append(treeIcon(row.kind==='folder'?'folder':/\.pdf$/i.test(row.name)?'document':'attachment'),node('span',type));if(this.layout!=='Tree'||row.directory?.thumbnail)this.thumbnails.observe(row,icon);if(row.directory?.thumbnail)icon.classList.add('stored-thumbnail');if(this.layout==='FreeForm'){const preview=button('',()=>this.select(row));preview.className='portfolio-thumbnail-button';preview.setAttribute('aria-label','View '+row.name);preview.title='View '+row.name;preview.append(icon);card.append(preview);}else card.append(icon);card.append(fileButton(row));const dl=node('dl');for(const field of (['FilmStrip','FreeForm'].includes(this.layout)?[]:columns).filter(f=>f.subtype!=='F')){const value=this.model.display(row,field);if(value){const description=node('dd',value.slice(0,1024));description.title=this.model.detail(row,field);dl.append(node('dt',field.label),description);}}if(!['FilmStrip','FreeForm'].includes(this.layout))card.append(dl);if(row.kind==='file'){const actions=node('div');actions.className='portfolio-card-actions';actions.append(iconButton('View','view',()=>this.select(row),true),iconButton('Download','download',()=>this.actions.download(row),true));card.append(actions);}if(this.layout==='Tree'){let depth=0,p=row.folder;const seen=new Set<string>();while(p&&p!==this.model.root&&!seen.has(p)){seen.add(p);depth++;p=this.model.data.folders.find(f=>f.path===p)?.parent??null;}card.style.marginLeft=`${Math.min(depth,20)*16}px`;}board.append(card);}this.arrangeFreeform();
    }
    if(this.currentRows.length>100){const paging=node('nav');paging.setAttribute('aria-label','Portfolio list pages');if(this.offset>0)paging.append(iconButton('Previous 100','left',()=>{this.offset-=100;this.render();}));if(this.offset+100<this.currentRows.length)paging.append(iconButton('Next 100','right',()=>{this.offset+=100;this.render();}));this.content.append(paging);}
    if(!this.currentRows.length)this.content.append(node('p',this.query?'No matching files or folders.':'This folder is empty.'));
    this.setBusy(this.busy);
  }
}
