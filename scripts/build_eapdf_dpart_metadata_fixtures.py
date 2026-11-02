"""Reviewed inherited DPart metadata constraints and permitted extension controls."""
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v

OUT = ROOT / 'tests/fixtures/eapdf/validator-dpart-metadata'
SEED = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    invalid = ('record-negative', 'record-outside', 'metadata-stream', 'dpm-stream',
               'dpm-nested-stream', 'dpm-array-stream', 'dpm-array-null',
               'dpm-value-name-utf8', 'dpm-key-space', 'dpm-nested-key-space')
    unavailable = ('dpm-key-nmtoken', 'dpm-nested-key-nmtoken')
    cases = []
    for name in invalid + unavailable + ('valid', 'permitted-values', 'dictionary-null', 'shared-cycle'):
        with pikepdf.Pdf.open(SEED) as pdf:
            root, node, nodes = v.get_dpart_nodes(pdf)
            leaf = nodes[-1]['node']
            if name == 'record-negative': root.RecordLevel = -1
            elif name == 'record-outside': root.RecordLevel = 100
            elif name == 'metadata-stream': leaf.Metadata = pdf.Root.Metadata
            elif name == 'dpm-stream': leaf.DPM.VendorPolicy = pdf.make_stream(b'policy')
            elif name == 'dpm-nested-stream': leaf.DPM.VendorPolicy = pikepdf.Dictionary(Data=pdf.make_stream(b'policy'))
            elif name == 'dpm-array-stream': leaf.DPM.VendorPolicy = pikepdf.Array([pdf.make_stream(b'policy')])
            elif name == 'dpm-array-null': leaf.DPM.VendorPolicy = pikepdf.Array([None])
            elif name == 'dpm-value-name-utf8': leaf.DPM.VendorPolicy = pikepdf.Object.parse(b'/#ff')
            elif name == 'dpm-key-space': leaf.DPM['/bad key'] = 'value'
            elif name == 'dpm-nested-key-space': leaf.DPM.VendorPolicy = pikepdf.Dictionary({'/bad key': 'value'})
            elif name == 'dpm-key-nmtoken': leaf.DPM['/1Vendor'] = 'value'
            elif name == 'dpm-nested-key-nmtoken': leaf.DPM.VendorPolicy = pikepdf.Dictionary({'/1Field': 'value'})
            elif name == 'permitted-values':
                leaf.DPM.VendorPolicy = pikepdf.Array([True, 42, Decimal('1.25'), pikepdf.String('policy'),
                    pikepdf.Name('/flag value/中文'), pikepdf.Dictionary(Enabled=False)])
            elif name == 'dictionary-null':
                leaf.DPM.VendorPolicy = pikepdf.Object.parse(b'<< /bad#20key null /Enabled true >>')
            elif name == 'shared-cycle':
                shared = pdf.make_indirect(pikepdf.Dictionary(Enabled=True))
                shared.Self = shared
                leaf.DPM.VendorA = shared
                leaf.DPM.VendorB = shared
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = ['DPM_NAME_TYPES' if name == 'dpm-value-name-utf8' else 'DPART_OBJECT_STRUCTURE'] if name in invalid else []
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='FAIL' if name in invalid else 'INCOMPLETE' if name in unavailable else 'PASS',
            expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='ISO32000-2:2020 Tables408/409 and 14.12.4.2 with industry-approved clause14 corrections accessed2026-10-20; XML1.0 Fifth Edition',
        seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),
        boundary='Dictionary nulls are absent. Shared/cyclic data containers are allowed. Name values require UTF-8, not XML spelling; NMTOKEN-only dictionary key spelling remains unavailable.',
        cases=cases), indent=2) + '\n')
    print('Built sixteen DPart metadata controls.')


if __name__ == '__main__':
    main()
