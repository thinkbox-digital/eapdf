import {text} from './viewer-elements.js';
export function messageLabel(node:HTMLButtonElement,label:string,details?:Record<string,string>):void {
  const content=text('span',''),subject=text('span',label);content.className='message-text';subject.className='message-subject';subject.title=label;content.append(subject);
  const secondary=[details?.From||details?.['Raw-From'],details?.Sent].filter(Boolean).join(' · ');
  if(secondary){const meta=text('span',''),sender=text('span',details?.From||details?.['Raw-From']||''),date=text('span',details?.Sent?.match(/^\d{4}-\d{2}-\d{2}/)?.[0]||details?.Sent||'');meta.className='message-meta';meta.title=secondary;sender.className='message-sender';date.className='message-date';meta.append(sender);if(date.textContent)meta.append(text('span','·'),date);content.append(meta);}
  for(const child of [...node.childNodes])if(child.nodeType===Node.TEXT_NODE)child.remove();node.append(content);
}
