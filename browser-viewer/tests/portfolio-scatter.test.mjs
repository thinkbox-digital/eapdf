import test from 'node:test';import assert from 'node:assert/strict';
import {scatterPositions} from '../dist/chromium/portfolio-scatter.js';
test('Freeform positions remain inside narrow and wide panes without obscuring other files',()=>{
 const ids=Array.from({length:100},(_,i)=>'member:'+i);
 for(const width of [1,180,220,390,800,1600])for(const seed of [0,1,123456]){
  const layout=scatterPositions(ids,width,seed);assert.equal(layout.positions.length,100);
  for(let i=0;i<100;i++){const p=layout.positions[i];assert.ok(p.x>=0&&p.y>=0&&p.x+layout.cardWidth<=width+1e-9&&p.y+200<=layout.height);
   for(let j=0;j<i;j++){const q=layout.positions[j];assert.ok(p.x+layout.cardWidth<=q.x||q.x+layout.cardWidth<=p.x||p.y+200<=q.y||q.y+200<=p.y);}
  }
 }
});
test('Freeform is stable within a panel session and does not mutate keyboard order',()=>{
 const ids=['a','b','c','d'],before=[...ids];const initial=scatterPositions(ids,600,42);
 assert.deepEqual(scatterPositions(ids,600,42),initial);assert.deepEqual(ids,before);
 assert.notDeepEqual(scatterPositions(ids,600,43),initial);assert.equal(scatterPositions([],600,42).height,0);
});
