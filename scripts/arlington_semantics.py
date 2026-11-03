"""Reproducible fixes for binary checksum length and number-tree parent context.

The pinned Arlington rules are unchanged. These correct the values supplied to
those rules by the released veraPDF model implementation.
"""
from pathlib import Path
import hashlib, io, json, shutil, subprocess, tempfile, urllib.request, zipfile

NAMES = ('GFAEmbeddedFileParameter', 'GFAAFEmbeddedFileParameter', 'GFANumberTreeNodesArrayEntry')

def patch_source(name, source):
    if name in NAMES[:2]:
        old = 'return (long) CheckSum.getString().length();'
        new = 'return (long) ((COSString) CheckSum.getDirectBase()).get().length;'
    elif name == NAMES[2]:
        old = 'new GFANumberTreeNode((COSDictionary)object.getDirectBase(), this.parentObject, keyName)'
        new = 'new GFANumberTreeNode((COSDictionary)object.getDirectBase(), this.parentParentObject, keyName)'
    else:
        raise ValueError(name)
    if source.count(old) != 1:
        raise RuntimeError('Unexpected upstream implementation: ' + name)
    return source.replace(old, new)

def verify(folder, pin):
    manifest = json.loads((folder/'adapter.json').read_text())
    if manifest['patched_sources'] != pin['patched_sources']:
        raise RuntimeError('Semantic adapter source mismatch')
    if not manifest['classes']:
        raise RuntimeError('Semantic adapter classes missing')
    for name, expected in manifest['classes'].items():
        if hashlib.sha256((folder/name).read_bytes()).hexdigest() != expected:
            raise RuntimeError('Semantic adapter class mismatch')
    return manifest

def install(jar, pin):
    folder = jar.parent.parent/'model-corrections'
    if (folder/'adapter.json').exists():
        verify(folder,pin)
        return folder
    with tempfile.TemporaryDirectory(prefix='eapdf-arlington-semantics-',dir='/tmp') as temporary:
        scratch = Path(temporary)
        with urllib.request.urlopen(pin['sources_url'],timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != pin['sources_sha256']:
            raise RuntimeError('Model source archive mismatch')
        sources = []
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for name in NAMES:
                source = patch_source(name,archive.read('org/verapdf/gf/model/impl/arlington/'+name+'.java').decode())
                if hashlib.sha256(source.encode()).hexdigest() != pin['patched_sources'][name]:
                    raise RuntimeError('Patched model source mismatch: '+name)
                path = scratch/(name+'.java');path.write_text(source);sources.append(str(path))
        classes = scratch/'classes';classes.mkdir()
        subprocess.run(['javac','--release','11','-cp',str(jar),'-d',str(classes),*sources],check=True,timeout=120)
        folder.mkdir(parents=True,exist_ok=True);shutil.copytree(classes,folder,dirs_exist_ok=True)
        manifest = {'patched_sources':pin['patched_sources'],'source_archive_sha256':pin['sources_sha256'],'javac':subprocess.check_output(['javac','-version'],text=True).strip(),'classes':{str(p.relative_to(folder)):hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.rglob('*.class')}}
        (folder/'adapter.json').write_text(json.dumps(manifest,indent=2)+'\n')
    verify(folder,pin)
    return folder
