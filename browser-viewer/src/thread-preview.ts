import {BrowserSession,type BrowserDocument} from './browser-session.js';
import {indexMessages,type IndexedMessage} from './message-index.js';
import type {ThreadGraph} from './thread-graph.js';
import type {MailMessage} from './domain/inspection.js';
import {VirtualList} from './virtual-list.js';
import {wholeWordAt} from './document-search.js';
import type {ConversationContext as Context} from './conversation-contract.js';
type CachedConversation={messages:IndexedMessage[];graphs:Map<boolean,ThreadGraph>;coverage:string;notices:string;expanded:Set<number>;query:string;infer:boolean};
export class ThreadPreview{
 private cache=new WeakMap<BrowserDocument,CachedConversation>();
 get enabled():boolean{return !!this.context&&!this.panel.hidden;}

 private epoch=0;private session?:BrowserSession;private worker?:Worker;private list?:VirtualList<{id:number;depth:number}>;private graph?:ThreadGraph;private messages:IndexedMessage[]=[];private expanded=new Set<number>();private context?:Context;
 private toggling=false;
 private navigating=false;private selected:number|null=null;private right=document.createElement('section');
 private button=document.createElement('button');private panel=document.createElement('section');private status=document.createElement('p');private rows=document.createElement('ul');private query=document.createElement('input');private infer=document.createElement('input');private back=document.createElement('button');
 constructor(private getContext:()=>Context|undefined){
  const host=document.getElementById('browse-panel')!;this.button.id='thread-preview-enable';this.button.textContent='Conversations';this.button.onclick=()=>void this.enable();host.before(this.button,this.panel);this.panel.id='thread-preview';this.panel.hidden=true;
  const title=document.createElement('strong');title.textContent='Conversations';this.back.textContent='Messages';this.back.id='thread-preview-disable';this.back.onclick=()=>this.reset(false);
  this.query.type='search';this.query.placeholder='Filter conversations (whole words)';this.query.setAttribute('aria-label','Filter conversations');this.query.oninput=()=>this.render();
  const label=document.createElement('label');this.infer.type='checkbox';this.infer.onchange=()=>void this.rebuild();label.append(this.infer,document.createTextNode(' Infer from matching subjects (experimental)'));
  this.status.id='thread-preview-status';this.status.setAttribute('role','status');this.rows.id='thread-preview-rows';this.panel.append(title,this.back,this.query,label,this.status,this.rows);
  this.right.id='thread-conversation';this.right.className='detail-section';this.right.hidden=true;document.getElementById('details-panel')!.append(this.right);
  this.right.tabIndex=0;this.right.addEventListener('keydown',event=>{if(event.altKey||event.ctrlKey||event.metaKey||!['ArrowUp','ArrowDown'].includes(event.key))return;const target=event.target;if(target instanceof HTMLElement&&target.closest('input,textarea,select'))return;if(this.navigate(event.key==='ArrowDown'?1:-1,false,true)){event.preventDefault();event.stopPropagation();}});
  title.className='thread-preview-title';label.className='thread-inference-option';
 }
 reset(clearCache=true){
  if(this.context){const saved=this.cache.get(this.context.root);if(saved){saved.expanded=new Set(this.expanded);saved.query=this.query.value;saved.infer=this.infer.checked;}}
  if(clearCache)this.cache=new WeakMap();
  this.selected=null;this.right.hidden=true;this.right.replaceChildren();delete this.panel.dataset.ready;this.epoch++;this.session?.close();this.session=undefined;this.worker?.terminate();this.worker=undefined;this.list?.close();this.list=undefined;this.graph=undefined;this.messages=[];this.context=undefined;this.rows.replaceChildren();this.expanded.clear();this.infer.checked=false;this.query.value='';this.panel.hidden=true;this.button.hidden=false;this.button.disabled=false;this.normal(true);}
 private normal(show:boolean){document.getElementById('browse-panel')!.hidden=!show;document.querySelector<HTMLElement>('.message-filter')!.hidden=!show;document.getElementById('filter-status')!.hidden=!show;for(const section of document.querySelectorAll<HTMLDetailsElement>('#files details'))section.open=show&&section.dataset.defaultOpen==='true';}
 private async enable(){const context=this.getContext();if(!context)return;this.reset(false);this.context=context;const epoch=this.epoch;this.panel.hidden=false;this.button.hidden=true;this.normal(false);this.status.textContent='Reading XMP message inventory…';
  const saved=this.cache.get(context.root);
  if(saved){this.messages=saved.messages;this.panel.dataset.coverage=saved.coverage;this.panel.dataset.notices=saved.notices;this.query.value=saved.query;this.infer.checked=saved.infer;this.expanded=new Set(saved.expanded);const graph=saved.graphs.get(saved.infer);if(graph){this.graph=graph;this.render();if(context.selection)this.sync(context.selection.doc,context.selection.message);return;}await this.rebuild();return;}
  const check=()=>{context.check();if(epoch!==this.epoch)throw Error('Conversation indexing cancelled.');};
  const session=new BrowserSession({},{reuseWorker:true});this.session=session;
  try{const blob=await context.session.sourceBlob(context.root);check();const doc=await session.open(blob,context.root.name);check();const result=await indexMessages(session,doc,check,count=>{check();this.status.textContent=`Reading XMP · ${count} messages…`;});check();this.messages=result.messages;
   this.panel.dataset.coverage=result.incomplete.length?'incomplete':'available';this.panel.dataset.notices=[...result.incomplete,...(result.notices??[])].join(' ');this.cache.set(context.root,{messages:this.messages,graphs:new Map(),coverage:this.panel.dataset.coverage,notices:this.panel.dataset.notices,expanded:new Set(),query:'',infer:false});await this.rebuild();
  }catch(error){if(epoch===this.epoch)this.status.textContent=`Conversations unavailable: ${error instanceof Error?error.message:String(error)}. Select Messages to return.`;}
  finally{session.close();if(this.session===session)this.session=undefined;}
 }
 private async rebuild(){delete this.panel.dataset.ready;if(!this.context)return;const epoch=this.epoch;this.worker?.terminate();const cached=this.cache.get(this.context.root)?.graphs.get(this.infer.checked);if(cached){this.worker=undefined;this.graph=cached;this.render();return;}const policy=this.infer.checked;const worker=new Worker(new URL('./thread-worker.js',import.meta.url),{type:'module'});this.worker=worker;this.status.textContent='Building conversations…';
  worker.onmessage=event=>{if(epoch!==this.epoch||this.worker!==worker)return;worker.terminate();this.worker=undefined;if(event.data.error){this.status.textContent=event.data.error;return;}this.graph=event.data.graph;this.cache.get(this.context!.root)?.graphs.set(policy,this.graph!);this.expanded.clear();this.render();const selection=this.getContext()?.selection;if(selection)this.sync(selection.doc,selection.message);};
  worker.onerror=()=>{if(epoch===this.epoch){worker.terminate();this.worker=undefined;this.status.textContent='Thread worker failed. Select Messages to return.';}};
  worker.postMessage({messages:this.messages,infer:this.infer.checked});
 }
 sync(doc:BrowserDocument,message?:MailMessage){
  if(!this.graph||!this.context)return;
  const selected=message??(doc.inspection.messages.length===1?doc.inspection.messages[0]:undefined);if(!selected){this.selected=null;this.right.hidden=true;return;}
  const index=this.messages.findIndex(m=>m.mailGuid?m.mailGuid===selected.mailGuid.value:m.messageId===selected.id);
  const node=this.graph.nodes.find(n=>n.message===index);if(!node)return;const changed=this.selected!==node.id;this.selected=node.id;this.render(changed);
 }
 private members(root:number):{id:number;depth:number}[]{const result:{id:number;depth:number}[]=[],pending=[{id:root,depth:0}];while(pending.length){const row=pending.pop()!;result.push(row);for(const id of this.graph!.nodes[row.id]!.children.slice().reverse())pending.push({id,depth:row.depth+1});}return result;}
 private root(id:number):number{while(this.graph!.nodes[id]!.parent!==null)id=this.graph!.nodes[id]!.parent!;return id;}
 private async select(id:number,edge?:-1|1,focusRight=false){const n=this.graph?.nodes[id],context=this.context;if(!n||n.message===null||!context)return;try{await context.open(this.messages[n.message]!,edge);if(this.context!==context)return;this.selected=id;this.render(true);if(focusRight)this.right.querySelector<HTMLButtonElement>('[aria-current=true]')?.focus({preventScroll:true});}catch(error){this.status.textContent=String(error);}}
 navigate(direction:-1|1,edge=false,focusRight=false):boolean{
  if(!this.graph||this.selected===null||!this.context)return false;
  const actual=this.members(this.root(this.selected)).filter(row=>this.graph!.nodes[row.id]!.message!==null);
  if(actual.length<2)return false;
  if(this.navigating)return true;
  const index=actual.findIndex(row=>row.id===this.selected),next=actual[index+direction];
  if(!next){this.status.textContent=direction===1?'End of this conversation.':'Beginning of this conversation.';return true;}
  this.navigating=true;void this.select(next.id,edge?direction:undefined,focusRight).finally(()=>{this.navigating=false;});return true;
 }
 private toggleRoot(id:number){
  const pane=document.getElementById('navigation-panel')!,old=this.rows.querySelector<HTMLElement>(`[data-thread-root="${id}"]`),top=old?.getBoundingClientRect().top,scroll=pane.scrollTop;
  this.expanded.has(id)?this.expanded.delete(id):this.expanded.add(id);
  this.toggling=true;try{this.render();}finally{this.toggling=false;}
  const index=this.visibleRows.findIndex(row=>row.id===id&&row.depth===0),row=this.list?.rowAt(index);
  if(row&&top!==undefined)pane.scrollTop+=row.getBoundingClientRect().top-top;else pane.scrollTop=scroll;
  row?.querySelector<HTMLButtonElement>('.conversation-expand')?.focus({preventScroll:true});
 }
 private visibleRows:{id:number;depth:number}[]=[];
 private messageRow(id:number,depth:number,right=false):HTMLLIElement{
  const n=this.graph!.nodes[id]!,m=n.message===null?undefined:this.messages[n.message],row=document.createElement('li');row.className='thread-message';row.dataset.threadNode=String(id);row.style.setProperty('--thread-depth',String(Math.min(depth,8)));
  const marker=document.createElement('span');marker.className='thread-marker'+(n.evidence==='Inferred'?' inferred':'');marker.title=n.subjectGroup?'Subject-based conversation group':n.evidence??'Root message';row.append(marker);
  const label=document.createElement(m?'button':'span');label.className='thread-message-content';label.title=n.label;
  const subject=document.createElement('strong');subject.textContent=n.label;label.append(subject);
  if(m){const meta=document.createElement('small');meta.textContent=[m.sender||'Sender not recorded',this.date(m.sent)].filter(Boolean).join(' · ');label.append(meta);label.dataset.threadMessage=String(n.message);label.onclick=()=>void this.select(id);if(id===this.selected){label.setAttribute('aria-current','true');row.classList.add('current');}}
  else if(!n.subjectGroup){row.classList.add('missing');}
  if(right&&n.evidence){const evidence=document.createElement('small');evidence.className='thread-evidence';evidence.textContent=n.evidence;label.append(evidence);}
  row.append(label);return row;
 }
 private date(value?:string):string{if(!value)return '';const date=new Date(value);return Number.isFinite(date.getTime())?date.toLocaleDateString(undefined,{year:'numeric',month:'short',day:'numeric'}):value;}
 private renderRight(){
  const graph=this.graph;if(!graph||this.selected===null){this.right.hidden=true;return;}
  const root=this.root(this.selected),members=this.members(root),actual=members.filter(m=>graph.nodes[m.id]!.message!==null),people=[...new Set(actual.map(m=>this.messages[graph.nodes[m.id]!.message!]!.sender).filter(Boolean))] as string[];
  const dates=actual.map(m=>this.messages[graph.nodes[m.id]!.message!]!.sent).filter((d):d is string=>!!d&&Number.isFinite(Date.parse(d))).sort((a,b)=>Date.parse(a)-Date.parse(b));
  const detailsOpen=this.right.querySelector<HTMLDetailsElement>('#thread-details-disclosure')?.open??false;
  if(actual.length<2){this.right.hidden=true;this.right.replaceChildren();return;}
  this.right.hidden=false;this.right.replaceChildren();const heading=document.createElement('h2');heading.textContent=`Conversation (${actual.length} messages)`;this.right.append(heading);
  const list=document.createElement('ol');list.className='conversation-tree';this.right.append(list);
  // Retain a bounded DOM for unusually long conversations; all messages remain reachable.
  const pageSize=100,selectedAt=members.findIndex(m=>m.id===this.selected),start=Math.floor(Math.max(0,selectedAt)/pageSize)*pageSize;
  for(const row of members.slice(start,start+pageSize))list.append(this.messageRow(row.id,row.depth,true));
  if(members.length>pageSize){const navigation=document.createElement('div');navigation.className='thread-page-controls';for(const [text,index]of [['Previous',Math.max(0,start-pageSize)],['Next',Math.min(members.length-1,start+pageSize)]] as const){const button=document.createElement('button');button.textContent=text;button.disabled=text==='Previous'?start===0:start+pageSize>=members.length;button.onclick=()=>{const next=members.slice(index,index+pageSize).find(m=>graph.nodes[m.id]!.message!==null);if(next)void this.select(next.id);};navigation.append(button);}this.right.append(navigation);}
  const disclosure=document.createElement('details');disclosure.id='thread-details-disclosure';disclosure.open=detailsOpen;const title=document.createElement('summary');title.textContent='Thread details';title.className='thread-details-title';const details=document.createElement('dl');details.className='thread-details';disclosure.append(title,details);this.right.append(disclosure);
  const fields=[['Thread ID',`conversation-${root+1}`],['Root Message-ID',graph.nodes[root]!.mid?`<${graph.nodes[root]!.mid}>`:graph.nodes[root]!.subjectGroup?'Subject-based group (no root message)':'Not recorded'],['Messages',String(actual.length)],['Date range',dates.length?this.date(dates[0])+' – '+this.date(dates.at(-1)):'Not recorded'],['Participants',people.slice(0,4).join('; ')+(people.length>4?`; and ${people.length-4} others`:'')||'Not recorded'],['Missing parents',String(new Set(members.flatMap(row=>{const n=graph.nodes[row.id]!;return [...n.missingAncestors,...(n.message===null&&!n.subjectGroup?[n.mid]:[])];})).size)],['Missing ancestor IDs',[...new Set(members.flatMap(row=>graph.nodes[row.id]!.missingAncestors))].map(id=>'<'+id+'>').join('; ')||'None']];
  for(const [key,value]of fields){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=key!;dd.textContent=value!;details.append(dt,dd);}
  const legend=document.createElement('p');legend.className='thread-legend';legend.textContent='● Confirmed (References / In-Reply-To)   ○ Inferred (Subject)';this.right.append(legend);
 }
 private render(reveal=false){const graph=this.graph;if(!graph)return;if(reveal&&this.selected!==null)this.expanded.add(this.root(this.selected));this.list?.close();const query=this.query.value.toLowerCase().trim(),visible=new Set<number>();
  if(query)for(const n of graph.nodes){const m=n.message===null?undefined:this.messages[n.message],text=(m?.searchText??n.label).toLowerCase();let at=text.indexOf(query),matches=false;while(at>=0){if(wholeWordAt(text,at,at+query.length)){matches=true;break;}at=text.indexOf(query,at+query.length);}if(matches){let id:number|null=n.id;while(id!==null&&!visible.has(id)){visible.add(id);id=graph.nodes[id]!.parent;}}}
  const flat:{id:number;depth:number}[]=[];
  for(const id of graph.roots){if(query&&!visible.has(id))continue;flat.push({id,depth:0});if(this.members(id).filter(row=>graph.nodes[row.id]!.message!==null).length>1&&(query||this.expanded.has(id)))for(const row of this.members(id)){if(graph.nodes[row.id]!.message!==null&&(!query||visible.has(row.id)))flat.push({id:row.id,depth:row.depth+1});}}
  this.status.textContent=`${graph.roots.filter(id=>this.members(id).filter(row=>graph.nodes[row.id]!.message!==null).length>1).length} conversations · ${this.messages.length} messages`;
  this.status.title=`${graph.linked} Confirmed links; ${graph.missing} missing parents; ${graph.duplicates} duplicate IDs; ${graph.cycles} rejected cycles. XMP only. ${this.panel.dataset.notices??''}`;
  const previous=this.panel.querySelector('.thread-diagnostics');previous?.remove();const info=document.createElement('details');info.className='thread-diagnostics';const summary=document.createElement('summary');summary.textContent='Indexing details';info.append(summary,document.createTextNode(this.status.title));this.status.after(info);
  this.panel.dataset.ready='true';this.visibleRows=flat;this.rows.replaceChildren();this.list=new VirtualList(this.rows,document.getElementById('navigation-panel')!,flat,({id,depth})=>{
   if(depth>0)return this.messageRow(id,depth-1);
   const members=this.members(id),actual=members.filter(m=>graph.nodes[m.id]!.message!==null),first=actual[0],representative=first?this.messages[graph.nodes[first.id]!.message!]:undefined;
   if(actual.length===1){const single=this.messageRow(first!.id,0);single.dataset.threadStandalone='true';single.classList.add('standalone-message');return single;}
   const row=document.createElement('li');row.className='conversation-group';row.dataset.threadRoot=String(id);
   if(this.selected!==null&&this.root(this.selected)===id)row.classList.add('selected');
   const toggle=document.createElement('button');toggle.className='conversation-expand';toggle.textContent=this.expanded.has(id)?'⌄':'›';toggle.setAttribute('aria-label',`Expand conversation ${representative?.label??'Missing conversation'}`);toggle.setAttribute('aria-expanded',String(this.expanded.has(id)));toggle.onclick=()=>{this.toggleRoot(id);};
   const open=document.createElement('button');open.className='conversation-summary';const subject=document.createElement('strong');subject.textContent=representative?.label.replace(/^(?:(?:re|fw|fwd)\s*:\s*)+/i,'')??'Missing conversation';const meta=document.createElement('small');const participants=[...new Set(actual.map(m=>this.messages[graph.nodes[m.id]!.message!]!.sender).filter(Boolean))];meta.textContent=[participants[0]||'Sender not recorded',participants.length>1?`and ${participants.length-1} others`:'',this.date(representative?.sent)].filter(Boolean).join(' · ');open.append(subject,meta);if(first){open.dataset.threadMessage=String(graph.nodes[first.id]!.message);open.onclick=()=>void this.select(first.id);}
   const count=document.createElement('span');count.className='conversation-count';count.textContent=String(actual.length);row.append(toggle,open,count);return row;
  },72);if(!this.toggling)this.renderRight();
  if(reveal&&this.selected!==null){const index=flat.findIndex(row=>row.id===this.selected&&row.depth>0);const fallback=index>=0?index:flat.findIndex(row=>row.id===this.root(this.selected!));const row=this.list.rowAt(fallback);if(row){const panel=document.getElementById('navigation-panel')!,rect=row.getBoundingClientRect(),area=panel.getBoundingClientRect(),header=panel.querySelector<HTMLElement>('.navigation-header')?.getBoundingClientRect().bottom??area.top;const top=header+8,bottom=area.bottom-8;if(rect.top<top)panel.scrollTop-=top-rect.top;else if(rect.bottom>bottom)panel.scrollTop+=rect.bottom-bottom;}}

 }
}
