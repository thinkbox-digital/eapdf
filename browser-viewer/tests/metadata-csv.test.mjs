import {test} from 'node:test';
import assert from 'node:assert/strict';
import {csvCell,metadataCsv} from '../dist/chromium/metadata-csv.js';
const doc=(name='mail.pdf')=>({name,details:new Map([['email',{From:'Alice <a@example.org>',To:'Bob <b@example.org>',Subject:'Hello, "Bob"\n안녕',Sent:'2026-10-29T12:00:00Z'}]]),inspection:{diagnostics:[],coverage:{metadata:'present',associatedFiles:'present'},metadataRecords:[],messages:[{id:'message',metadataRecordId:'email',internetMessageId:{value:'<id>'},mailGuid:{value:'uuid:1'}}],collection:{state:'missing',value:null},files:[]}});
const run=(d,child=()=>{throw Error('unexpected child');},check=()=>{})=>metadataCsv(d,child,check,()=>{});
test('CSV preserves quoted multiline Unicode fields and blank missing values',async()=>{
 const result=await run(doc());assert.equal(result.rows,1);const text=await result.blob.text();assert.doesNotMatch(text,/Mail GUID|uuid:1/);
 assert.match(text,/"Hello, ""Bob""\n안녕"/);assert.match(text,/"Bob <b@example.org>","",""/);assert.match(text,/"2026-10-29T12:00:00Z"/);
 const bytes=new Uint8Array(await result.blob.arrayBuffer());assert.deepEqual([...bytes.slice(0,3)],[239,187,191]);
});
test('spreadsheet formula prefixes are neutralized, including whitespace',()=>{
 for(const value of ['=1+1','+cmd','-2','@SUM(1)','  =1','\t=1','\n=1'])assert.equal(csvCell(value),'"\''+value+'"');
 assert.equal(csvCell('a,b'),'"a,b"');
});
test('long subjects are not truncated by CSV export',async()=>{
 const d=doc();d.details.get('email').Subject='x'.repeat(9000);assert.ok((await (await run(d)).blob.text()).includes('x'.repeat(9000)));
});
test('complete flat metadata exports independently of document-part or file traversal limits',async()=>{
 const d=doc();d.inspection.coverage.associatedFiles='unavailable';d.inspection.diagnostics=[{code:'TRAVERSAL_LIMIT'},{code:'OBJECT_LIMIT'}];
 assert.equal((await run(d)).rows,1);
});
test('unreadable or conflicting displayed metadata blocks export instead of becoming a blank or invented value',async()=>{
 for(const code of ['XMP_DISPLAY_VALUE_UNAVAILABLE','XMP_DISPLAY_VALUE_AMBIGUOUS']){
  const d=doc();d.inspection.diagnostics=[{code}];await assert.rejects(run(d),/could not be read unambiguously/);
 }
 const d=doc();delete d.details.get('email').Sent;
 assert.match(await (await run(d)).blob.text(),/,"","<id>","",""\r\n/,'an absent date remains blank without a substitute');
});
test('collection visits members sequentially, keeps repeated message IDs and includes PDF path',async()=>{
 const d=doc('collection.pdf');d.inspection.collection={state:'present',value:{folders:[],entries:[{fileId:'a',folderIds:{state:'present',value:[]}},{fileId:'b',folderIds:{state:'present',value:[]}}]}};
 d.inspection.files=['a','b'].map(id=>({id,unicodeFilename:{value:id+'.pdf'},streams:[{mimeType:{value:'application/pdf'}}]}));
 let active=0;const result=await run(d,async(_,f)=>{assert.equal(active++,0);await new Promise(r=>setTimeout(r,1));active--;return doc(f.id+'.pdf');});
 assert.equal(result.rows,2);assert.match(await result.blob.text(),/collection.pdf \/ b.pdf/);
 assert.doesNotMatch(await result.blob.text(),/"collection.pdf","Alice/);
});
test('unreadable children, inspection limits, empty metadata and cancellation do not return partial exports',async()=>{
 const d=doc();d.inspection.diagnostics=[{code:'XMP_RECORD_LIMIT'}];await assert.rejects(run(d),/fully read/);
 const empty=doc();empty.inspection.messages=[];await assert.rejects(run(empty),/No email metadata/);
 await assert.rejects(run(doc(),undefined,()=>{throw Error('Cancelled');}),/Cancelled/);
 const c=doc();c.inspection.collection={state:'present',value:{folders:[],entries:[{fileId:'a',folderIds:{state:'present',value:[]}}]}};c.inspection.files=[{id:'a',unicodeFilename:{value:'a.pdf'},streams:[{mimeType:{value:'application/pdf'}}]}];
 await assert.rejects(run(c,async()=>{throw Error('Unreadable child');}),/Unreadable child/);
});
const present=value=>({state:'present',value});
const folder=(id,name,parentId=null)=>({id,name:present(name),parentId});
const member=id=>({id,unicodeFilename:present(id+'.pdf'),streams:[{mimeType:present('application/pdf')}]});
const collection=(name,folders,entries,files)=>{const d=doc(name);d.inspection.collection=present({folders,entries});d.inspection.files=files;return d;};
test('CSV preserves nested collection folder paths and multiple folder memberships without duplicating messages',async()=>{
 const root=collection('root.pdf',[folder('root','Mailbox'),folder('in','Inbox','root'),folder('saved','Saved','root')],[{fileId:'nested',folderIds:present(['in'])},{fileId:'nested',folderIds:present(['saved'])}],[member('nested')]);
 const nested=collection('nested.pdf',[folder('r','Archive'),folder('leaf','2026','r')],[{fileId:'email',folderIds:present(['leaf'])}],[member('email')]);
 const result=await run(root,async(parent)=>parent===root?nested:doc('email.pdf'));
 assert.equal(result.rows,1);const csv=await result.blob.text();
 assert.match(csv,/"\/Mailbox\/Inbox\/Archive\/2026\n\/Mailbox\/Saved\/Archive\/2026"/);
 assert.match(csv,/root.pdf \/ nested.pdf \/ email.pdf/);
});
test('CSV rejects unresolved, cyclic and unknown folder membership instead of making up a root path',async()=>{
 for(const [folders,ids] of [[[folder('a','A','a')],present(['a'])],[[],present(['unknown'])],[[],{state:'ambiguous',value:null}]]){
  const root=collection('root.pdf',folders,[{fileId:'email',folderIds:ids}],[member('email')]);
  await assert.rejects(run(root,async()=>doc()),/folder/i);
 }
});
test('CSV includes declared attachment counts and names, preserves duplicate names and marks missing names',async()=>{
 const d=doc();d.inspection.metadataRecords=[{id:'email',kind:'email',fields:{NumberAttachments:present('3')}},
 ...['a','b'].map(id=>({id,kind:'attachment',owners:present(['message']),fields:{Filename:present('report,"final".pdf')}}))];
 const csv=await (await run(d)).blob.text();
 assert.match(csv,/"3","report,""final"".pdf\nreport,""final"".pdf\n\(1 attachment name\(s\) not recorded\)"/);
 assert.doesNotMatch(csv,/Mail GUID|uuid:1/);
 d.inspection.metadataRecords=[{id:'email',kind:'email',fields:{NumberAttachments:present('0')}}];
 assert.match(await (await run(d)).blob.text(),/,"0",""\r\n/);
 d.inspection.metadataRecords=[];
 assert.match(await (await run(d)).blob.text(),/,"<id>","",""\r\n/,'missing count is blank, never zero');
});
test('CSV rejects invalid attachment counts, conflicting counts and unidentified owners',async()=>{
 for(const count of ['-1','999999999999999999999','text','0']){
  const d=doc();d.inspection.metadataRecords=[{id:'email',kind:'email',fields:{NumberAttachments:present(count)}},{id:'a',kind:'attachment',owners:present(['message']),fields:{Filename:present('a.txt')}}];
  await assert.rejects(run(d),/attachment count/i);
 }
 const d=doc();d.inspection.metadataRecords=[{id:'a',kind:'attachment',owners:{state:'ambiguous',value:null},fields:{Filename:present('a.txt')}}];
 await assert.rejects(run(d),/ownership/);
});

test('CSV exports all rows beyond the former 100000-row cutoff',async()=>{
  const d=doc();d.inspection.messages=Array(100001).fill(d.inspection.messages[0]);
  const result=await run(d);assert.equal(result.rows,100001);
  assert.equal((await result.blob.text()).split('\r\n').length,100003);
});
test('CSV visits every collection member beyond the former 1000-PDF cutoff',async()=>{
  const d=doc('collection.pdf');const ids=Array.from({length:1001},(_,i)=>String(i));
  d.inspection.collection={state:'present',value:{folders:[],entries:ids.map(fileId=>({fileId,folderIds:{state:'present',value:[]}}))}};
  d.inspection.files=ids.map(member);
  const result=await run(d,async(_,f)=>doc(f.id+'.pdf'));assert.equal(result.rows,1001);
});

test('collection export releases each child before opening the next, including failures',async()=>{
 const root=collection('root.pdf',[],['a','b'].map(fileId=>({fileId,folderIds:present([])})),['a','b'].map(member));
 const events=[];
 const result=await metadataCsv(root,async(_,f)=>{events.push('open '+f.id);return doc(f.id);},()=>{},()=>{},d=>events.push('release '+d.name));
 assert.equal(result.rows,2);assert.deepEqual(events,['open a','release a','open b','release b']);
 const released=[];await assert.rejects(metadataCsv(root,async()=>{const d=doc('bad');d.inspection.coverage.metadata='unavailable';return d;},()=>{},()=>{},d=>released.push(d.name)),/fully read/);
 assert.deepEqual(released,['bad']);
});
test('progress reports the child before awaiting its inspection',async()=>{
 const root=collection('root.pdf',[],[{fileId:'a',folderIds:present([])}],[member('a')]);
 let last;
 await metadataCsv(root,async()=>{assert.deepEqual(last,[0,'a.pdf']);return doc('a.pdf');},()=>{},(...p)=>{last=p;});
});
