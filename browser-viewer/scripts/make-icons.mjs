// Original project icon; rasterize its checked-in SVG for extension manifests.
import {chromium} from 'playwright';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'})});
try{
 const page=await browser.newPage(),svg=await readFile('src/icons/viewer.svg','utf8');
 for(const size of [16,32,48,128]){
  await page.setViewportSize({width:size,height:size});
  await page.setContent(`<style>html,body{margin:0;background:transparent}svg{width:100vw;height:100vh;display:block}</style>${svg}`);
  await page.screenshot({path:`src/icons/icon-${size}.png`,omitBackground:true});
 }
}finally{await browser.close();}
