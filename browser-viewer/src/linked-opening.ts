import {downloadLinkedPdf} from './bounded-download.js';
declare const chrome:{permissions:{contains(options:{origins:string[]}):Promise<boolean>;request(options:{origins:string[]}):Promise<boolean>;remove(options:{origins:string[]}):Promise<boolean>}};
export function setupLinkedOpening(open:(blob:Blob,name:string)=>Promise<void>,status:(text:string)=>void):()=>void {
  const raw=new URLSearchParams(location.hash.slice(1)).get('source');
  if(!raw)return ()=>{};
  const panel=document.getElementById('linked-file')!,label=document.getElementById('linked-label')!,button=document.getElementById('open-linked') as HTMLButtonElement;
  let url:URL;try{url=new URL(raw);}catch{return ()=>{};}
  panel.hidden=false;
  if(url.protocol==='file:'){label.textContent='Local PDF selected. Use Open PDF or drag the file here to grant access.';button.hidden=true;return ()=>{};}
  if(!['https:','http:'].includes(url.protocol)||url.username||url.password){label.textContent='Use Open PDF or drag a local file here.';button.hidden=true;return ()=>{};}
  label.textContent=`Open PDF from ${url.hostname}`;
  const permission={origins:[url.origin+'/*']};let previous=false,controller:AbortController|undefined;
  button.disabled=true;void chrome.permissions.contains(permission).then(value=>{previous=value;button.disabled=false;}).catch(()=>{label.textContent='Use Open PDF to choose a downloaded copy.';});
  button.addEventListener('click',()=>{
    if(controller){controller.abort();return;}
    // Call directly from the click so both browsers retain the user gesture.
    const request=chrome.permissions.request(permission);controller=new AbortController();const active=controller;button.textContent='Cancel opening';
    void (async()=>{
      let granted=false;
      try{
        granted=await request;if(!granted){status('Site access was not granted. Download the PDF and use Open PDF.');return;}
        if(active.signal.aborted)return;status(`Loading PDF from ${url.hostname}…`);
        const blob=await downloadLinkedPdf(url.href,active);if(active.signal.aborted)return;
        controller=undefined;panel.hidden=true;
        let name='Linked PDF';try{name=decodeURIComponent(url.pathname.split('/').at(-1)??'')||name;}catch{/* Preserve safe fallback. */}
        await open(blob,name);
      }catch(error){status(active.signal.aborted&&active.signal.reason?.name==='AbortError'?'Opening cancelled.':`Unable to open the link. Download a local copy and use Open PDF. ${error instanceof Error?error.message:String(error)}`);}
      finally{if(granted&&!previous)await chrome.permissions.remove(permission);if(controller===active)controller=undefined;button.textContent='Open linked PDF';}
    })();
  });
  return ()=>controller?.abort();
}
