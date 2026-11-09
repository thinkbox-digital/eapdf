// Randomized slots keep thumbnails scattered but non-overlapping. Positions
// remain stable for a panel session; source/keyboard order is never changed.
export function scatterPositions(ids:readonly string[],width:number,seed:number):{cardWidth:number;height:number;positions:{x:number;y:number}[]} {
  const hash=(id:string,salt:number)=>{let n=(seed^salt)>>>0;for(const c of id)n=Math.imul(n^c.charCodeAt(0),16777619)>>>0;n^=n>>>16;n=Math.imul(n,2246822507);n^=n>>>13;return (n>>>0)/4294967296;};
  const available=Math.max(1,width),columns=Math.max(1,Math.floor(available/198));
  const slotWidth=available/columns,cardWidth=Math.max(1,Math.min(176,slotWidth-20)),slotHeight=224,cardHeight=200;
  const order=ids.map((id,index)=>({id,index,rank:hash(id,1)})).sort((a,b)=>a.rank-b.rank||a.index-b.index);
  const positions=ids.map(()=>({x:0,y:0}));
  order.forEach(({id,index},slot)=>{positions[index]={x:(slot%columns)*slotWidth+hash(id,2)*(slotWidth-cardWidth),y:Math.floor(slot/columns)*slotHeight+hash(id,3)*(slotHeight-cardHeight)};});
  return {cardWidth,height:Math.ceil(ids.length/columns)*slotHeight,positions};
}
