import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {parseFullTextIndex,queryFullTextIndex,verifyFileBinding,FULLTEXT_LIMITS} from '../dist/chromium/fulltext-index.js';
import {decodeEmbeddedGzip} from '../dist/chromium/embedded-index.js';
import {textMatches,normalizeSearchText} from '../dist/chromium/document-search.js';
import {gzipSync} from 'node:zlib';
const observed=value=>({state:'present',value});
function fixture(texts=['Contract contract contracts.\n東京 café ﬁancée İσς 😀','Two  spaces and three   spaces.','']){
 texts=texts.map(normalizeSearchText);
 const root={name:'test.pdf',pdf:{numPages:texts.length},inspection:{identification:{declaredProfile:observed('mi')},parts:[],messages:[],files:[],collection:observed(null)}};
 const rows=[['D',[[],'',0,texts.length]],...texts.map((_,n)=>['P',[0,n+1,n,'']]),...texts.map(t=>['T',t])],terms=new Map();
 for(const [id,text] of texts.entries())for(const token of text.toLowerCase().matchAll(/[^ ]+/g)){const list=terms.get(token[0])??[];list.push(id,token.index);terms.set(token[0],list);}
 let postings=0;
 for(const [term,positions] of [...terms].sort(([a],[b])=>a<b?-1:1)){const delta=[];let id=0,position=0;for(let n=0;n<positions.length;n+=2){delta.push(positions[n]-id,positions[n+1]-(positions[n]===id?position:0));id=positions[n];position=positions[n+1];postings++;}rows.push(['W',[term,delta]]);}
 const header={format:'eapdf-search-index',version:3,profile:'1mi',engine:'pdfbox-nfkd-space-fold-v3',complete:true,documents:1,pages:texts.length,texts:texts.length,terms:terms.size,postings};
 const encode=(edit={})=>{const body=rows.map(r=>JSON.stringify(r)+'\n').join('');return new TextEncoder().encode(JSON.stringify({...header,sha256:createHash('sha256').update(body).digest('hex'),...edit})+'\n'+body);};return {root,rows,header,encode};
}
test('whole words, phrases, case, whitespace and Unicode agree with page search',async()=>{
 const f=fixture(),index=await parseFullTextIndex(f.encode(),f.root,()=>{});
 for(const query of ['contract','CONTRACT','tract','contract contract','Two spaces','Two  spaces','three   spaces','東京','café','cafe','ﬁancée','fiancée','İ','σ','😀','absent',' ','.']){
  const result=await queryFullTextIndex(index,f.root,query,()=>{}),expected=index.texts.flatMap((text,n)=>{const matches=textMatches(text,query);return matches.length?[{page:n+1,count:matches.length}]:[];});
  assert.deepEqual(result.map(({page,count})=>({page,count})),expected,query);
 }
});
test('canonical query counting preserves overlaps, folded text, Unicode and result verification hashes',async()=>{
 const f=fixture(['aaaaa aaaa Con-\ntract literal-hyphen Café ﬁ ﬃ Straße İΣς 😀😀 𐐀','Cafe\u0301 é\nsecond page','']),index=await parseFullTextIndex(f.encode(),f.root,()=>{});
 for(const query of ['', ' ', 'aa','aaa','contract','con-\ntract','literal-hyphen','literal hyphen','fi','f','cafe','é','\u0301','i\u0307','σ','😀','😀😀','𐐀','straße','STRASSE']){
  const expected=index.texts.flatMap((text,n)=>{const matches=textMatches(text,query);return matches.length?[{page:n+1,count:matches.length,excerpt:text.slice(Math.max(0,matches[0].start-60),matches[0].end+120),hash:createHash('sha256').update(text).digest('hex')}]:[];});
  const result=await queryFullTextIndex(index,f.root,query,()=>{});
  assert.deepEqual(result.map(m=>({page:m.page,count:m.count,excerpt:m.excerpt,hash:m.indexed.textHash})),expected,query);
  assert.deepEqual(await queryFullTextIndex(index,f.root,query,()=>{}),result,'repeated query: '+query);
 }
});
test('indexed queries yield for cancellation and retain the execution deadline',async()=>{
 const f=fixture(['email '.repeat(20000)]),index=await parseFullTextIndex(f.encode(),f.root,()=>{});
 let cancelled=false;const timer=setTimeout(()=>{cancelled=true;},0);
 try{await assert.rejects(queryFullTextIndex(index,f.root,'email',()=>{if(cancelled)throw Error('cancelled');}),/cancelled/);}finally{clearTimeout(timer);}
 const original=Object.getOwnPropertyDescriptor(globalThis,'performance');let time=0;
 try{
  Object.defineProperty(globalThis,'performance',{configurable:true,value:{now:()=>time+=1001}});
  await assert.rejects(queryFullTextIndex(index,f.root,'email',()=>{}),/indexed query time limit/);
 }finally{Object.defineProperty(globalThis,'performance',original);}
});
for(const [name,edit] of [
 ['unsupported version',f=>f.header.version=99],['unsupported engine',f=>f.header.engine='other'],['incomplete coverage',f=>f.header.complete=false],['wrong profile',f=>f.header.profile='1c'],
 ['missing document inventory',f=>{f.rows.length=0;Object.assign(f.header,{documents:0,pages:0,texts:0,terms:0,postings:0});}],
 ['page reference',f=>f.rows[1][1][1]=999],['text reference',f=>f.rows[1][1][2]=999],['page ownership',f=>f.rows[1][1][3]='unknown-guid'],
 ['duplicate page',f=>f.rows[2][1][1]=1],['incorrect page count',f=>f.rows[0][1][3]=2],['unknown section',f=>f.rows.push(['Z',[]])],
 ['bad positions',f=>f.rows.find(r=>r[0]==='W')[1][1][1]=999],['negative delta',f=>f.rows.find(r=>r[0]==='W')[1][1][0]=-1],
 ['missing postings',f=>{f.rows.pop();f.header.terms--;}],['duplicate term',f=>{const row=f.rows.find(r=>r[0]==='W');f.rows.push(row);f.header.terms++;}],
 ['oversized declared terms',f=>f.header.terms=FULLTEXT_LIMITS.terms+1],['oversized declared pages',f=>f.header.pages=FULLTEXT_LIMITS.pages+1],['oversized declared postings',f=>f.header.postings=FULLTEXT_LIMITS.postings+1],
])test(name+' is rejected even with recomputed payload digest',async()=>{const f=fixture();edit(f);await assert.rejects(parseFullTextIndex(f.encode(),f.root,()=>{}));});
test('corruption, truncation and decompression limits fail closed',async()=>{
 const f=fixture();await assert.rejects(parseFullTextIndex(f.encode({sha256:'0'.repeat(64)}),f.root,()=>{}));await assert.rejects(parseFullTextIndex(f.encode().subarray(0,-1),f.root,()=>{}));
 const compressed=gzipSync(Buffer.alloc(1025,65)).toString('base64');await assert.rejects(decodeEmbeddedGzip(compressed,{compressed:4096,decoded:1024},()=>{}),/decompressed/);
 await assert.rejects(decodeEmbeddedGzip('AAAA',{compressed:10,decoded:10},()=>{}));
});
test('file binding covers later changes and detects duplicate markers across chunk boundaries',async()=>{
 const marker='<eapdfindex:fileDigest>',prefix='x'.repeat(1024*1024-8),suffix='</eapdfindex:fileDigest>end';
 const raw=prefix+marker+'0'.repeat(64)+suffix,digest=createHash('sha256').update(raw).digest('hex'),valid=new Blob([prefix,marker,digest,suffix]);
 await verifyFileBinding(valid,digest,()=>{});
 await assert.rejects(verifyFileBinding(new Blob([valid,'changed']),digest,()=>{}),/changed/);
 await assert.rejects(verifyFileBinding(new Blob([valid,marker,digest]),digest,()=>{}),/ambiguous/);
});
test('parsing, querying and byte binding respect cancellation',async()=>{
 const f=fixture(),index=await parseFullTextIndex(f.encode(),f.root,()=>{});const cancel=()=>{throw Error('cancelled');};
 await assert.rejects(parseFullTextIndex(f.encode(),f.root,cancel),/cancelled/);
 await assert.rejects(queryFullTextIndex(index,f.root,'contract',cancel),/cancelled/);
 await assert.rejects(verifyFileBinding(new Blob(['x']),'0'.repeat(64),cancel),/cancelled/);
});
test('nested routes, duplicate child names and multiple folder aliases keep distinct destinations',async()=>{
 const f=fixture(['needle']);f.root.inspection.identification= {declaredProfile:observed('ci')};f.header.profile='1ci';
 const stream=(id,md5)=>({locator:{path:id},declaredMd5:observed(md5),declaredSize:observed(100),mimeType:observed('application/pdf')});
 f.root.inspection.coverage={associatedFiles:'present'};
 f.root.inspection.files=[{id:'a',filename:observed('same.pdf'),unicodeFilename:observed(null),streams:[stream('a-stream','a'.repeat(32))]},{id:'b',filename:observed('same.pdf'),unicodeFilename:observed(null),streams:[stream('b-stream','b'.repeat(32))]}];
 f.root.inspection.collection=observed({entries:[{fileId:'a',nameTreeKey:observed('<1>same.pdf')},{fileId:'a',nameTreeKey:observed('<2>same.pdf')},{fileId:'b',nameTreeKey:observed('<3>same.pdf')}],folders:[]});
 const alias=['<1>same.pdf','<2>same.pdf'];
 f.rows.splice(1,0,['D',[[alias],'a'.repeat(32),100,1]],['D',[[['<3>same.pdf']],'b'.repeat(32),100,1]],['D',[[alias,['nested.pdf']],'c'.repeat(32),100,1]]);
 const position=f.rows.findIndex(r=>r[0]==='T');f.rows.splice(position,0,['P',[1,1,0,'duplicate-guid']],['P',[2,1,0,'duplicate-guid']],['P',[3,1,0,'nested-guid']]);f.header.documents=4;f.header.pages=4;
 const index=await parseFullTextIndex(f.encode(),f.root,()=>{}),matches=await queryFullTextIndex(index,f.root,'needle',()=>{});
 assert.equal(matches.length,4);assert.deepEqual(matches[0].path,[],'Visible collection cover remains searchable');assert.equal(matches[1].document,matches[2].document);assert.notDeepEqual(matches[1].path,matches[2].path);assert.deepEqual(matches[3].indexed.route,[alias,['nested.pdf']]);
 f.rows[1][1][0]=[['<1>same.pdf']];await assert.rejects(parseFullTextIndex(f.encode(),f.root,()=>{}));
});

