"""Reproducible reporting-only adapter for the pinned veraPDF Arlington release."""
from pathlib import Path
import hashlib,io,json,subprocess,tempfile,urllib.request,zipfile

def patch_source(source):
    original='''if ((failedChecksNumberOfRule <= maxNumberOfDisplayedFailedChecks || maxNumberOfDisplayedFailedChecks == -1) &&
						(flavourValidator.results.size() <= MAX_CHECKS_NUMBER || failedChecksNumberOfRule <= 1))'''
    replacement='if (failedChecksNumberOfRule <= maxNumberOfDisplayedFailedChecks || maxNumberOfDisplayedFailedChecks == -1)'
    if source.count(original)!=1:raise RuntimeError('Unexpected upstream failed-check retention code')
    source=source.replace(original,replacement)
    original='this.logPassedChecks && flavourValidator.results.size() <= MAX_CHECKS_NUMBER'
    if source.count(original)!=1:raise RuntimeError('Unexpected upstream passed-check retention code')
    source=source.replace(original,'this.logPassedChecks')
    return source.replace('\tprivate static final int MAX_CHECKS_NUMBER = 10_000;\n','')

def verify(folder,pin):
    manifest=json.loads((folder/'adapter.json').read_text())
    if manifest['patched_source_sha256']!=pin['patched_source_sha256']:raise RuntimeError('Adapter source checksum mismatch')
    for name,expected in manifest['classes'].items():
        if hashlib.sha256((folder/name).read_bytes()).hexdigest()!=expected:raise RuntimeError('Adapter class checksum mismatch')
    if not manifest['classes']:raise RuntimeError('Adapter classes missing')
    return manifest

def install(jar,pin):
    folder=jar.parent.parent/'unlimited-reporting'
    if (folder/'adapter.json').exists():verify(folder,pin);return folder
    with tempfile.TemporaryDirectory(prefix='eapdf-arlington-adapter-',dir='/tmp') as temporary:
        scratch=Path(temporary)
        with urllib.request.urlopen(pin['sources_url'],timeout=120) as response:data=response.read()
        if hashlib.sha256(data).hexdigest()!=pin['sources_sha256']:raise RuntimeError('Source archive checksum mismatch')
        with zipfile.ZipFile(io.BytesIO(data)) as source:original=source.read('org/verapdf/pdfa/validation/validators/BaseValidator.java').decode()
        patched=patch_source(original)
        if hashlib.sha256(patched.encode()).hexdigest()!=pin['patched_source_sha256']:raise RuntimeError('Patched source checksum mismatch')
        java=scratch/'BaseValidator.java';java.write_text(patched)
        classes=scratch/'classes';classes.mkdir()
        subprocess.run(['javac','--release','11','-cp',str(jar),'-d',str(classes),str(java)],check=True,timeout=120)
        import shutil
        folder.mkdir(parents=True,exist_ok=True);shutil.copytree(classes,folder,dirs_exist_ok=True)
        manifest={'patched_source_sha256':pin['patched_source_sha256'],'source_archive_sha256':pin['sources_sha256'],'javac':subprocess.check_output(['javac','-version'],text=True).strip(),'classes':{str(p.relative_to(folder)):hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.rglob('*.class')}}
        (folder/'adapter.json').write_text(json.dumps(manifest,indent=2)+'\n')
    verify(folder,pin)
    return folder
