import type {BrowserSession,BrowserDocument} from './browser-session.js';
import type {MailMessage,AssociatedFile,EmbeddedStream} from './domain/inspection.js';
import type {OpeningDestination} from './opening-destination.js';
import type {ScrollingPages} from './scrolling-pages.js';
import type {AttachmentViewer} from './attachment-viewer.js';
export interface CollectionViewHost {
 readonly savedMessages:ReadonlyMap<BrowserDocument,string|undefined>;
 readonly session:BrowserSession|undefined; readonly current:BrowserDocument|undefined; page:number;
 readonly pages:Pick<ScrollingPages,'clear'>; readonly surface:HTMLElement; readonly empty:HTMLElement; readonly status:HTMLElement;
 readonly attachmentViewer:Pick<AttachmentViewer,'open'>; readonly openingPlans:WeakMap<BrowserDocument,OpeningDestination>;
 check(ticket:number):void;
 operation(action:(ticket:number)=>Promise<void>):Promise<void>;
 showDocument(doc:BrowserDocument,ticket:number,message?:MailMessage):Promise<void>;
 render(ticket:number,scroll?:boolean):Promise<void>;
 showInformation(doc:BrowserDocument):void;
 showOutline(doc:BrowserDocument,ticket:number):Promise<void>;
 openChild(parent:BrowserDocument,file:AssociatedFile,stream:EmbeddedStream,ticket:number):Promise<void>;
 returnToParent(parent:BrowserDocument,ticket:number):Promise<void>;
 prepareBrowseSubjects(doc:BrowserDocument,ticket:number):void;
 stopBrowseIndex():void;
}
