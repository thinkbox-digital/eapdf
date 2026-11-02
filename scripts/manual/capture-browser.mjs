// Documentation captures: actual extension and local web app, using fictional email.
import {chromium} from '../../browser-viewer/node_modules/playwright/index.mjs';
import {spawn} from 'node:child_process';
import {readFile,writeFile,mkdir,rm,open} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'), work=process.env.MANUAL_WORK??'/tmp/eapdf-user-manual', screenshots=resolve(root,'docs/manual/screenshots');
const extension=resolve(root,'browser-viewer/dist/chromium');
await mkdir(screenshots,{recursive:true});
if(process.env.MANUAL_SKIP_VIEWER!=='1') {
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1200,height:850},executablePath:process.env.CHROMIUM_PATH,args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try {
 await context.setOffline(true);
 const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
 const page=await context.newPage();await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const ready=()=>page.waitForFunction(()=>!document.querySelector('#canvas').hidden&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 await page.locator('#file').setInputFiles(work+'/manual_1m.pdf');await ready();
 await page.locator('[data-message-id]').first().click();await ready();
 await page.locator('#zoom').selectOption('fit-width');await ready();
 await page.screenshot({path:resolve(screenshots,'viewer-email.png')});
 await page.locator('.file-card').filter({has:page.locator('.file-name',{hasText:'agenda.txt'})}).locator('[data-view-file-id]').click();
 await page.waitForSelector('#attachment-text:visible');
 await page.screenshot({path:resolve(screenshots,'viewer-attachment.png')});
 await page.keyboard.press('Escape');
 await page.locator('#file').setInputFiles(work+'/manual_1c.pdf');await ready();
 await page.screenshot({path:resolve(screenshots,'viewer-collection.png')});
 const csv=page.waitForEvent('download');await page.locator('#export-csv').click();const download=await csv;await download.saveAs(work+'/metadata.csv');
 assert.match(await readFile(work+'/metadata.csv','utf8'),/October planning meeting/);
} finally {await context.close();}
}
if(process.env.MANUAL_VIEWER_ONLY==='1')process.exit(0);
// Keep the real application behavior, with an ownership receipt for cleanup.
const creatorJar=process.env.MANUAL_CREATOR_JAR??resolve(root,'dist/emailtopdfa.jar');
const bootstrap=`import json,os\nfrom pathlib import Path\nimport validator_web as web\nweb.JAR_PATH=Path(${JSON.stringify(creatorJar)})\noriginal=web.CapabilityRegistry.__setitem__\ndef tracked(self,token,path):\n original(self,token,path)\n with open(${JSON.stringify(work+'/web-ownership.jsonl')},'a') as receipt: receipt.write(json.dumps({'token':token,'kind':self.kind,'path':str(path)})+'\\n')\nweb.CapabilityRegistry.__setitem__=tracked\nweb.app.run(host='127.0.0.1',port=int(os.environ['PORT']),use_reloader=False)\n`;
const {createServer}=await import('node:net');const probe=createServer();await new Promise(done=>probe.listen(0,'127.0.0.1',done));const port=probe.address().port;await new Promise(done=>probe.close(done));
const log=await open(work+'/manual-web-server.log','w');
const server=spawn(process.env.MANUAL_PYTHON??'python3',['-B','-c',bootstrap],{cwd:root,env:{...process.env,PYTHONPATH:root,PORT:String(port),EAPDF_CONFIG:work+'/config.json',VALIDATOR_JOB_MEMORY_MB:'512',VALIDATOR_JOB_TIMEOUT:'120'},stdio:['ignore',log.fd,log.fd]});
let browser;
try {
 const url=`http://127.0.0.1:${port}`;let connected=false;
 for(let count=0;count<100;count++){try{if((await fetch(url)).ok){connected=true;break;}}catch{}await new Promise(done=>setTimeout(done,100));}
 assert.ok(connected,'Local workbench did not start');
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH});const page=await browser.newPage({viewport:{width:1100,height:900}});
 await page.goto(url);await page.locator('.hero').screenshot({path:resolve(screenshots,'web-validator-ready.png')});
 await page.locator('input[name=pdfs]').setInputFiles(work+'/manual_1m.pdf');
 await Promise.all([page.waitForNavigation({timeout:150000}),page.getByRole('button',{name:'Validate PDFs',exact:true}).click({timeout:150000})]);
 await writeFile(work+'/web-validation-result.html',await page.content());
 await page.screenshot({path:resolve(screenshots,'web-validator-result.png')});
 console.log('Web result text:',(await page.locator('.results').innerText()).slice(0,300));
 await page.goto(url+'/generate');await page.locator('#inputs').setInputFiles([work+'/emails/20261028_1.eml',work+'/emails/20261028_2.eml']);await page.selectOption('#profile','1m');
 await page.screenshot({path:resolve(screenshots,'web-creator.png'),clip:{x:0,y:0,width:1100,height:550}});
} finally {
 if(browser)await browser.close();server.kill('SIGTERM');await new Promise(done=>server.once('exit',done));await log.close();
 const rows=(await readFile(work+'/web-ownership.jsonl','utf8').catch(()=>'' )).trim().split('\n').filter(Boolean).map(JSON.parse);
 for(const row of rows){
  const path=resolve(row.path);if(row.kind==='job'&&(path.startsWith(root+'/.validator_web_tmp/')||path.startsWith(root+'/.generator_web_tmp/')||path.startsWith(root+'/out/.web-jobs/')))await rm(path,{recursive:true,force:true});
  if(/^[0-9a-f]{32}$/.test(row.token))await rm(resolve(root,'.validator_web_tmp/registry',row.token+'.json'),{force:true});
 }
}
console.log('Captured viewer/web screenshots and removed only this capture’s web jobs.');
