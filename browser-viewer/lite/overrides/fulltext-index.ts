import type {BrowserDocument,BrowserSession} from './browser-session.js';
import type {CollectionMatch,SearchStep} from './collection-search.js';
/** No embedded index is read, verified, decompressed or queried in Lite. */
export async function readFullTextIndex(_session:BrowserSession,_doc:BrowserDocument,check:()=>void):Promise<{index?:never;diagnostic?:string}>{check();return {};}
export async function queryFullTextIndex(_index:never,_doc:BrowserDocument,_query:string,check:()=>void):Promise<CollectionMatch[]>{check();return [];}
export function forgetFullTextIndex(_doc:BrowserDocument):void {}
export function invalidateFullTextIndex(_doc:BrowserDocument):void {}
export function resolveStep(_doc:BrowserDocument,_keys:readonly string[]):SearchStep|undefined{return undefined;}
export function fullTextPageOwners(_doc:BrowserDocument):Map<number,string>{return new Map();}
