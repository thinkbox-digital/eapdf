import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {checkTypes} from '../scripts/check-types.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));

test('inspection schema and readonly declarations match the canonical source', () => {
  execFileSync(process.execPath, ['scripts/inspection-contract.mjs', '--check'], { cwd: root });
});

test('all frozen records satisfy the TypeScript contract; invalid states and mutation fail', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'eapdf-m1-types-'));
  try {
    const manifest = JSON.parse(await readFile(join(root, 'tests/inspection/manifest.json'), 'utf8'));
    const records = await Promise.all(manifest.fixtures.map(c => readFile(join(root, 'tests/inspection', c.expected), 'utf8')));
    const source = `import type { InspectionResult, TextObservation } from ${JSON.stringify(join(root, 'src/domain/inspection.js'))};\n`
      + records.map((r, i) => `const fixture${i} = ${r.trim()} as const satisfies InspectionResult;`).join('\n')
      + `
declare const inspection: InspectionResult;
// @ts-expect-error The model is readonly.
inspection.messages.push(inspection.messages[0]);
// @ts-expect-error Inspection cannot assert a validation verdict.
const verdict: InspectionResult['identification']['validationStatus'] = 'PASS';
// @ts-expect-error Missing data cannot carry a present value.
const missing: TextObservation = { state: 'missing', value: 'guess', basis: 'none', raw: [], evidence: [], reason: 'Absent', candidates: [] };
// @ts-expect-error An observation basis cannot invent a new state.
const unknown: TextObservation['state'] = 'probably-valid';
`;
    const path = join(temporary, 'contract.ts');
    await writeFile(path, source);
    await checkTypes(root, path);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
