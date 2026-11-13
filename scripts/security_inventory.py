"""Make CycloneDX inventories from resolved Maven lists and the Python environment.
No dependency resolution, source uploads or network calls occur in this script.
"""
import argparse
import hashlib
import importlib.metadata as metadata
import json
from pathlib import Path
from urllib.parse import quote
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from packaging.requirements import Requirement
from packaging.utils import canonicalize_name

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def pom_licenses(path, seen=None):
    seen = set() if seen is None else seen
    if not path.is_file() or path in seen:
        return []
    seen.add(path)
    root = ET.parse(path).getroot()
    licenses = []
    for item in root.findall('m:licenses/m:license', NS):
        value = {'name': item.findtext('m:name', namespaces=NS) or 'Unspecified license'}
        url = item.findtext('m:url', namespaces=NS)
        if url and not '${' in url: value['url'] = url
        licenses.append({'license': value})
    if licenses: return licenses
    parent = root.find('m:parent', NS)
    if parent is not None:
        parts = [parent.findtext('m:'+key, namespaces=NS) for key in ('groupId', 'artifactId', 'version')]
        if all(parts) and not any('${' in p for p in parts):
            group, artifact, version = parts
            return pom_licenses(Path.home()/'.m2/repository'/group.replace('.', '/')/artifact/version/(artifact+'-'+version+'.pom'), seen)
    return []


def maven_components(path):
    result = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if ':jar:' not in line or ':/' not in line: continue
        coordinate, filename = line.split(':/', 1)
        fields = coordinate.split(':')
        group, artifact, _, *tail = fields
        classifier = tail[0] if len(tail) == 3 else None
        version = tail[-2]
        jar = Path('/'+filename)
        pom = jar.parent/(artifact+'-'+version+'.pom')
        purl = 'pkg:maven/'+quote(group,safe='')+'/'+quote(artifact,safe='')+'@'+quote(version,safe='')
        if classifier: purl += '?classifier='+quote(classifier,safe='')
        value = {'type':'library','group':group,'name':artifact,'version':version,'purl':purl,'bom-ref':purl,
                 'hashes':[{'alg':'SHA-256','content':sha(jar)}],
                 'properties':[{'name':'eapdf:hash-scope','value':'resolved dependency JAR before application shading and local class overrides'}]}
        licenses = pom_licenses(pom)
        if not licenses:
            with ZipFile(jar) as archive:
                for member in archive.namelist():
                    if member.upper() not in ('META-INF/LICENSE', 'META-INF/LICENSE.TXT', 'META-INF/LICENSE.MD', 'LICENSE', 'LICENSE.TXT'): continue
                    text = archive.read(member).decode('utf-8', 'replace')
                    expression = 'MIT' if text.lstrip().startswith('MIT License') else 'Apache-2.0' if 'Apache License' in text and 'Version 2.0' in text else None
                    if expression:
                        licenses = [{'expression':expression}]
                        value['properties'].append({'name':'eapdf:license-evidence','value':jar.name+'!/'+member})
                        break
        if licenses: value['licenses'] = licenses
        else: value['properties'].append({'name':'eapdf:license-review','value':'No resolved POM license; inspect packaged notices and upstream source'})
        result.append(value)
    if not result: raise ValueError('No resolved dependencies in '+str(path))
    return sorted(result, key=lambda c:c['purl'])


def python_components():
    pending = [Requirement(line) for line in (ROOT/'requirements-validator.txt').read_text().splitlines() if line.strip() and not line.startswith('#')]
    components = {}
    while pending:
        req = pending.pop()
        if req.marker and not req.marker.evaluate({'extra':''}): continue
        name = canonicalize_name(req.name)
        if name in components: continue
        dist = metadata.distribution(req.name)
        if req.specifier and not req.specifier.contains(dist.version): raise ValueError(str(req)+' does not match '+dist.version)
        purl = 'pkg:pypi/'+name+'@'+dist.version
        value = {'type':'library','name':name,'version':dist.version,'purl':purl,'bom-ref':purl}
        license_expression = dist.metadata.get('License-Expression')
        if license_expression: value['licenses'] = [{'expression':license_expression}]
        else:
            names = [v.rsplit(' :: ',1)[-1] for v in dist.metadata.get_all('Classifier',[]) if v.startswith('License :: OSI Approved :: ')]
            license_name = dist.metadata.get('License','').strip()
            if names: value['licenses']=[{'license':{'name':name}} for name in names]
            elif license_name: value['licenses']=[{'license':{'name':license_name}}]
            else: value['properties']=[{'name':'eapdf:license-review','value':'Installed metadata does not declare a license'}]
        # RECORD identifies the installed wheel, not a claim that pip artifacts are locked by hashes.
        record = next((dist.locate_file(f) for f in dist.files or [] if str(f).endswith('.dist-info/RECORD')),None)
        if record: value.setdefault('properties',[]).append({'name':'eapdf:installed-record-sha256','value':sha(record)})
        components[name] = value
        pending += [Requirement(r) for r in dist.requires or []]
    return sorted(components.values(),key=lambda c:c['name'])


def write_bom(output,name,components,artifacts=()):
    value = {'bomFormat':'CycloneDX','specVersion':'1.5','version':1,
        'metadata':{'component':{'type':'application','name':name,'version':(ROOT/'VERSION').read_text().strip()},
                    'properties':[{'name':'eapdf:scope','value':'Resolved runtime dependency inventory; licensing/advisory review is separate. Local patches are recorded by source and artifact hashes.'}]},
        'components':list(components)}
    for artifact in artifacts:
        value['components'].append({'type':'file','name':artifact.name,'bom-ref':'artifact:'+artifact.name,
            'hashes':[{'alg':'SHA-256','content':sha(artifact)}]})
    output.write_text(json.dumps(value,indent=2)+'\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--creator-dependencies',type=Path,required=True)
    parser.add_argument('--validator-dependencies',type=Path,required=True)
    parser.add_argument('--creator-jar',type=Path,required=True)
    parser.add_argument('--validator-jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    creator = maven_components(args.creator_dependencies); validator = maven_components(args.validator_dependencies); python = python_components()
    write_bom(args.output/'creator.cdx.json','EA-PDF creator',creator,[args.creator_jar])
    write_bom(args.output/'java-validator.cdx.json','EA-PDF Java validator',validator,[args.validator_jar])
    write_bom(args.output/'python-validator.cdx.json','EA-PDF Python CLI validator',python)
    (args.output/'python-runtime-versions.txt').write_text('# Installed runtime closure; platform-specific snapshot, no artifact-hash lock.\n'+''.join(c['name']+'=='+c['version']+'\n' for c in python))
    rows = ['# Runtime dependency licenses','', 'Generated from resolved Maven POMs and installed Python distribution metadata. Review packaged notices, local patches and corresponding-source obligations before distribution.','', '| Runtime | Component | Version | Declared license |','| --- | --- | --- | --- |']
    for name,components in [('Creator',creator),('Java validator',validator),('Python CLI',python)]:
        for c in components:
            declared='; '.join(item.get('expression') or item['license'].get('name','') for item in c.get('licenses',[])) or 'REVIEW REQUIRED'
            rows.append('| '+name+' | '+c.get('group','')+(':' if c.get('group') else '')+c['name']+' | '+c['version']+' | '+declared.replace('|','/').replace('\n',' ')+' |')
    (args.output/'DEPENDENCY_LICENSES.md').write_text('\n'.join(rows)+'\n')
    print(json.dumps({'creator':len(creator),'java_validator':len(validator),'python_validator':len(python)}))


if __name__=='__main__': main()
