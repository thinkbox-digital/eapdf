import type {ConversationContext} from './conversation-contract.js';
import type {BrowserDocument} from './browser-session.js';
import type {MailMessage} from './domain/inspection.js';
/** Optional presentation is absent in Lite; the shared viewer keeps one lifecycle. */
export class ThreadPreview {
  constructor(_context:()=>ConversationContext|undefined) {}
  readonly enabled=false;
  reset():void {}
  sync(_document:BrowserDocument,_message?:MailMessage):void {}
  navigate(_direction:number,_atEdge?:boolean):boolean {return false;}
}
