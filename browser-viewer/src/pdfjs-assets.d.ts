import 'pdfjs-dist/types/src/display/api.js';
import type {PortfolioData} from './collection-data.js';
import type { PartLocator } from './pdfjs-document-parts.js';
export type AssetValue = Readonly<{ kind: string; value?: string | number | boolean; bytesHex?: string }>;
export type AssetOrigin = Readonly<{ kind: 'name-tree' | 'catalog-af' | 'object-af' | 'annotation'; locator: PartLocator; pageIndex: number | null; nameTreeKey: AssetValue }>;
export type AssetStream = Readonly<{ locator: PartLocator; efKeys: readonly string[]; state: 'present' | 'invalid'; subtype: AssetValue;
  parameters: AssetValue; declaredSize: AssetValue; declaredChecksum: AssetValue }>;
export type AssetFile = Readonly<{ locator: PartLocator; filename: AssetValue; unicodeFilename: AssetValue; description: AssetValue;
  relationship: AssetValue; embedding: 'present' | 'missing' | 'invalid'; streams: readonly AssetStream[]; origins: readonly AssetOrigin[] }>;
export type AssetMetadata = Readonly<{ locator: PartLocator; owners: readonly PartLocator[]; state: 'present' | 'unavailable'; text: string | null }>;
export type AssetFolder = Readonly<{ locator: PartLocator; parent: string | null; children: readonly string[]; number: AssetValue; name: AssetValue }>;
export type AssetLimits = Readonly<{ maxObjects: number; maxDepth: number; maxFiles: number; maxMetadataBytes: number; maxStreamBytes: number }>;
export type DocumentAssets = Readonly<{ apiVersion: 1; documentId: string; files: readonly AssetFile[]; metadata: readonly AssetMetadata[];
  catalogMetadata: PartLocator | null; catalogVersion: AssetValue;
  collection: Readonly<{ state: 'present' | 'missing' | 'invalid'; locator: PartLocator | null; view: AssetValue; schemaKeys: readonly string[]; folders: readonly AssetFolder[] }>;
  diagnostics: readonly Readonly<{ code: string; locator: PartLocator }>[]; limits: AssetLimits }>;
export type FileBytesRequest = Readonly<{ documentId: string; fileId: string; streamId: string; maxBytes: number }>;
declare module 'pdfjs-dist/types/src/display/api.js' {
  interface PDFDocumentProxy {
    extractPages(pageInfos: Array<{document?:Uint8Array|null;includePages?:Array<number|number[]>}>, copyLevels: null, options: {pageOnly:true}): Promise<Uint8Array|null>;
    getDocumentAssets(options: {embeddedTarget:{page:number;annotation:number|string}}): Promise<{target:string}>;
    getDocumentAssets(options: {folderThumbnail:string}): Promise<{thumbnail:{width:number;height:number;data:Uint8Array}}>;
    getDocumentAssets(options: {presentationOnly:true}): Promise<{presentation:PortfolioData}>;
    getDocumentAssets(options?: Partial<AssetLimits>): Promise<DocumentAssets>;
    getAssociatedFileBytes(request: {documentId:string;metadataId:string;maxBytes:number}): Promise<Uint8Array>;
    getAssociatedFileBytes(request: FileBytesRequest): Promise<Uint8Array>;
  }
}
