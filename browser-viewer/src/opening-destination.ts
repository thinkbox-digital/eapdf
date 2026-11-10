import type {BrowserDocument} from './browser-session.js';
export type OpeningDestination={page:number;zoom:string;left:number|null;top:number|null};
export async function openingDestination(doc:BrowserDocument,width:number,height:number,check:()=>void):Promise<OpeningDestination|null>{
 const raw=await doc.run(doc.pdf.getOpenAction());
 const action=raw instanceof Map?{dest:raw.get('dest'),action:raw.get('action')}:raw as unknown as {dest?:any;action?:string}|null;check();if(!action)return null;
 let destination=action.dest;
 if(typeof destination==='string')destination=await doc.run(doc.pdf.getDestination(destination));check();
 if(!destination){if(action.action)throw Error('The PDF requests an unsupported opening action.');return null;}
 const index=typeof destination[0]==='number'?destination[0]:await doc.run(doc.pdf.getPageIndex(destination[0]));check();
 if(!Number.isInteger(index)||index<0||index>=doc.pdf.numPages)throw Error('The PDF opening page is outside the document.');
 const mode=destination[1]?.name,page=await doc.run(doc.pdf.getPage(index+1));check();
 try{
 const size=page.getViewport({scale:1});let zoom='fit-page',left:number|null=null,top:number|null=null;
 const coordinate=(v:unknown)=>v===null||v===undefined?null:typeof v==='number'&&Number.isFinite(v)?v:(()=>{throw Error('The PDF opening destination has an invalid coordinate.');})();
 if(mode==='XYZ'){
  left=coordinate(destination[2]);top=coordinate(destination[3]);const scale=coordinate(destination[4]);if(scale!==null&&scale!==0){if(scale<0)throw Error('Invalid opening zoom.');zoom=String(scale);}
 }else if(mode==='Fit'||mode==='FitB')zoom='fit-page';
 else if(mode==='FitH'||mode==='FitBH'){zoom='fit-width';top=coordinate(destination[2]);}
 else if(mode==='FitV'||mode==='FitBV'){zoom=String(height/size.height/(96/72));left=coordinate(destination[2]);}
 else if(mode==='FitR'){
  const x1=coordinate(destination[2]),y1=coordinate(destination[3]),x2=coordinate(destination[4]),y2=coordinate(destination[5]);
  if(x1===null||y1===null||x2===null||y2===null||x2<=x1||y2<=y1)throw Error('Invalid opening rectangle.');
  const a=size.convertToViewportPoint(x1,y1),b=size.convertToViewportPoint(x2,y2);
  zoom=String(Math.min(width/Math.abs(b[0]-a[0]),height/Math.abs(b[1]-a[1]))/(96/72));left=x1;top=y2;
 }else throw Error('The PDF requests an unsupported opening destination.');
 return {page:index+1,zoom,left,top};
 }finally{page.cleanup();}
}
