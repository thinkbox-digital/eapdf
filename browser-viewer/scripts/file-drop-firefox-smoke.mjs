import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {once} from 'node:events';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const probe=createServer();probe.listen(0,'127.0.0.1');await once(probe,'listening');const port=probe.address().port;await new Promise(done=>probe.close(done));
const driver=spawn(process.env.GECKODRIVER_PATH??'geckodriver',['--allow-system-access','--host','127.0.0.1','--port',String(port),'--log','error'],{stdio:['ignore','ignore','inherit']});
let session,launchError;driver.on('error',error=>launchError=error);
async function request(path,body,method='POST'){
 if(launchError)throw launchError;
 const r=await fetch(`http://127.0.0.1:${port}${path}`,{method,headers:{'content-type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30000)});
 const data=(await r.json()).value;if(!r.ok)throw new Error(JSON.stringify(data));return data;
}
const call=(path,body,method)=>request(`/session/${session}${path}`,body,method);
const execute=(fn,args=[])=>call('/execute/sync',{script:`return (${fn.toString()})(...arguments);`,args});
async function until(action){const deadline=Date.now()+30000;while(Date.now()<deadline){if(await action())return;await new Promise(done=>setTimeout(done,100));}throw new Error('Firefox file-drop check timed out');}
try{
 await until(async()=>{try{return !!await request('/status',undefined,'GET');}catch{if(launchError)throw launchError;return false;}});
 const result=await request('/session',{capabilities:{alwaysMatch:{browserName:'firefox','moz:firefoxOptions':{binary:process.env.FIREFOX_PATH??'/usr/lib/firefox/firefox',args:['-headless']}}}});session=result.sessionId;
 const addon=await call('/moz/addon/install',{path:resolve('dist/firefox'),temporary:true});
 await call('/moz/context',{context:'chrome'});
 const uuid=await execute(id=>JSON.parse(Services.prefs.getCharPref('extensions.webextensions.uuids'))[id],[addon]);
 await call('/moz/context',{context:'content'});const url=`moz-extension://${uuid}/viewer.html`;await call('/url',{url});
 await until(()=>execute(()=>!!document.querySelector('#file')&&!document.querySelector('#file').disabled));
 const source=resolve('../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf'),bytes=await readFile(source),sha256=createHash('sha256').update(bytes).digest('hex');const checks=[];
 for(const [name,selector] of [['left-empty','#navigation-panel'],['left-filter','#message-filter'],['left-message','#tree button'],['left-consuming-control','#message-filter'],['center','#viewport'],['right','#files'],['toolbar','.app-header']]){
  const cancelled=await execute((selector,name,encoded)=>{
   const target=document.querySelector(selector);if(!target)throw new Error('Missing '+selector);
   if(name==='left-consuming-control')for(const type of ['dragenter','dragover','drop'])target.addEventListener(type,event=>event.stopPropagation());
   const bytes=Uint8Array.from(atob(encoded),c=>c.charCodeAt(0)),dataTransfer=new DataTransfer();dataTransfer.items.add(new File([bytes],name+'.pdf',{type:'application/pdf'}));
   return ['dragenter','dragover','drop'].map(type=>{const event=new DragEvent(type,{bubbles:true,cancelable:true,dataTransfer});target.dispatchEvent(event);return event.defaultPrevented;});
  },[selector,name,bytes.toString('base64')]);
  assert.deepEqual(cancelled,[true,true,true]);
  await until(()=>execute(name=>document.querySelector('#header-filename').textContent===name+'.pdf'&&!document.querySelector('#file').disabled,[name]));
  assert.equal(await call('/url',undefined,'GET'),url);checks.push({target:name,status:'PASS'});console.log('PASS Firefox drop:',name);
 }
 assert.equal(createHash('sha256').update(await readFile(source)).digest('hex'),sha256);
 await mkdir('test-results',{recursive:true});await writeFile('test-results/file-drop-firefox.json',JSON.stringify({status:'PASS',sha256,checks,method:'DOM file-drag events with actual PDF bytes; browser OS drag integration not exercised'},null,2)+'\n');
}finally{try{if(session)await request(`/session/${session}`,undefined,'DELETE');}finally{driver.kill();}}
