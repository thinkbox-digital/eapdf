import {RowHeights} from './row-heights.js';
/** Variable-height sidebar rows, mounted only near the visible selection. */
export class VirtualList<T> {
  private rows=new Map<number,HTMLElement>();
  private heights:RowHeights;
  private top=document.createElement('li');
  private bottom=document.createElement('li');
  private frame=0;
  private listening=()=>{if(!this.frame)this.frame=requestAnimationFrame(()=>{this.frame=0;this.follow();});};
  constructor(private list:HTMLElement,private panel:HTMLElement,private values:readonly T[],private create:(value:T,index:number)=>HTMLElement,estimate:number){
    this.heights=new RowHeights(values.length,estimate);
    for(const spacer of [this.top,this.bottom]){spacer.style.margin='0';spacer.style.padding='0';spacer.setAttribute('aria-hidden','true');}
    panel.addEventListener('scroll',this.listening,{passive:true});this.mount(0);
  }
  private offset(index:number):number{return this.heights.offset(index);}
  private follow():void{
    const position=this.panel.getBoundingClientRect().top-this.list.getBoundingClientRect().top;
    let low=0,high=this.values.length-1;
    while(low<high){const mid=(low+high)>>>1;if(this.offset(mid+1)<=position)low=mid+1;else high=mid;}
    this.mount(low);
  }
  private mount(index:number):void{
    const first=Math.max(0,index-24),last=Math.min(this.values.length,index+48);
    if(this.rows.has(first)&&this.rows.has(last-1)&&this.rows.size===last-first)return;
    const next=new Map<number,HTMLElement>();
    for(let i=first;i<last;i++){
      let row=this.rows.get(i);if(!row){row=this.create(this.values[i]!,i);row.style.marginTop='0';row.style.marginBottom='3px';row.setAttribute('aria-posinset',String(i+1));row.setAttribute('aria-setsize',String(this.values.length));}
      next.set(i,row);
    }
    const scrollTop=this.panel.scrollTop;
    this.rows=next;this.top.style.height=`${this.offset(first)}px`;this.bottom.style.height=`${this.offset(this.values.length)-this.offset(last)}px`;
    const focused=document.activeElement instanceof HTMLElement&&this.list.contains(document.activeElement)?document.activeElement:undefined;
    this.list.replaceChildren(this.top,...next.values(),this.bottom);
    this.panel.scrollTop=scrollTop;
    if(focused?.isConnected)focused.focus({preventScroll:true});
    const anchor=next.values().next().value as HTMLElement|undefined,before=anchor?.getBoundingClientRect().top;
    for(const [i,row] of next){const h=row.getBoundingClientRect().height;if(h>0)this.heights.set(i,h+3);}
    this.top.style.height=`${this.offset(first)}px`;this.bottom.style.height=`${this.offset(this.values.length)-this.offset(last)}px`;
    if(anchor&&before!==undefined)this.panel.scrollTop+=anchor.getBoundingClientRect().top-before;
  }
  rowAt(index:number):HTMLElement|undefined{this.mount(index);return this.rows.get(index);}
  invalidateRow(index:number,row:HTMLElement):void{const previous=this.rows.get(index);if(previous){previous.replaceWith(row);this.rows.set(index,row);}}
  replaceRow(index:number,row:HTMLElement):void{
    this.mount(index);const previous=this.rows.get(index);previous?.replaceWith(row);this.rows.set(index,row);
    const height=row.getBoundingClientRect().height;if(height>0)this.heights.set(index,height+3);
  }
  close():void{cancelAnimationFrame(this.frame);this.panel.removeEventListener('scroll',this.listening);this.rows.clear();}
}
