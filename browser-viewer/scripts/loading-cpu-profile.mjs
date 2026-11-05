import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdir,writeFile} from 'node:fs/promises';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
 const cdp=await context.newCDPSession(page),pending=new Map(),sessions=[];let seq=0;
 cdp.on('Target.receivedMessageFromTarget',({message})=>{const m=JSON.parse(message),p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(m.error):p.resolve(m.result);}});
 const send=(sessionId,method)=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});cdp.send('Target.sendMessageToTarget',{sessionId,message:JSON.stringify({id,method})}).catch(reject);});
 cdp.on('Target.attachedToTarget',({sessionId,targetInfo})=>{if(targetInfo.type==='worker'){sessions.push(sessionId);void send(sessionId,'Profiler.enable').then(()=>send(sessionId,'Profiler.start')).catch(()=>{});}});
 await cdp.send('Target.setAutoAttach',{autoAttach:true,waitForDebuggerOnStart:false,flatten:false});
 await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js');window.profileSession=new BrowserSession();const i=document.createElement('input');i.id='profile-file';i.type='file';document.body.append(i);});
 await page.locator('#profile-file').setInputFiles(resolve('../out/pst/pst_21_04+April+2003+Public+2/pst_21_04+April+2003+Public+2_1c.pdf'));
 await page.evaluate(async()=>{window.profileDoc=await window.profileSession.open(document.querySelector('#profile-file').files[0],'April.pdf');});
 await mkdir('test-results',{recursive:true});
 for(const [index,session] of sessions.entries()){
  const {profile}=await send(session,'Profiler.stop');await writeFile(`test-results/loading-worker-${index}.cpuprofile`,JSON.stringify(profile));
  const nodes=new Map(profile.nodes.map(n=>[n.id,n])),totals=new Map();
  profile.samples?.forEach((id,i)=>{const node=nodes.get(id),frame=node.callFrame,key=`${frame.functionName||'(anonymous)'}:${frame.lineNumber+1}`;totals.set(key,(totals.get(key)??0)+(profile.timeDeltas?.[i]??0));});
  console.log(JSON.stringify([...totals].sort((a,b)=>b[1]-a[1]).slice(0,25)));
 }
 await page.evaluate(()=>window.profileSession.close());
}finally{await context.close();}
