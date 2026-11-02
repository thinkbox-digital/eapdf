// Original synthetic content, licensed under this project's Apache-2.0 license.
// This writes one fixed test PDF; it is not an alternate PDF parser or runtime dependency.
import { writeFile } from 'node:fs/promises';
const streams = [
  '0.1 0.35 0.65 rg 40 170 220 50 re f\n0 g BT /F1 20 Tf 40 130 Td (Ordinary PDF - page 1) Tj ET\n',
  '0.7 0.2 0.1 rg 40 170 100 50 re f\n0 g BT /F1 20 Tf 40 130 Td (Ordinary PDF - page 2) Tj ET\n',
];
const objects = [
  '<< /Type /Catalog /Pages 2 0 R >>',
  '<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>',
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 260] /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R >>',
  `<< /Length ${Buffer.byteLength(streams[0])} >>\nstream\n${streams[0]}endstream`,
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 260] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>',
  `<< /Length ${Buffer.byteLength(streams[1])} >>\nstream\n${streams[1]}endstream`,
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
];
let data = '%PDF-1.4\n';
const offsets = [0];
objects.forEach((object, index) => {
  offsets.push(Buffer.byteLength(data));
  data += `${index + 1} 0 obj\n${object}\nendobj\n`;
});
const start = Buffer.byteLength(data);
data += `xref\n0 ${offsets.length}\n0000000000 65535 f \n`;
for (const offset of offsets.slice(1)) data += `${String(offset).padStart(10, '0')} 00000 n \n`;
data += `trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`;
await writeFile(new URL('../tests/fixtures/ordinary.pdf', import.meta.url), data);
