/** Conservative accounting for retained inspection data, not a browser heap measurement. */
export function retainedSize(value:unknown,ceiling=64*1024**2):number{
 const pending:unknown[]=[value],seen=new Set<object>();let bytes=0;
 while(pending.length&&bytes<ceiling){
  const item=pending.pop();
  if(typeof item==='string'){bytes+=item.length*2+24;continue;}
  if(!item||typeof item!=='object'){bytes+=8;continue;}
  if(seen.has(item))continue;seen.add(item);bytes+=64;
  if(ArrayBuffer.isView(item)){bytes+=item.byteLength;continue;}
  if(item instanceof ArrayBuffer){bytes+=item.byteLength;continue;}
  if(item instanceof Map){bytes+=item.size*32;for(const [key,value] of item)pending.push(key,value);}
  else if(item instanceof Set){bytes+=item.size*16;for(const value of item)pending.push(value);}
  else for(const key in item){bytes+=key.length*2+8;pending.push((item as Record<string,unknown>)[key]);}
 }
 return Math.min(bytes,ceiling);
}
