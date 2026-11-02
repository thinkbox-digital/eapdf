// Bound the public structure record before upstream's recursive DOM builder sees it.
export function boundedStructureTree(root:unknown):boolean {
  if(root===null)return true;
  const pending=[{node:root,depth:0}],seen=new Set<object>();let count=0,characters=0;
  while(pending.length){const {node,depth}=pending.pop()!;
    if(typeof node!=='object'||node===null||seen.has(node)||depth>64||++count>10000)return false;
    seen.add(node);const value=node as Record<string,unknown>;
    for(const field of Object.values(value))if(typeof field==='string'){characters+=field.length;if(characters>1_000_000)return false;}
    if(value.children!==undefined){if(!Array.isArray(value.children)||value.children.length>10000)return false;for(const child of value.children)pending.push({node:child,depth:depth+1});}
  }
  return true;
}
