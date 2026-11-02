/** Count actual decoded response bytes; Content-Length is only an early check. */
export async function boundedResponseBlob(response:Response,maximum:number,signal:AbortSignal):Promise<Blob>{
  if(!response.ok){await response.body?.cancel().catch(()=>{});throw new Error(`The server returned HTTP ${response.status}.`);}
  const declared=response.headers.get('content-length');
  if(declared&&/^\d+$/.test(declared)&&Number(declared)>maximum){await response.body?.cancel().catch(()=>{});throw new Error('Linked PDF exceeds the download byte limit.');}
  if(!response.body)throw new Error('The server returned no readable PDF data.');
  const reader=response.body.getReader(),chunks:Uint8Array<ArrayBuffer>[]=[];let count=0,complete=false;
  const abort=()=>{void reader.cancel(signal.reason).catch(()=>{});};
  signal.addEventListener('abort',abort,{once:true});
  try{
    while(true){
      signal.throwIfAborted();const result=await reader.read();signal.throwIfAborted();
      if(result.done)break;
      if(result.value.length>maximum-count)throw new Error('Linked PDF exceeds the download byte limit.');
      count+=result.value.length;chunks.push(new Uint8Array(result.value));
    }
    complete=true;return new Blob(chunks,{type:'application/pdf'});
  }finally{
    signal.removeEventListener('abort',abort);
    if(!complete)await reader.cancel().catch(()=>{});
    reader.releaseLock();
  }
}

export async function downloadLinkedPdf(url:string,controller:AbortController,maximum=64*1024**2,milliseconds=30000):Promise<Blob>{
  const timer=setTimeout(()=>controller.abort(new Error('Linked PDF download timed out.')),milliseconds);
  try{
    const response=await fetch(url,{credentials:'omit',redirect:'error',signal:controller.signal});
    return await boundedResponseBlob(response,maximum,controller.signal);
  }catch(error){if(!controller.signal.aborted)controller.abort(error);if(controller.signal.reason instanceof Error)throw controller.signal.reason;throw error;}
  finally{clearTimeout(timer);}
}
