// Local public API extension for the pinned PDF.js source patch (not EA-PDF semantics).
import 'pdfjs-dist/types/src/display/api.js';

export type PartRef = Readonly<{ num: number; gen: number }>;
export type PartLocator = Readonly<{ ref: PartRef | null; path: string }>;
export type PartValue = Readonly<{ ref: PartRef | null }> & (
  | Readonly<{ kind: 'name' | 'string'; value: string; bytesHex: string }>
  | Readonly<{ kind: 'number'; value: number }>
  | Readonly<{ kind: 'boolean'; value: boolean }>
  | Readonly<{ kind: 'missing' | 'invalid' | 'null' | 'unavailable' | 'dictionary' | 'array' | 'stream' | 'unsupported' }>
);
export type PartReference = Readonly<{ kind: 'reference' | 'missing' | 'invalid'; ref: PartRef | null }>;
export type GenericDocumentPart = Readonly<{
  locator: PartLocator;
  parent: PartLocator | null;
  declaredParent: PartReference;
  children: readonly PartLocator[];
  dpm: Readonly<{ state: 'present' | 'partial' | 'missing' | 'invalid'; locator: PartLocator | null;
    entries: readonly Readonly<{ key: string; keyBytesHex: string; value: PartValue }>[] }>;
  start: PartReference;
  end: PartReference;
  pageRange: Readonly<{ state: 'present' | 'missing' | 'invalid'; start: number | null; end: number | null;
    indexBase: 0; endInclusive: true }>;
}>;
export type DocumentPartsInspection = Readonly<{
  apiVersion: 1;
  documentId: string;
  status: 'present' | 'missing' | 'invalid' | 'partial';
  root: PartLocator | null;
  parts: readonly GenericDocumentPart[];
  diagnostics: readonly Readonly<{ code: string; locator: PartLocator }>[];
  limits: Readonly<{ maxNodes: number; maxDepth: number; maxEntries: number; maxStringBytes: number; maxTotalStringBytes: number }>;
}>;

declare module 'pdfjs-dist/types/src/display/api.js' {
  interface PDFDocumentProxy {
    /** Detached generic DPart records; zero-based inclusive page ranges; no conformance verdict. */
    getDocumentParts(): Promise<DocumentPartsInspection>;
  }
}
