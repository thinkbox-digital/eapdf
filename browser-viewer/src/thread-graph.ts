import type {IndexedMessage} from './message-index.js';
export type ThreadNode={id:number;message:number|null;label:string;parent:number|null;children:number[];evidence:'Confirmed'|'Inferred'|null;mid:string;missingAncestors:string[];pruned?:boolean;subjectGroup?:boolean};
export type ThreadGraph={nodes:ThreadNode[];roots:number[];duplicates:number;missing:number;cycles:number;linked:number};
export {messageIds} from './message-identifiers.js';
import {messageIds} from './message-identifiers.js';
export function threadGraph(messages:readonly IndexedMessage[],infer=false):ThreadGraph{
 if(messages.length>100000)throw Error('Threading preview exceeds 100,000 messages.');
 let characters=0,tokens=0,operations=0,duplicates=0,cycles=0;
 const nodes:ThreadNode[]=[],ids=new Map<string,number>();
 const node=(mid:string,message:number|null,label:string)=>{const id=nodes.length;nodes.push({id,message,label,parent:null,children:[],evidence:null,mid,missingAncestors:[]});return id;};
 const actual:number[]=[];
 // Register all occurrences first: later-loaded parents must not remain placeholders.
 messages.forEach((m,i)=>{
  characters+=(m.internetMessageId?.length??0)+(m.inReplyTo?.length??0)+(m.references?.length??0)+m.label.length;
  if(characters>8_000_000)throw Error('Thread metadata exceeds the preview byte/character budget.');
  const mid=messageIds(m.internetMessageId)[0]??'',id=node(mid,i,m.label);actual.push(id);
  if(mid){if(ids.has(mid))duplicates++;else ids.set(mid,id);}
 });
 const link=(parent:number,child:number,evidence:'Confirmed'|'Inferred',replace=false)=>{
  if(nodes[child]!.parent!==null&&!replace)return;
  if(replace){nodes[child]!.parent=null;nodes[child]!.evidence=null;}
  let cursor:number|null=parent;
  while(cursor!==null){if(++operations>2_000_000)throw Error('Thread reconstruction complexity limit reached.');if(cursor===child){cycles++;return;}cursor=nodes[cursor]!.parent;}
  nodes[child]!.parent=parent;nodes[child]!.evidence=evidence;
 };
 messages.forEach((m,i)=>{
  let refs=messageIds(m.references);if(!refs.length)refs=messageIds(m.inReplyTo).slice(0,1);
  tokens+=refs.length;if(tokens>200000)throw Error('Too many reference identifiers for this preview.');
  refs=refs.filter((id,index)=>index===0||id!==refs[index-1]);
  const chain=refs.map(mid=>{let id=ids.get(mid);if(id===undefined){id=node(mid,null,'Missing parent <'+mid+'>');ids.set(mid,id);}return id;});
  for(let j=1;j<chain.length;j++)link(chain[j-1]!,chain[j]!,'Confirmed');
  if(chain.length)link(chain.at(-1)!,actual[i]!,'Confirmed',true);else {nodes[actual[i]!]!.parent=null;nodes[actual[i]!]!.evidence=null;}
 });
 // Prune empty/single-child missing containers bottom-up. Preserve their IDs
 // as provenance on promoted children; shared missing roots retain their context.
 const adjacency=nodes.map(()=>[] as number[]);
 for(const n of nodes)if(n.parent!==null)adjacency[n.parent]!.push(n.id);
 const order:number[]=[],pending=nodes.filter(n=>!n.pruned&&n.parent===null).map(n=>n.id);
 while(pending.length){const id=pending.pop()!;order.push(id);for(const child of adjacency[id]!)pending.push(child);}
 for(const id of order.reverse()){
  const n=nodes[id]!;if(n.message!==null)continue;const children=adjacency[id]!,parent=n.parent;
  if(parent===null&&children.length>1)continue;
  n.pruned=true;n.parent=null;n.evidence=null;
  for(const child of children){const c=nodes[child]!;c.parent=parent;c.missingAncestors=[...n.missingAncestors,n.mid,...c.missingAncestors];if(parent===null)c.evidence=null;}
  if(parent!==null){const siblings=adjacency[parent]!,index=siblings.indexOf(id);siblings.splice(index,1,...children);}
  adjacency[id]=[];
 }
 // Subject similarity groups independent chains; it does not establish a reply parent.
 if(infer){
  const subjects=new Map<string,number[]>();
  for(const id of actual){const n=nodes[id]!;if(n.parent!==null)continue;const base=n.label.replace(/^(?:(?:re|fw|fwd)\s*:\s*)+/i,'').trim().toLowerCase();if(!base)continue;const group=subjects.get(base);if(group)group.push(id);else subjects.set(base,[id]);}
  for(const members of subjects.values())if(members.length>1){
   const label=nodes[members[0]!]!.label.replace(/^(?:(?:re|fw|fwd)\s*:\s*)+/i,'').trim();
   const group=node('',null,'Conversation: '+label);nodes[group]!.subjectGroup=true;
   for(const id of members)link(group,id,'Inferred');
  }
 }
 for(const n of nodes)if(n.parent!==null)nodes[n.parent]!.children.push(n.id);
 const dates=nodes.map(n=>{const value=n.message===null?NaN:Date.parse(messages[n.message]?.sent??'');return Number.isFinite(value)?value:Infinity;});
 for(const n of nodes)if(n.subjectGroup)for(const child of n.children)dates[n.id]=Math.min(dates[n.id]!,dates[child]!);
 const date=(id:number)=>dates[id]!;
 const compare=(a:number,b:number)=>date(a)-date(b)||a-b;
 for(const n of nodes)n.children.sort(compare);
 const roots=nodes.filter(n=>!n.pruned&&n.parent===null).map(n=>n.id).sort(compare);
 return {nodes,roots,duplicates,cycles,missing:nodes.filter(n=>n.message===null&&!n.subjectGroup).length,linked:nodes.filter(n=>n.evidence==='Confirmed').length};
}
