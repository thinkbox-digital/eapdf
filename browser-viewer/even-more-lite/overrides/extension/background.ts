declare const chrome:{action:{onClicked:{addListener(listener:()=>void):void}};runtime:{getURL(path:string):string};tabs:{create(options:{url:string}):Promise<unknown>}};
chrome.action.onClicked.addListener(()=>{void chrome.tabs.create({url:chrome.runtime.getURL('viewer.html')});});
