import {downloadName} from './navigation.js';
let tail:Promise<void>=Promise.resolve(),lastRequest=-Infinity;
/** Space user-requested downloads across all panes to avoid Chromium burst blocking. */
export function saveBlob(blob:Blob,name:string,check:()=>void=()=>{},parent:HTMLElement=document.body):Promise<void>{
  const request=tail.then(async()=>{
    check();const delay=1100-(performance.now()-lastRequest);
    if(delay>0)await new Promise(resolve=>setTimeout(resolve,delay));
    check();const url=URL.createObjectURL(blob),link=document.createElement('a');
    link.href=url;link.download=downloadName(name);parent.append(link);
    try{link.click();lastRequest=performance.now();}
    finally{
      link.remove();
      // Closing a preview/document must not revoke a download still being accepted.
      setTimeout(()=>URL.revokeObjectURL(url),60000);
    }
  });
  tail=request.catch(()=>{});return request;
}
