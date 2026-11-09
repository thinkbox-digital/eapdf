// A focusable separator shared by pointer, touch and keyboard resizing.
export class PortfolioSplitter {
  readonly element=document.createElement('div');
  private direction='V';private position=35;private pointer:number|undefined;
  constructor(private workspace:HTMLElement,after:HTMLElement,private change:(position:number)=>void){
    const bar=this.element;bar.id='portfolio-splitter';bar.tabIndex=0;
    bar.setAttribute('role','separator');bar.setAttribute('aria-label','Resize Portfolio pane');
    bar.setAttribute('aria-controls','portfolio-panel');bar.setAttribute('aria-valuemin','0');bar.setAttribute('aria-valuemax','100');
    bar.title='Drag to resize. Use arrow keys when focused.';after.after(bar);
    bar.addEventListener('pointerdown',event=>{
      if(event.button!==0||bar.hidden)return;
      event.preventDefault();this.pointer=event.pointerId;bar.setPointerCapture(event.pointerId);bar.focus({preventScroll:true});
      document.body.dataset.portfolioResizing=this.direction;
    });
    bar.addEventListener('pointermove',event=>{
      if(event.pointerId!==this.pointer)return;
      const rect=this.workspace.getBoundingClientRect(),vertical=this.direction==='V';
      const extent=vertical?rect.width:rect.height,coordinate=vertical?event.clientX-rect.left:event.clientY-rect.top;
      if(extent>0)this.resize(coordinate/extent*100);
    });
    const finish=()=>{this.pointer=undefined;delete document.body.dataset.portfolioResizing;};
    bar.addEventListener('pointerup',finish);bar.addEventListener('pointercancel',finish);bar.addEventListener('lostpointercapture',finish);
    bar.addEventListener('keydown',event=>{
      if(event.altKey||event.ctrlKey||event.metaKey)return;
      const keys=this.direction==='V'?['ArrowLeft','ArrowRight']:['ArrowUp','ArrowDown'];
      const index=keys.indexOf(event.key),[min,max]=this.bounds();
      if(index<0&&!['Home','End'].includes(event.key))return;
      event.preventDefault();this.resize(event.key==='Home'?min:event.key==='End'?max:this.position+(index===0?-1:1)*(event.shiftKey?10:2));
    });
  }
  private bounds():[number,number]{
    const rect=this.workspace.getBoundingClientRect(),extent=this.direction==='V'?rect.width:rect.height;
    return extent>0?[Math.min(30,(this.direction==='V'?220:120)/extent*100),100-Math.min(30,(this.direction==='V'?280:180)/extent*100)]:[20,80];
  }
  private resize(position:number):void{const [min,max]=this.bounds();this.change(Math.round(Math.max(min,Math.min(max,position))*10)/10);}
  update(direction:string,position:number,visible:boolean):void{
    this.direction=direction;this.position=position;this.element.hidden=!visible;
    this.element.setAttribute('aria-orientation',direction==='V'?'vertical':'horizontal');
    this.element.setAttribute('aria-valuenow',String(position));this.element.setAttribute('aria-valuetext',`${position}% Portfolio, ${Math.round((100-position)*10)/10}% document`);
  }
  close():void{this.pointer=undefined;delete document.body.dataset.portfolioResizing;this.element.remove();}
}
