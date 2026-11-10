import {spawnSync} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const groups=[
 ['file-format-boundaries',['--test','tests/collection-names.test.mjs','tests/metadata-export.test.mjs','tests/metadata-csv.test.mjs']],
 ['required-interface',['scripts/section8-browser.mjs']],
 ['metadata-extraction',['scripts/metadata-export-smoke.mjs']],
 ['embedded-files',['scripts/assets-smoke.mjs']],
 ['rendering-compatibility',['scripts/pdfjs-compat-smoke.mjs']],
];
const results=[];
for(const [group,args]of groups){console.log(`Section 8: ${group}`);const result=spawnSync(process.execPath,args,{stdio:'inherit',env:{...process.env,CHROMIUM_PATH:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome'}});results.push({group,status:result.status===0?'PASS':'FAIL'});}
await mkdir('test-results',{recursive:true});await writeFile('test-results/section8-acceptance.json',JSON.stringify({scope:'Targeted Section 8 acceptance; not full PDF/A renderer certification',results},null,2)+'\n');
if(results.some(r=>r.status!=='PASS'))process.exitCode=1;
