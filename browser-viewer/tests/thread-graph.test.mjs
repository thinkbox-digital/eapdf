import {test} from 'node:test';
import assert from 'node:assert/strict';
import {threadGraph,messageIds} from '../dist/chromium/thread-graph.js';
const m=(id,refs='',reply='',label=id)=>({path:[],messageId:id,document:'test.pdf',searchText:label,label,internetMessageId:'<'+id+'>',references:refs,inReplyTo:reply});
test('Confirmed chain uses References ahead of In-Reply-To and preserves input',()=>{
 const messages=[m('a'),m('b','<a>'),m('c','<a> <b>','<wrong>')],before=JSON.stringify(messages),g=threadGraph(messages);
 assert.equal(g.roots.length,1);assert.equal(g.nodes[2].parent,1);assert.equal(g.linked,2);assert.equal(JSON.stringify(messages),before);
});
test('missing ancestors, duplicates and absent IDs remain distinct occurrences',()=>{
 const g=threadGraph([m('a','<missing>'),m('a'),{...m('x'),internetMessageId:undefined}]);assert.equal(g.missing,1);assert.equal(g.duplicates,1);assert.equal(g.nodes.filter(n=>n.message!==null).length,3);
});
test('cycles and self-links are rejected without dropping messages',()=>{
 const g=threadGraph([m('a','<b>'),m('b','<a>'),m('c','<c>')]);assert.equal(g.cycles,2);assert.equal(g.nodes.length,3);
});
test('inference is opt-in and IDs are case-sensitive',()=>{
 const messages=[m('a','','','Topic'),m('A','','','Re: Topic')];assert.equal(threadGraph(messages).roots.length,2);const g=threadGraph(messages,true);assert.equal(g.roots.length,1);assert.equal(g.nodes[1].evidence,'Inferred');assert.deepEqual(messageIds('<A> <a>'),['A','a']);
});
test('large shallow inventory stays bounded and deterministic',()=>{
 const messages=Array.from({length:10000},(_,i)=>m('id'+i,i?' <id0>':''));const g=threadGraph(messages);assert.equal(g.nodes.length,10000);assert.equal(g.roots.length,1);assert.equal(g.linked,9999);
});
test('hostile chains hit an explicit complexity budget instead of unbounded work',()=>{
 const messages=Array.from({length:10000},(_,i)=>m('id'+i,i?`<id${i-1}>`:''));assert.throws(()=>threadGraph(messages),/complexity limit/);
});

test('quoted equivalent IDs match without lowercasing or altering originals',()=>{
 const messages=[m('"01KF8JCEOCBS0045PS"@Example.COM'),m('child@example','<01KF8JCEOCBS0045PS@Example.COM>')];
 const before=JSON.stringify(messages),g=threadGraph(messages);assert.equal(g.nodes[1].parent,0);assert.equal(g.linked,1);assert.equal(JSON.stringify(messages),before);
 assert.deepEqual(messageIds('<"foo\\bar"@example> <foobar@example> <"foo bar"@example> <foo bar@example>'),['foobar@example','foobar@example','"foo bar"@example']);
 assert.deepEqual(messageIds('<a@Example> <a@example>'),['a@Example','a@example']);
});
test('comments, folding and malformed brackets cannot introduce false identifiers',()=>{
 assert.deepEqual(messageIds('(ignore <fake@example>) <a (comment) @ example>\r\n <b@example>'),['a@example','b@example']);
 assert.deepEqual(messageIds('<a (note > <fake@example>) @ example>'),['a@example']);
 assert.deepEqual(messageIds('<bad<fake@example>> <good@example>'),['good@example']);
 assert.deepEqual(messageIds('<foo(comment)bar@example> <broken\n@example> <a@@example> <.dot@example>'),[]);
 assert.deepEqual(messageIds('"ignore <fake@example>" <local@[127.0.0.1]>'),['local@[127.0.0.1]']);
});
test('quoted/unquoted duplicates retain both occurrences with deterministic first-ID binding',()=>{
 const g=threadGraph([m('"a"@example'),m('a@example'),m('child@example','<a@example>')]);assert.equal(g.duplicates,1);assert.equal(g.nodes[2].parent,0);assert.equal(g.nodes.filter(n=>n.message!==null).length,3);
});
test('single-child missing chains are compacted with ancestor IDs preserved',()=>{
 const g=threadGraph([m('a','<root> <parent>'),m('b','<a>')]);assert.equal(g.roots.length,1);assert.equal(g.roots[0],0);assert.deepEqual(g.nodes[0].missingAncestors,['root','parent']);assert.equal(g.nodes[1].parent,0);assert.equal(g.missing,2);assert.equal(g.nodes.filter(n=>n.pruned).length,2);
});
test('shared missing root stays visible and descendants never become fake messages',()=>{
 const g=threadGraph([m('a','<missing>'),m('b','<missing>')]);const root=g.nodes[g.roots[0]];assert.equal(g.roots.length,1);assert.equal(root.message,null);assert.equal(root.pruned,undefined);assert.equal(root.children.length,2);
});
test('a message own headers replace ancestry hinted by another references chain',()=>{
 const g=threadGraph([m('c','<a> <b>'),m('b'),m('a')]);assert.equal(g.nodes[1].parent,null);assert.equal(g.nodes[0].parent,1);
 const fallback=threadGraph([m('a'),m('b','<invalid id>','<a>')]);assert.equal(fallback.nodes[1].parent,0);
});

