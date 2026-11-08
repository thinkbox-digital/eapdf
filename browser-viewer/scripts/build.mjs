import { cp, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { extensionVersion } from './extension-version.mjs';
import { makeSbom } from './sbom.mjs';
import { verifyPatchedPdfjs } from './pdfjs-patch.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
process.chdir(root);
const temporary = await mkdtemp(join(tmpdir(), 'eapdf-viewer-build-'));
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const base = await json('src/extension/manifest.json');
const dependency = await json('node_modules/pdfjs-dist/package.json');
const project = await json('package.json');
if (dependency.version !== project.dependencies['pdfjs-dist']) throw new Error('PDF.js version mismatch');

async function hashes(directory, prefix = '') {
  const result = {};
  for (const item of (await readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
    const name = prefix + item.name;
    if (item.isDirectory()) Object.assign(result, await hashes(join(directory, item.name), name + '/'));
    else result[name] = createHash('sha256').update(await readFile(join(directory, item.name))).digest('hex');
  }
  return result;
}

try {
  execFileSync(process.execPath, ['scripts/licenses.mjs'], { stdio: 'inherit' });
  const patch = await verifyPatchedPdfjs();
  execFileSync(process.execPath, ['node_modules/typescript/bin/tsc', '--outDir', temporary], { stdio: 'inherit' });
  execFileSync('node_modules/.bin/esbuild',['src/interpret-worker.ts','--bundle','--format=esm','--platform=browser','--outfile='+join(temporary,'interpret-worker.js')],{stdio:'inherit'});
  await rm('dist', { recursive: true, force: true });
  for (const browser of ['chromium', 'firefox']) {
    const directory = join('dist', browser);
    await mkdir(join(directory, 'vendor/pdfjs'), { recursive: true });
    await cp(temporary, directory, { recursive: true });
    await cp(join(temporary, 'extension/background.js'), join(directory, 'background.js'));
    await rm(join(directory, 'extension'), { recursive: true });
    await cp(join('src','icons'),join(directory,'icons'),{recursive:true});
    await mkdir(join(directory,'assets'),{recursive:true});
    await cp('../assets/thinkbox_logo.jpg',join(directory,'assets','thinkbox_logo.jpg'));
    for (const file of ['viewer.html', 'viewer.css']) await cp(join('src', file), join(directory, file));
    await writeFile(join(directory,'viewer.html'),(await readFile('src/viewer.html','utf8')).replaceAll('__VIEWER_VERSION__',project.version));
    for (const file of ['pdf.mjs', 'pdf.worker.mjs']) {
      await cp(join('vendor/pdfjs-patched', file), join(directory, 'vendor/pdfjs', file));
    }
    for (const name of ['cmaps', 'standard_fonts', 'wasm', 'iccs', 'LICENSE']) {
      await cp(join('node_modules/pdfjs-dist', name), join(directory, 'vendor/pdfjs', name), { recursive: true });
    }
    await cp('node_modules/pdfjs-dist/web/pdf_viewer.mjs',join(directory,'vendor/pdfjs/pdf_viewer.mjs'));
    // PDF scripting is never enabled; do not ship its unused JavaScript interpreter.
    for (const name of ['quickjs-eval.js', 'quickjs-eval.wasm', 'LICENSE_QUICKJS', 'LICENSE_PDFJS_QUICKJS'])
      await rm(join(directory,'vendor/pdfjs/wasm',name),{force:true});
    await cp('node_modules/@xmldom/xmldom/LICENSE',join(directory,'xmldom-LICENSE'));
    await cp('LICENSE', join(directory, 'LICENSE'));
    await cp('THIRD_PARTY_NOTICES.md', join(directory, 'THIRD_PARTY_NOTICES.md'));
    await cp('vendor/sources', join(directory, 'license-sources'), { recursive: true });
    const manifest = structuredClone(base);
    manifest.version = extensionVersion(project.version);
    if (browser === 'chromium') {
      manifest.background = { service_worker: 'background.js', type: 'module' };
      manifest.minimum_chrome_version = '156';
    } else {
      manifest.background = { scripts: ['background.js'], type: 'module' };
      manifest.browser_specific_settings = { gecko: {
        id: 'eapdf-m0-viewer@thinkbox.local', strict_min_version: '158.0',
        data_collection_permissions: { required: ['none'] },
      } };
    }
    await writeFile(join(directory, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    await writeFile(join(directory, 'SBOM.cdx.json'), JSON.stringify(await makeSbom(new URL('../', import.meta.url)), null, 2) + '\n');
    await writeFile(join(directory, 'build-info.json'), JSON.stringify({
      project: project.version, pdfjs: dependency.version,
      pdfjsCommit: patch.upstreamCommit,
      pdfjsPatch: patch.localInputs['vendor/pdfjs-patches/0001-document-parts.patch'],
      pdfjsPatches: Object.fromEntries((patch.patches ?? []).map(name => [name, patch.localInputs[name]])),
      node: process.versions.node, files: await hashes(directory),
    }, null, 2) + '\n');
    console.log(`Built ${directory} with PDF.js ${dependency.version}`);
  }
} finally {
  await rm(temporary, { recursive: true, force: true });
}
