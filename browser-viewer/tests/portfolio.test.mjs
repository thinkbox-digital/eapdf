import test from 'node:test';import assert from 'node:assert/strict';
import {PortfolioModel,detectDocument,pdfDate} from '../dist/chromium/portfolio.js';
const obs=value=>({state:value===null?'missing':'present',value});
const inspection=(version=null,profile=null,collection=false)=>({identification:{eapdfVersion:obs(version),eapdfRevision:obs(version?'2024':null),declaredProfile:obs(profile)},collection:obs(collection?{}:null),files:[]});
const base=()=>({initial:null,initialHex:null,view:'D',layouts:[],fields:[],sort:[],ascending:[],folders:[],entries:[],split:null,colors:[],notices:[]});
const entry=(name,values=[])=>({key:name,keyHex:Buffer.from(name).toString('hex'),filePath:name,name,description:null,created:null,modified:null,size:null,compressedSize:null,values});
test('EA-PDF takes precedence over Collection without treating detection as validation',()=>{
 for(const profile of ['s','si','m','mi','c','ci'])assert.equal(detectDocument(inspection('1',profile,true)).kind,'eapdf');
 assert.equal(detectDocument(inspection(null,null,true)).kind,'portfolio');assert.equal(detectDocument(inspection()).kind,'pdf');
 assert.equal(detectDocument(inspection('2','c',true)).kind,'portfolio');assert.match(detectDocument(inspection('2','c',true)).notice,/unsupported/);
 assert.equal(detectDocument(inspection(null,'c',true)).kind,'portfolio');
 const mixed=inspection('1','c',true);mixed.identification.eapdfVersion.state='ambiguous';assert.equal(detectDocument(mixed).kind,'portfolio');
});
test('Portfolio sorting uses numeric/date values, tie breakers and ignores displayed prefixes',()=>{
 const data={...base(),fields:[{key:'n',label:'Number',subtype:'N',visible:true},{key:'s',label:'Subject',subtype:'S',visible:true},{key:'date',label:'Date',subtype:'D',visible:false}],sort:['n','s'],ascending:[false,true],entries:[entry('a',[['n',2,null],['s','z','A']]),entry('b',[['n',10,null],['s','b','Z']]),entry('c',[['n',10,null],['s','a','ZZ']])]};
 const model=new PortfolioModel(data,inspection());assert.deepEqual(model.sorted(model.rows).map(r=>r.name),['c','b','a']);assert.equal(model.display(model.rows[1],model.fields[1]),'Zb');
 assert.ok(pdfDate('D:20240101120000+02\'00\'')<pdfDate('D:20240101110000Z'));assert.equal(pdfDate('D:20240231'),null);assert.equal(pdfDate('invalid'),null);
});
test('initial target distinguishes missing from empty/unmatched byte strings and uses sorted fallback',()=>{
 const data={...base(),entries:[entry('b'),entry('a')]};const model=new PortfolioModel(data,inspection());assert.equal(model.initial(),'container');data.initialHex='';assert.equal(model.initial(),'invalid');data.initialHex=Buffer.from('b').toString('hex');assert.equal(model.initial().name,'b');data.entries=[];assert.equal(new PortfolioModel(data,inspection()).initial(),'invalid');
});
test('membership comes only from the collection name tree, with correct folder root fallback',()=>{
 const data={...base(),folders:[{path:'root',parent:null,number:0,name:'Root',values:[]},{path:'child',parent:'root',number:1,name:'Child',values:[]}],entries:[entry('<1>x'),entry('plain'),entry('<99>bad'),entry('<1>')]};
 const i=inspection();i.files=[{id:'unrelated',locator:{path:'unrelated'},origins:[]}];const model=new PortfolioModel(data,i);assert.equal(model.rows.filter(r=>r.kind==='file').length,4);assert.deepEqual(model.rows.filter(r=>r.kind==='file').map(r=>r.folder),['child','root','root','root']);
});
test('navigator selection follows the first recognized layout and scalar descending applies to tie breakers',()=>{
 const d={...base(),view:'C',layouts:['CustomLayout','FilmStrip','D']};assert.equal(new PortfolioModel(d,inspection()).layout(),'FilmStrip');
 d.layouts=['Custom'];assert.equal(new PortfolioModel(d,inspection()).layout(),'D');
 d.fields=[{key:'n',label:'N',subtype:'N',visible:true},{key:'s',label:'S',subtype:'S',visible:true}];d.sort=['n','s'];d.ascending=[false];d.ascendingAll=false;d.entries=[entry('a',[['n',1,null],['s','a',null]]),entry('b',[['n',1,null],['s','b',null]])];const m=new PortfolioModel(d,inspection());assert.deepEqual(m.sorted(m.rows).map(r=>r.name),['b','a']);
});
test('document breadcrumbs use the complete unambiguous member folder path',()=>{
 const data={...base(),folders:[{path:'root',parent:null,number:0,name:'Root',values:[]},{path:'child',parent:'root',number:1,name:'Tagged PDF',values:[]},{path:'leaf',parent:'child',number:2,name:'Logical Structure',values:[]}],entries:[entry('<2>tags.pdf'),entry('root.pdf')]};
 const i=inspection();i.files=data.entries.map((e,n)=>({id:'file'+n,locator:{path:e.filePath},origins:[],filename:obs(e.name),unicodeFilename:obs(null)}));
 const model=new PortfolioModel(data,i);assert.deepEqual(model.folderPath('file0'),['Tagged PDF','Logical Structure']);assert.deepEqual(model.folderPath('file1'),[]);assert.deepEqual(model.folderPath('missing'),[]);
 model.rows.push({...model.rows.find(r=>r.file?.id==='file0'),id:'duplicate',folder:'root'});assert.deepEqual(model.folderPath('file0'),[],'conflicting memberships cannot invent a breadcrumb');
});
