type Tab={url?:string};
declare const chrome:{
 action:{onClicked:{addListener(listener:(tab:Tab)=>void):void}};
 runtime:{getURL(path:string):string;onInstalled:{addListener(listener:()=>void):void}};
 tabs:{create(options:{url:string}):Promise<unknown>};
 contextMenus:{create(options:{id:string;title:string;contexts:string[]}):void;removeAll():Promise<void>;onClicked:{addListener(listener:(info:{menuItemId:string|number;linkUrl?:string;pageUrl?:string},tab?:Tab)=>void):void}};
};
function open(source?:string):void {
 let suffix='';if(source){try{const url=new URL(source);if(['http:','https:','file:'].includes(url.protocol))suffix='#'+new URLSearchParams({source:url.href}).toString();}catch{/* Open the local picker. */}}
 void chrome.tabs.create({url:chrome.runtime.getURL('viewer.html')+suffix});
}
chrome.action.onClicked.addListener(tab=>open(tab.url));
chrome.runtime.onInstalled.addListener(()=>{void chrome.contextMenus.removeAll().then(()=>chrome.contextMenus.create({id:'open-eapdf',title:'Open in EA-PDF viewer',contexts:['link','page','action']}));});
chrome.contextMenus.onClicked.addListener((info,tab)=>{if(info.menuItemId==='open-eapdf')open(info.linkUrl??info.pageUrl??tab?.url);});
