import {threadGraph} from './thread-graph.js';
self.onmessage=event=>{try{self.postMessage({graph:threadGraph(event.data.messages,event.data.infer)});}catch(error){self.postMessage({error:error instanceof Error?error.message:String(error)});}};
