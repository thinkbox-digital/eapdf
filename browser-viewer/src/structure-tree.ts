// Check cycles and retained string memory before building the public structure DOM.
// PDF.js renders this tree recursively, so bound both depth and queued nodes first.
export const STRUCTURE_TREE_LIMITS=Object.freeze({depth:128,nodes:50_000,characters:1_000_000});
export function boundedStructureTree(root:unknown):boolean {
  if(root===null)return true;
  const pending=[{node:root,depth:0}],seen=new Set<object>();let characters=0,nodes=0;
  while(pending.length){const {node,depth}=pending.pop()!;
    if(++nodes>STRUCTURE_TREE_LIMITS.nodes||depth>STRUCTURE_TREE_LIMITS.depth||typeof node!=='object'||node===null||seen.has(node))return false;
    seen.add(node);const value=node as Record<string,unknown>;
    for(const field of Object.values(value))if(typeof field==='string'){characters+=field.length;if(characters>STRUCTURE_TREE_LIMITS.characters)return false;}
    if(value.children!==undefined){if(!Array.isArray(value.children))return false;for(const child of value.children){if(nodes+pending.length>=STRUCTURE_TREE_LIMITS.nodes)return false;pending.push({node:child,depth:depth+1});}}
  }
  return true;
}
