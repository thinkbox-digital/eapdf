import type {BrowserDocument} from './browser-session.js';
import type {IndexedMessage} from './message-index.js';
export type EmbeddedIndexResult={messages?:IndexedMessage[];diagnostic?:string};
/** Always use authoritative PDF metadata and ordinary child scanning. */
export async function readEmbeddedIndex(_doc:BrowserDocument,check:()=>void):Promise<EmbeddedIndexResult>{check();return {};}
export function forgetEmbeddedIndex(_doc:BrowserDocument):void {}