test('old PDF.js engine falls back rather than mixing normalization rules',async()=>{const f=fixture();await assert.rejects(parseFullTextIndex(f.encode({version:2,engine:'pdfjs-6.5.438-space-join-lowercase-v1'}),f.root,()=>{}));});
test('normalization maps highlights to original glyphs and whitespace',()=>{
 assert.equal(normalizeSearchText('  Power \n VaR Café ﬁancée Σς  '),'power var café fiancée σσ');
 assert.deepEqual(textMatches('Power   VaR','power var'),[{start:0,end:11}]);
 assert.deepEqual(textMatches('ﬁancée','fiancée'),[{start:0,end:6}]);
 assert.deepEqual(textMatches('Café','Café'),[{start:0,end:4}]);
 assert.equal(textMatches('Café','cafe').length,0); // a combining mark remains part of the word
});

function signedBinding(){
 const marker='<eapdfindex:fileDigest>',descriptor='<eapdfindex:signatureContents>';
 const zero='0'.repeat(64),empty='0'.repeat(20)+':'+'0'.repeat(20);
 let prefix=marker+zero+'</eapdfindex:fileDigest>'+descriptor+empty+'</eapdfindex:signatureContents> /Contents ';
 const contents='<'+ 'ab'.repeat(100)+'>',end=prefix.length+contents.length;
 prefix=prefix.replace(empty,String(prefix.length).padStart(20,'0')+':'+String(end).padStart(20,'0'));
 const suffix=' /ByteRange [0 '+prefix.length+' '+end+' 4] EOF';
 const digest=createHash('sha256').update(prefix+suffix).digest('hex');
 return {prefix:prefix.replace(zero,digest),contents,suffix,digest,descriptor};
}
test('signature binding excludes only CMS bytes and covers all other changes',async()=>{
 const f=signedBinding(),blob=()=>new Blob([f.prefix,f.contents,f.suffix]);
 await verifyFileBinding(blob(),f.digest,()=>{});
 f.contents=f.contents.replaceAll('ab','cd');await verifyFileBinding(blob(),f.digest,()=>{}); // Not signature authentication.
 await assert.rejects(verifyFileBinding(new Blob([blob(),'appended revision']),f.digest,()=>{}),/changed/);
 await assert.rejects(verifyFileBinding(new Blob([f.prefix,f.contents,f.suffix.replace('EOF','EOf')]),f.digest,()=>{}),/changed/);
 await assert.rejects(verifyFileBinding(new Blob([f.prefix,f.contents.replace('cd','zz'),f.suffix]),f.digest,()=>{}),/Contents/);
 await assert.rejects(verifyFileBinding(new Blob([blob(),f.descriptor]),f.digest,()=>{}),/ambiguous/);
 for(const range of ['9'.repeat(20)+':'+'9'.repeat(20),'0'.repeat(20)+':'+'0'.repeat(19)+'1','x'.repeat(41)]){
  const prefix=f.prefix.replace(/\d{20}:\d{20}/,range);
  await assert.rejects(verifyFileBinding(new Blob([prefix,f.contents,f.suffix]),f.digest,()=>{}),/signature/);
 }
});
test('unsigned signature-capable bindings retain whole-file verification',async()=>{
 const raw='<eapdfindex:fileDigest>'+'0'.repeat(64)+'</eapdfindex:fileDigest><eapdfindex:signatureContents>'+'0'.repeat(20)+':'+'0'.repeat(20)+'</eapdfindex:signatureContents>';
 const digest=createHash('sha256').update(raw).digest('hex');await verifyFileBinding(new Blob([raw.replace('0'.repeat(64),digest)]),digest,()=>{});
});

test('pre-boundary PDFBox indexes fall back instead of trusting cross-run phantom matches',async()=>{const f=fixture();await assert.rejects(parseFullTextIndex(f.encode({engine:'pdfbox-nfkd-space-fold-v2'}),f.root,()=>{}),/unsupported full-text format\/engine/);});
