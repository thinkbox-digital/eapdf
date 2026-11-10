/** Pointer/touch and keyboard resizing; responsive drawers keep their existing layout. */
export function setupPaneResizers(workspace:HTMLElement):void {
  const widths={navigation:27,details:27};
  for(const pane of ['navigation','details'] as const){
    const bar=document.getElementById('resize-'+pane)!;let pointer:number|undefined;
    const bounds=()=>{
      const width=workspace.clientWidth,other=pane==='navigation'?'details':'navigation';
      const otherWidth=workspace.classList.contains('hide-'+other)?0:widths[other];
      return [Math.min(20,200/width*100),Math.max(20,100-otherWidth-320/width*100)];
    };
    const resize=(value:number)=>{const [min,max]=bounds();widths[pane]=Math.max(min!,Math.min(max!,value));
      workspace.style.setProperty('--'+pane+'-width',widths[pane]+'%');
      bar.setAttribute('aria-valuenow',String(Math.round(widths[pane])));
    };
    bar.setAttribute('aria-valuemin','0');bar.setAttribute('aria-valuemax','100');resize(widths[pane]);
    bar.addEventListener('pointerdown',event=>{if(event.button!==0)return;event.preventDefault();pointer=event.pointerId;bar.setPointerCapture(pointer);bar.focus();document.body.classList.add('resizing-panes');});
    bar.addEventListener('pointermove',event=>{if(event.pointerId!==pointer)return;const rect=workspace.getBoundingClientRect();resize((pane==='navigation'?event.clientX-rect.left:rect.right-event.clientX)/rect.width*100);});
    const end=()=>{pointer=undefined;document.body.classList.remove('resizing-panes');};
    for(const type of ['pointerup','pointercancel','lostpointercapture'])bar.addEventListener(type,end);
    bar.addEventListener('keydown',event=>{if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)||event.altKey||event.ctrlKey||event.metaKey)return;event.preventDefault();const [min,max]=bounds();
      resize(event.key==='Home'?min!:event.key==='End'?max!:widths[pane]+(event.key==='ArrowRight'?1:-1)*(pane==='navigation'?1:-1)*(event.shiftKey?10:2));});
  }
}
