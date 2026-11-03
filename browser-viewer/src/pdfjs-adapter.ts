import {requestPassword} from './password-prompt.js';
// Only the public display API is used. The relative URL resolves inside the extension.
const url = new URL("./vendor/pdfjs/pdf.mjs", import.meta.url).href;
export const pdfjs: typeof import("pdfjs-dist") = await import(url);
pdfjs.GlobalWorkerOptions.workerSrc = new URL("./vendor/pdfjs/pdf.worker.mjs", import.meta.url).href;
// Extension origins must not take PDF.js's cross-origin blob-worker path.


export function loadPdf(data: Uint8Array) {
  pdfjs.GlobalWorkerOptions.workerPort ??= new Worker(pdfjs.GlobalWorkerOptions.workerSrc, { type: "module" });
  const asset = (name: string) => new URL(`./vendor/pdfjs/${name}/`, import.meta.url).href;
  return pdfjs.getDocument({
    data,
    cMapUrl: asset("cmaps"),
    cMapPacked: true,
    standardFontDataUrl: asset("standard_fonts"),
    wasmUrl: asset("wasm"),
    iccUrl: asset("iccs"),
    useSystemFonts: false,
    enableXfa: false,
    maxImageSize: 16_000_000,
    canvasMaxAreaInBytes: 64 * 1024 * 1024,
  });
}

// Application sessions use a dedicated worker so cancellation/timeout releases
// this document without disrupting a parent or another open collection child.
export function createManagedWorker() {
  const port = new Worker(pdfjs.GlobalWorkerOptions.workerSrc, {type:'module'});
  const worker = pdfjs.PDFWorker.create({port});
  let stopped=false,release!:()=>void;
  const whenClosed=new Promise<void>(resolve=>{release=resolve;});
  return {worker,whenClosed,close:()=>{if(stopped)return;stopped=true;release();worker.destroy();port.terminate();}};
}
export function loadManagedPdf(data: Uint8Array | Blob, shared?:ReturnType<typeof createManagedWorker>) {
  const owned=shared??createManagedWorker(),worker=owned.worker;
  const asset = (name: string) => new URL(`./vendor/pdfjs/${name}/`,import.meta.url).href;
  let rangeError:(error:Error)=>void = () => {};
  class BlobRange extends pdfjs.PDFDataRangeTransport {
    private stopped=false;
    constructor(private readonly blob:Blob){super(blob.size,new Uint8Array(),true);}
    requestDataRange(begin:number,end:number):void {
      void this.blob.slice(begin,end).arrayBuffer().then(buffer=>{
        if(!this.stopped)this.onDataRange(begin,new Uint8Array(buffer));
      }).catch(error=>{if(!this.stopped)rangeError(error instanceof Error?error:new Error(String(error)));});
    }
    abort():void {this.stopped=true;}
  }
  const input=data instanceof Blob?{range:new BlobRange(data),disableAutoFetch:true,disableStream:true,rangeChunkSize:1024*1024}:{data};
  const task = pdfjs.getDocument({...input,worker,cMapUrl:asset('cmaps'),cMapPacked:true,standardFontDataUrl:asset('standard_fonts'),
    wasmUrl:asset('wasm'),iccUrl:asset('iccs'),useSystemFonts:false,enableXfa:false,
    maxImageSize:16_000_000,canvasMaxAreaInBytes:64*1024*1024});
  const passwordAbort=new AbortController();
  task.onPassword=(update:(password:string)=>void,reason:number)=>{void requestPassword(reason===pdfjs.PasswordResponses.INCORRECT_PASSWORD,passwordAbort.signal).then(password=>{if(!closed)update(password);}).catch(error=>close(error));};
  let closed=false;
  let rejectCancellation:(reason:Error)=>void = () => {};
  const cancelled=new Promise<never>((_,reject)=>{rejectCancellation=reject;});
  // Closing an idle document must not create an unhandled rejection.
  void cancelled.catch(()=>{});
  let disposed:Promise<void>=Promise.resolve();
  const close = (reason = new Error('Document processing cancelled.')) => {
    if(closed)return disposed;closed=true;passwordAbort.abort();rejectCancellation(reason);
    disposed=Promise.race([task.destroy().catch(()=>{}),owned.whenClosed]);if(!shared)owned.close();return disposed;
  };
  async function run<T>(operation: Promise<T>, milliseconds=30000): Promise<T> {
    if(closed){void operation.catch(()=>{});throw new Error('Document is closed.');}
    let timer:ReturnType<typeof setTimeout>|undefined;
    try{return await Promise.race([operation,cancelled,new Promise<never>((_,reject)=>{
      timer=setTimeout(()=>{const error=new Error('Document processing timed out; reopen to retry.');close(error);reject(error);},milliseconds);
    })]);}finally{clearTimeout(timer);}
  }
  rangeError=close;
  return {task,close,run,get closed(){return closed;}};
}
