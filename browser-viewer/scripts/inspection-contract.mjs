// Canonical M1 contract. Generates JSON Schema and readonly TypeScript together.
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const ref = name => ({ $ref: `#/$defs/${name}` });
const text = { type: 'string' };
const number = { type: 'integer', minimum: 0, maximum: Number.MAX_SAFE_INTEGER };
const nullable = value => ({ anyOf: [value, { type: 'null' }] });
const array = items => ({ type: 'array', items });
const enumeration = (...values) => ({ enum: values });
const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });
const map = value => ({ type: 'object', additionalProperties: value });
const states = ['present', 'missing', 'invalid', 'ambiguous', 'unsupported', 'unavailable', 'not-evaluated'];
const observation = (value, candidate = value) => ({ oneOf: [
  object({ state: { const: 'present' }, value, basis: enumeration('observed', 'derived', 'inferred'),
    raw: array(ref('RawValue')), evidence: array(ref('ObjectLocator')), reason: nullable(text), candidates: array(candidate) }),
  object({ state: enumeration(...states.slice(1)), value: { type: 'null' }, basis: { const: 'none' },
    raw: array(ref('RawValue')), evidence: array(ref('ObjectLocator')), reason: { type: 'string', minLength: 1 }, candidates: array(candidate) }),
] });
const defs = {
  InspectionState: enumeration(...states),
  ObjectLocator: object({ documentId: text, objectNumber: nullable(number), generation: nullable(number), path: text }),
  RawValue: object({ kind: enumeration('pdf-string', 'pdf-name', 'pdf-integer', 'pdf-boolean', 'pdf-real',
    'pdf-array', 'pdf-dictionary', 'pdf-stream', 'pdf-null', 'xmp-text', 'xmp-attribute', 'xmp-structure', 'header'),
    text: nullable(text), bytesHex: nullable({ type: 'string', pattern: '^(?:[0-9a-f]{2})*$' }) }),
  TextObservation: observation(text),
  DeclaredProfile: enumeration('s', 'si', 'm', 'mi', 'c', 'ci'),
  ProfileObservation: observation(ref('DeclaredProfile'), text),
  NumberObservation: observation(number),
  LinksObservation: observation(array(text)),
  PageRange: object({ indexBase: { const: 0 }, endInclusive: { const: true }, start: number, end: number }),
  PageRangeObservation: observation(ref('PageRange')),
  InspectionDiagnostic: object({ code: text, state: ref('InspectionState'), message: text,
    evidence: array(ref('ObjectLocator')) }),
  InspectionLimits: object({ maxFileBytes: number, maxStreamBytes: number, maxDecodedBytes: number,
    maxMetadataBytes: number, maxObjects: number, maxParts: number, maxTreeDepth: number,
    maxChildDepth: number, maxChildren: number }),
  Identification: object({ pdfHeaderVersion: ref('TextObservation'), pdfCatalogVersion: ref('TextObservation'),
    effectivePdfVersion: ref('TextObservation'), pdfaPart: ref('TextObservation'), pdfaConformance: ref('TextObservation'),
    pdfaRevision: ref('TextObservation'), eapdfVersion: ref('TextObservation'), eapdfRevision: ref('TextObservation'),
    declaredProfile: ref('ProfileObservation'), validationStatus: { const: 'not-evaluated' } }),
  MetadataRecord: object({ id: text, kind: enumeration('email', 'source-asset', 'attachment'), locator: ref('ObjectLocator'),
    fields: map(ref('TextObservation')), uninspectedFields: array(text), owners: ref('LinksObservation'), files: ref('LinksObservation') }),
  DocumentPart: object({ id: text, locator: ref('ObjectLocator'), parentId: nullable(text),
    declaredParent: ref('LinksObservation'), childIds: array(text), dpm: map(ref('TextObservation')),
    startReference: ref('LinksObservation'), endReference: ref('LinksObservation'),
    pageRange: ref('PageRangeObservation'), messageIds: ref('LinksObservation') }),
  MailMessage: object({ id: text, documentId: text, metadataRecordId: text,
    mailGuid: ref('TextObservation'), internetMessageId: ref('TextObservation'),
    partIds: ref('LinksObservation'), sourceAssetIds: ref('LinksObservation'), attachmentRecordIds: array(text) }),
  AssociationOrigin: object({ kind: enumeration('catalog-af', 'object-af', 'name-tree', 'annotation'),
    locator: ref('ObjectLocator'), pageIndex: nullable(number), nameTreeKey: ref('TextObservation') }),
  EmbeddedStream: object({ locator: ref('ObjectLocator'), efKeys: array(text),
    mimeType: ref('TextObservation'), mediaTypeParameters: ref('TextObservation'),
    declaredSize: ref('NumberObservation'), declaredMd5: ref('TextObservation'),
    decodedSize: ref('NumberObservation'), computedMd5: ref('TextObservation'), sha256: ref('TextObservation'),
    extraction: ref('InspectionState') }),
  AssociatedFile: object({ id: text, locator: ref('ObjectLocator'), filename: ref('TextObservation'),
    unicodeFilename: ref('TextObservation'), afRelationship: ref('TextObservation'), origins: array(ref('AssociationOrigin')),
    embedding: ref('InspectionState'), streams: array(ref('EmbeddedStream')), roles: ref('LinksObservation'), owners: ref('LinksObservation'),
    metadataRecordIds: array(text) }),
  CollectionFolder: object({ id: text, locator: ref('ObjectLocator'), folderNumber: ref('NumberObservation'),
    name: ref('TextObservation'), parentId: nullable(text), childIds: array(text) }),
  ChildDocument: object({ fileId: text, stream: ref('ObjectLocator'), documentId: nullable(text), state: ref('InspectionState'),
    reason: nullable(text), inspection: nullable(ref('InspectionResult')) }),
  CollectionEntry: object({ fileId: text, nameTreeKey: ref('TextObservation'), folderIds: ref('LinksObservation') }),
  CollectionNode: object({ locator: ref('ObjectLocator'), view: ref('TextObservation'), schemaKeys: array(text),
    folders: array(ref('CollectionFolder')), entries: array(ref('CollectionEntry')), children: array(ref('ChildDocument')) }),
  CollectionObservation: observation(ref('CollectionNode')),
  InspectionResult: object({ schemaVersion: { const: 1 }, documentId: text,
    source: object({ sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' }, byteLength: number }),
    identification: ref('Identification'), pageCount: number, parts: array(ref('DocumentPart')),
    messages: array(ref('MailMessage')), metadataRecords: array(ref('MetadataRecord')), files: array(ref('AssociatedFile')),
    collection: ref('CollectionObservation'), diagnostics: array(ref('InspectionDiagnostic')), limits: ref('InspectionLimits'),
    coverage: object({ metadata: ref('InspectionState'), documentParts: ref('InspectionState'),
      associatedFiles: ref('InspectionState'), collection: ref('InspectionState'),
      fullEmailHeaders: { const: 'not-evaluated' }, logicalStructure: { const: 'not-evaluated' },
      sourceEmailParsing: { const: 'not-evaluated' }, contentStreamAssociations: { const: 'not-evaluated' },
      conformance: { const: 'not-evaluated' } }) }),
  ExtractionRequest: object({ documentId: text, fileId: text, stream: ref('ObjectLocator'), maxBytes: number }),
};
const schema = { $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'urn:thinkbox:eapdf:inspection:1', title: 'EA-PDF read-only inspection v1',
  description: 'Extraction observations, not validation verdicts. See docs/inspection-schema.md.',
  $ref: '#/$defs/InspectionResult', $defs: defs };

function ts(value) {
  if (value.$ref) return value.$ref.split('/').at(-1);
  if ('const' in value) return JSON.stringify(value.const);
  if (value.enum) return value.enum.map(v => JSON.stringify(v)).join(' | ');
  if (value.oneOf || value.anyOf) return '(' + (value.oneOf ?? value.anyOf).map(ts).join(' | ') + ')';
  if (value.type === 'array') return `ReadonlyArray<${ts(value.items)}>`;
  if (value.type === 'object') {
    if (!value.properties) return `Readonly<Record<string, ${ts(value.additionalProperties)}>>`;
    return '{\n' + Object.entries(value.properties).map(([key, item]) => `  readonly ${JSON.stringify(key)}: ${ts(item)};`).join('\n') + '\n}';
  }
  return value.type === 'integer' ? 'number' : value.type;
}
const types = '// Generated by scripts/inspection-contract.mjs. Edit that contract, not this file.\n'
  + '// Numeric bounds and cross-reference invariants require runtime checking; see inspection-schema.md.\n\n'
  + Object.entries(defs).map(([name, value]) => `export type ${name} = ${ts(value)};\n`).join('\n')
  + '\n/** Binary payloads are deliberately outside InspectionResult JSON. No M1 browser implementation. */\n'
  + 'export interface BoundedExtraction {\n  read(request: ExtractionRequest): Promise<Uint8Array>;\n}\n';
for (const [path, data] of [['schema/inspection-v1.schema.json', JSON.stringify(schema, null, 2) + '\n'], ['src/domain/inspection.d.ts', types]]) {
  if (process.argv.includes('--check')) {
    if (await readFile(path, 'utf8') !== data) throw new Error(`${path} differs from the canonical contract`);
  } else await writeFile(path, data);
}
console.log(process.argv.includes('--check') ? 'Inspection schema and TypeScript match the canonical contract.' : 'Wrote inspection schema and readonly TypeScript.');
