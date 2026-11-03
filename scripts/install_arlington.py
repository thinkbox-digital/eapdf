#!/usr/bin/env python3
"""Install the pinned official Arlington Java CLI; validation itself stays offline."""
from pathlib import Path
import hashlib,json,subprocess,tempfile,urllib.request,zipfile
from xml.sax.saxutils import escape
ROOT=Path(__file__).resolve().parents[1]
def install():
    pin=json.loads((ROOT/'config/arlington.json').read_text())
    destination=ROOT/'dist/tools'/('arlington-'+pin['version'])
    jar=destination/'bin'/('cli-arlington-'+pin['version']+'.jar')
    if not jar.exists():
        with tempfile.TemporaryDirectory(prefix='eapdf-arlington-install-',dir='/tmp') as scratch:
            folder=Path(scratch);archive=folder/'installer.zip'
            with urllib.request.urlopen(pin['installer_url'],timeout=120) as response,archive.open('wb') as output:
                import shutil
                shutil.copyfileobj(response,output)
            if hashlib.sha256(archive.read_bytes()).hexdigest()!=pin['installer_sha256']:raise RuntimeError('Installer checksum mismatch')
            with zipfile.ZipFile(archive) as source:
                member='verapdf-arlington-'+pin['version']+'/verapdf-izpack-installer-'+pin['version']+'.jar'
                installer=folder/'installer.jar';installer.write_bytes(source.read(member))
            options=folder/'install.xml'
            options.write_text('''<AutomatedInstallation langpack="eng">
<com.izforge.izpack.panels.htmlhello.HTMLHelloPanel id="welcome"/>
<com.izforge.izpack.panels.target.TargetPanel id="install_dir"><installpath>'''+escape(str(destination))+'''</installpath></com.izforge.izpack.panels.target.TargetPanel>
<com.izforge.izpack.panels.packs.PacksPanel id="sdk_pack_select">
<pack index="0" name="veraPDF GUI" selected="false"/>
<pack index="1" name="veraPDF CLI" selected="true"/>
<pack index="2" name="veraPDF Documentation" selected="false"/>
<pack index="3" name="veraPDF Sample Plugins" selected="false"/>
</com.izforge.izpack.panels.packs.PacksPanel>
<com.izforge.izpack.panels.install.InstallPanel id="install"/>
<com.izforge.izpack.panels.finish.FinishPanel id="finish"/>
</AutomatedInstallation>''')
            subprocess.run(['java','-jar',str(installer),str(options)],check=True,timeout=180)
    if hashlib.sha256(jar.read_bytes()).hexdigest()!=pin['jar_sha256']:raise RuntimeError('Installed Arlington JAR checksum mismatch')
    with zipfile.ZipFile(jar) as source:
        if hashlib.sha256(source.read('org/verapdf/pdfa/validation/ARLINGTON2-0.xml')).hexdigest()!=pin['pdf20_profile_sha256']:raise RuntimeError('Bundled Arlington model checksum mismatch')
    from arlington_adapter import install as install_adapter
    install_adapter(jar,pin["report_adapter"])
    from arlington_semantics import install as install_semantics
    install_semantics(jar,pin["model_adapter"])
    print(jar)
    return jar
if __name__=='__main__':install()
