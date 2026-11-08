import {DOMParser} from '@xmldom/xmldom';
import {interpret,type InterpretationInput} from './domain/interpret.js';
self.onmessage=(event:MessageEvent<{input:Omit<InterpretationInput,'parseXml'>;chunked?:boolean}>)=>{
  try{
    const result=interpret({...event.data.input,parseXml:source=>new DOMParser({onError:(_level,message)=>{throw new Error(message);}}).parseFromString(source,'application/xml') as unknown as Document});
    if(!event.data.chunked){self.postMessage({result});return;}
    // Bound each structured-clone task on the UI thread, not just XML parsing.
    const fields=['parts','messages','files','metadataRecords','diagnostics'] as const;
    const arrays=fields.map(key=>({key,values:result.inspection[key]}));
    const inspection={...result.inspection,parts:[],messages:[],files:[],metadataRecords:[],diagnostics:[]};
    self.postMessage({start:{...result,inspection,details:new Map()}});
    for(const {key,values} of arrays)for(let i=0;i<values.length;i+=128)self.postMessage({key,values:values.slice(i,i+128)});
    const details=[...result.details];for(let i=0;i<details.length;i+=128)self.postMessage({details:details.slice(i,i+128)});
    self.postMessage({done:true});
  }catch(error){self.postMessage({error:error instanceof Error?error.message:String(error)});}
};
