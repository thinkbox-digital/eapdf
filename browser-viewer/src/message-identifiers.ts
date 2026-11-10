// Normalize comparison keys only. Original XMP/header strings remain untouched.
const atom=(value:string)=>value.length>0&&value.split('.').every(part=>part.length>0&&[...part].every(c=>/[A-Za-z0-9!#$%&'*+\-/=?^_`{|}~]/.test(c)||c.charCodeAt(0)>=128&&!/[\s\p{C}]/u.test(c)));
function withoutComments(value:string):string|null{
 let out='',depth=0,quoted=false,literal=false,escaped=false;
 for(const c of value){
  if(escaped){if(!depth)out+='\\'+c;escaped=false;continue;}
  if(c==='\\'&&(depth||quoted||literal)){escaped=true;continue;}
  if(depth){if(c==='('&&++depth>64)return null;if(c===')')depth--;continue;}
  if(c==='"'&&!literal){quoted=!quoted;out+=c;continue;}
  if(!quoted){if(c==='('&&!literal){depth=1;out+=' ';continue;}if(c==='[')literal=true;else if(c===']')literal=false;}
  out+=c;
 }
 return depth||quoted||literal||escaped?null:out.trim();
}
export function normalizeMessageId(value:string):string|null{
 const unfolded=value.replace(/\r\n[ \t]+/g,' ');if(/[\r\n\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(unfolded))return null;
 const clean=withoutComments(unfolded);if(!clean)return null;
 let at=-1,quoted=false,literal=false,escaped=false;
 for(let i=0;i<clean.length;i++){const c=clean[i]!;if(escaped){escaped=false;continue;}if(c==='\\'&&(quoted||literal)){escaped=true;continue;}if(c==='"'&&!literal)quoted=!quoted;else if(!quoted&&c==='[')literal=true;else if(!quoted&&c===']')literal=false;else if(!quoted&&!literal&&c==='@'){if(at>=0)return null;at=i;}}
 // Retain compatibility with opaque legacy IDs already present in EA-PDF samples.
 if(at<0)return atom(clean)?clean:null;
 let local=clean.slice(0,at).trim();const domain=clean.slice(at+1).trim().replace(/[ \t]*\.[ \t]*/g,'.');
 if(local.startsWith('"')){
  if(!local.endsWith('"'))return null;const body=local.slice(1,-1);let decoded='';
  for(let i=0;i<body.length;i++){const c=body[i]!;if(c==='\\'){if(++i>=body.length)return null;decoded+=body[i];}else {if(c==='"')return null;decoded+=c;}}
  local=atom(decoded)?decoded:'"'+decoded.replaceAll('\\','\\\\').replaceAll('"','\\"')+'"';
 }else {local=local.replace(/[ \t]*\.[ \t]*/g,'.');if(!atom(local))return null;}
 if(!atom(domain)&&!/^\[(?:[^\[\]\\\s]|\\[^\r\n])+\]$/u.test(domain))return null;
 return local+'@'+domain;
}
export function messageIds(value:string|undefined):string[]{
 if(!value)return [];const ids:string[]=[];let comments=0,phrase=false,escaped=false;
 for(let i=0;i<value.length;i++){
  const c=value[i]!;
  if(escaped){escaped=false;continue;}if(c==='\\'&&(comments||phrase)){escaped=true;continue;}
  if(comments){if(c==='(')comments++;else if(c===')')comments--;continue;}
  if(c==='"'){phrase=!phrase;continue;}if(!phrase&&c==='('){comments=1;continue;}
  if(c!=='<'||phrase)continue;
  const start=i+1;let quoted=false,literal=false,escape=false,nested=false,closed=false,comment=0;
  for(i=start;i<value.length;i++){
   const ch=value[i]!;if(escape){escape=false;continue;}if(ch==='\\'&&(quoted||literal||comment)){escape=true;continue;}
   if(comment){if(ch==='(')comment++;else if(ch===')')comment--;continue;}
   if(ch==='('&&!quoted&&!literal){comment=1;continue;}
   if(ch==='"'&&!literal){quoted=!quoted;continue;}if(!quoted&&ch==='[')literal=true;else if(!quoted&&ch===']')literal=false;
   if(!quoted&&!literal&&ch==='<')nested=true;
   if(!quoted&&!literal&&ch==='>'){closed=true;break;}
  }
  if(closed&&!nested){const id=normalizeMessageId(value.slice(start,i));if(id!==null)ids.push(id);}
 }
 return ids;
}
