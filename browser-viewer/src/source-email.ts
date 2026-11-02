// Inspect only an explicitly embedded email source's header block. Do not guess
// ownership from names, ordering, subjects, or a single-message document.
export function sourceMessageId(bytes:Uint8Array,headerOnly=false):string|undefined {
  const limit=Math.min(bytes.length,256*1024);let end=-1;
  for(let i=0;i<limit-1;i++){
    if(bytes[i]===10&&bytes[i+1]===10){end=i;break;}
    if(i+3<limit&&bytes[i]===13&&bytes[i+1]===10&&bytes[i+2]===13&&bytes[i+3]===10){end=i;break;}
  }
  if(end<0&&headerOnly&&bytes.length<=limit)end=bytes.length;
  if(end<0)return undefined;
  let head:string;try{head=new TextDecoder('utf-8',{fatal:true}).decode(bytes.subarray(0,end));}catch{return undefined;}
  const headers=head.trimEnd().replace(/\r?\n[ \t]+/g,' ');
  const lines=headers.split(/\r?\n/);
  if(lines.some(line=>! /^[\x21-\x39\x3b-\x7e]+:/.test(line)))return undefined;
  const ids=lines.filter(line=>/^message-id:/i.test(line)).map(line=>line.slice(line.indexOf(':')+1).trim());
  return ids.length===1&&/^<[^<>\s]+@[^<>\s]+>$/.test(ids[0]!)?ids[0]:undefined;
}
