// Keep shared reader styles; omit selectors for the optional collection view.
// Braces and selector commas are parsed outside comments/strings/brackets.
import assert from 'node:assert/strict';
function splitSelectors(text) {
 const result=[];let start=0,depth=0,quote='';
 for(let i=0;i<text.length;i++){
  const c=text[i];if(quote){if(c==='\\')i++;else if(c===quote)quote='';continue;}
  if(c==='"'||c==="'"){quote=c;continue;}
  if(c==='('||c==='[')depth++;else if(c===')'||c===']')depth--;
  if(c===','&&!depth){result.push(text.slice(start,i));start=i+1;}
 }result.push(text.slice(start));return result;
}
export function liteStyles(css) {
 css=css.replace(/\/\*[\s\S]*?\*\//g,'').replaceAll(':not([data-portfolio])','');
 let result='',start=0,open=-1,depth=0,quote='';
 for(let i=0;i<css.length;i++){
  const c=css[i];if(quote){if(c==='\\')i++;else if(c===quote)quote='';continue;}
  if(c==='"'||c==="'"){quote=c;continue;}
  if(c==='{'){if(depth++===0)open=i;}
  else if(c==='}'&&--depth===0){
   const header=css.slice(start,open).trim(),body=css.slice(open+1,i);let selectors;
   if(/^@(media|supports|layer|container)\b/.test(header)){const nested=liteStyles(body);if(nested.trim())result+=header+'{'+nested+'}\n';}
   else if(header.startsWith('@'))result+=header+'{'+body+'}\n';
   else {selectors=splitSelectors(header).filter(selector=>!selector.includes('portfolio'));if(selectors.length)result+=selectors.join(',')+'{'+body+'}\n';}
   start=i+1;
  }
 }
 assert.equal(depth,0,'Unbalanced shared CSS');assert.equal(css.slice(start).trim(),'','Review trailing CSS');
 return result;
}
