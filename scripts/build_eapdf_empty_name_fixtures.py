"""DPM entry presence, valid empty Names and independent media syntax."""
import hashlib
import json
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements

OUT = ROOT / 'tests/fixtures/eapdf/validator-empty-dpm-names'
OPAQUE = ROOT / 'tests/fixtures/eapdf/validator-content-group-types/opaque-group-types.pdf'
SUBTYPE = ROOT / 'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf'


def null_field(leaf, key):
    fields = pikepdf.Object.parse(('<< /' + key + ' null >>').encode('ascii'))
    for name, value in leaf.DPM.items():
        if name != '/' + key:
            fields[name] = value
    leaf.DPM = fields


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for name in ('empty-extension-type', 'empty-optional-subtype', 'empty-required-subtype',
                 'missing-content-type', 'null-content-type', 'missing-required-subtype',
                 'null-required-subtype', 'omitted-optional-subtype'):
        type_case = name in ('empty-extension-type', 'missing-content-type', 'null-content-type')
        with pikepdf.open(OPAQUE if type_case else SUBTYPE) as pdf:
            elements = structure_elements(pdf)
            _, _, nodes = v.get_dpart_nodes(pdf)
            if name == 'empty-extension-type':
                for item in nodes:
                    if not item['child_count'] and str(item['dpm'].get('Mail_ContentSetType')) == '/ Vendor  rendering ':
                        item['node'].DPM.Mail_ContentSetType = pikepdf.Name('/')
                for element in elements:
                    if element.get('/S') in (pikepdf.Name('/Mail_ContentGroup'), pikepdf.Name('/Mail_ContentSet')):
                        for prop in element.A.P:
                            if str(prop.N) == 'Mail_ContentSetType' and str(prop.V) == ' Vendor  rendering ':
                                prop.V = ''
            elif type_case:
                leaf = next(item['node'] for item in nodes if not item['child_count']
                            and str(item['dpm'].get('Mail_ContentSetType')) == '/ Vendor  rendering ')
                if name == 'null-content-type': null_field(leaf, 'Mail_ContentSetType')
                else: del leaf.DPM.Mail_ContentSetType
            else:
                wanted = 'FrontMatter' if name in ('empty-optional-subtype', 'omitted-optional-subtype') else 'BodyRendering'
                item = next(i for i in nodes if not i['child_count']
                            and i['dpm'].get('Mail_ContentSetType') == pikepdf.Name('/' + wanted))
                content = next(e for e in elements if e.get('/S') == pikepdf.Name('/Mail_ContentSet')
                               and e.get('/Pg') is not None and e.Pg.objgen == item['start'].objgen)
                if name.startswith('empty-'):
                    item['node'].DPM.Mail_Subtype = pikepdf.Name('/')
                    next(p for p in content.A.P if str(p.N) == 'Mail_Subtype').V = ''
                else:
                    if name == 'null-required-subtype': null_field(item['node'], 'Mail_Subtype')
                    else: del item['node'].DPM.Mail_Subtype
                    content.A.P = pikepdf.Array([p for p in content.A.P if str(p.N) != 'Mail_Subtype'])
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        legal = name in ('empty-extension-type', 'omitted-optional-subtype')
        failure = ('DPM_CONTENTSETTYPE_PRESENT' if type_case else 'DPM_SUBTYPE_MEDIA_TYPE_NAME'
                   if name.startswith('empty-') else 'DPM_SUBTYPE_REQUIRED')
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='PASS' if legal else 'FAIL', expected_failures=[] if legal else [failure]))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='ISO32000-2:2020 7.3.5 empty Name; EA-PDF9.6 Table8 required entries/open types/media subtype syntax',
        source_files={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (OPAQUE, SUBTYPE)},
        boundary='An actual empty Name is present and has valid Name syntax. Open extension type names have no added nonempty restriction; an empty subtype still fails its media syntax. Null dictionary values are absent. These are internal declared metadata controls, not source/vendor format-fidelity proof.',
        cases=cases), indent=2) + '\n')
    print('Built eight DPM presence/empty-name controls.')


if __name__ == '__main__':
    main()
