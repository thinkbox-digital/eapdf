import {execFileSync} from 'node:child_process';
import {writeFile} from 'node:fs/promises';
import {dirname,join} from 'node:path';

// Use the supported compiler CLI, including TypeScript's native implementation.
export async function checkTypes(root,source) {
 const project=join(dirname(source),'tsconfig.json');
 await writeFile(project,JSON.stringify({compilerOptions:{noEmit:true,strict:true,skipLibCheck:true,
  target:'ES2022',module:'ES2022',moduleResolution:'Bundler',types:[]},files:[source]}));
 try {execFileSync(process.execPath,[join(root,'node_modules/typescript/bin/tsc'),'--project',project,'--pretty','false'],{cwd:root,encoding:'utf8'});}
 catch(error){throw new Error(error.stdout||error.message,{cause:error});}
}
