import type {CollectionViewHost} from './collection-view-contract.js';
import type {BrowserDocument} from './browser-session.js';
import type {Detection} from './document-kind.js';
/** Lite uses the ordinary message/folder tree for every collection. */
export class CollectionView {
 constructor(_host:CollectionViewHost) {}
 readonly active=false;
 readonly outlinePanel='outline-panel';
 readonly outlineList='outline';
 controls(_busy:boolean):void {}
 keyboardSelection(_focused:Element|null):(()=>HTMLElement|null)|undefined{return undefined;}
 reset():void {}
 folderPath(_doc:BrowserDocument):readonly string[]{return [];}
 information(_root:Detection,_detection:Detection,_doc:BrowserDocument):void {}
 documentLabel():string{return 'PDF collection';}
 async prepare(_doc:BrowserDocument,_ticket:number):Promise<void> {}
 sync(_doc:BrowserDocument):void {}
 async crumb(_doc:BrowserDocument,_ticket:number):Promise<void> {}
 async opened(_doc:BrowserDocument,_ticket:number,_initial=false):Promise<boolean>{return false;}
 async parent(_doc:BrowserDocument,_ticket:number):Promise<boolean>{return false;}
 async embeddedTarget(_doc:BrowserDocument,_ticket:number):Promise<void> {}
}
