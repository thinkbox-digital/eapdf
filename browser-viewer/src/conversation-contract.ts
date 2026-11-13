import type {BrowserSession,BrowserDocument} from './browser-session.js';
import type {IndexedMessage} from './message-index.js';
import type {MailMessage} from './domain/inspection.js';
export type ConversationContext={selection?:{doc:BrowserDocument;message?:MailMessage};root:BrowserDocument;session:BrowserSession;check:()=>void;open:(message:IndexedMessage,edge?:-1|1)=>Promise<void>};