test('subject inference groups chains as chronological peers without inventing message parents',()=>{
 const messages=[{...m('later','','','Fwd: Topic'),sent:'2026-02-08T13:32:44-05:00'},
 {...m('reply','<early>','','Re: Topic'),sent:'2026-02-04T10:00:00-05:00'},
 {...m('early','','','Topic'),sent:'2026-02-04T09:00:00-05:00'}];
 const before=JSON.stringify(messages);
 for(const input of [messages,[...messages].reverse()]){
  const g=threadGraph(input,true),group=g.nodes[g.roots[0]];
  assert.equal(g.roots.length,1);assert.equal(group.subjectGroup,true);assert.equal(group.message,null);assert.equal(group.mid,'');
  assert.deepEqual(group.children.map(id=>input[g.nodes[id].message].messageId),['early','later']);
  const early=g.nodes.find(n=>n.mid==='early'),later=g.nodes.find(n=>n.mid==='later'),reply=g.nodes.find(n=>n.mid==='reply');
  assert.equal(early.parent,group.id);assert.equal(later.parent,group.id);assert.equal(early.evidence,'Inferred');assert.equal(later.evidence,'Inferred');
  assert.equal(reply.parent,early.id);assert.equal(reply.evidence,'Confirmed');assert.equal(g.linked,1);assert.equal(g.missing,0);
  assert.equal(g.nodes.filter(n=>n.message!==null).length,3);
 }
 assert.equal(JSON.stringify(messages),before);assert.equal(threadGraph(messages).roots.length,2);
});
test('subject groups retain missing ancestor provenance and put undated components last',()=>{
 const messages=[m('undated','','','Topic'),{...m('dated','<missing>','','Re: Topic'),sent:'2026-02-04T09:00:00Z'},m('other','','','Other')];
 const g=threadGraph(messages,true),group=g.nodes.find(n=>n.subjectGroup);
 assert.deepEqual(group.children.map(id=>g.nodes[id].mid),['dated','undated']);
 assert.equal(g.missing,1);assert.deepEqual(g.nodes[1].missingAncestors,['missing']);assert.equal(g.roots.length,2);
 assert.equal(g.nodes.find(n=>n.mid==='other').parent,null);
});

const permutations=items=>items.length?items.flatMap((item,i)=>permutations(items.filter((_,j)=>j!==i)).map(rest=>[item,...rest])):[[]];
const parentOf=(g,mid)=>{const n=g.nodes.find(n=>n.mid===mid);return n.parent===null?null:g.nodes[n.parent].mid;};
const membership=g=>g.roots.map(root=>{const members=[],pending=[root];while(pending.length){const n=g.nodes[pending.pop()];if(n.message!==null)members.push(n.mid);pending.push(...n.children);}return members.sort();}).sort((a,b)=>a.join(',').localeCompare(b.join(',')));

test('all permutations preserve explicit roots over another message ancestry hints',()=>{
 const messages=[m('a'),m('b'),m('c','<a> <b>')],before=JSON.stringify(messages);
 for(const input of permutations(messages))for(const infer of [false,true]){
  const g=threadGraph(input,infer),order=input.map(m=>m.messageId).join(',');
  assert.equal(parentOf(g,'a'),null,order);assert.equal(parentOf(g,'b'),null,order);assert.equal(parentOf(g,'c'),'b',order);
  assert.deepEqual(membership(g),[['a'],['b','c']],order);assert.equal(g.linked,1);assert.equal(g.cycles,0);
 }
 assert.equal(JSON.stringify(messages),before);
});

test('all permutations retain own References or In-Reply-To rather than conflicting ancestry hints',()=>{
 for(const own of [['<d>','<wrong>'],['','<d>'],['<invalid id>','<d>']]){
  const messages=[m('a'),m('b',...own),m('c','<a> <b>'),m('d')];
  for(const input of permutations(messages)){
   const g=threadGraph(input),order=input.map(m=>m.messageId).join(',');
   assert.equal(parentOf(g,'b'),'d',order);assert.equal(parentOf(g,'c'),'b',order);
   assert.deepEqual(membership(g),[['a'],['b','c','d']],order);assert.equal(g.linked,2);
   assert.equal(g.nodes.find(n=>n.mid==='b').evidence,'Confirmed');
  }
 }
});

test('ancestry hints cannot create a cycle that defeats a message own missing-parent decision',()=>{
 for(const input of permutations([m('b','<missing>'),m('c','<b> <missing>')])){
  const g=threadGraph(input),order=input.map(m=>m.messageId).join(',');
  assert.equal(parentOf(g,'b'),'missing',order);assert.equal(parentOf(g,'c'),'missing',order);
  assert.equal(g.nodes[g.roots[0]].mid,'missing');assert.equal(g.nodes[g.roots[0]].message,null);
  assert.deepEqual(membership(g),[['b','c']],order);assert.equal(g.linked,2);assert.equal(g.cycles,1);
 }
});
