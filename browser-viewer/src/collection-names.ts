import type {InspectionResult} from './domain/inspection.js';
export function checkCollectionNames(inspection:InspectionResult):void{
 const collection=inspection.collection.value;if(!collection)return;
 const invalid=(value:string)=>!value||value==='.'||value==='..'||/[\\/:*?"<>|\u0000-\u001f]/u.test(value);
 const keys=new Set<string>();
 for(const entry of collection.entries){const key=entry.nameTreeKey.value;if(key===null)throw Error('Collection filename is unreadable.');
  const canonical=key.replace(/^\uFEFF/,'');
  const name=canonical.replace(/^<\d+>/,'');const normalized=canonical.normalize('NFC').toUpperCase();
  if(invalid(name)||keys.has(normalized))throw Error('Invalid or duplicate collection filename: '+key);keys.add(normalized);
 }
 const folders=new Set<string>();for(const folder of collection.folders){const name=folder.name.value,key=folder.parentId+'|'+name?.normalize('NFC').toUpperCase();if(name===null||(invalid(name)&&!(name===''&&folder.parentId===null))||folders.has(key))throw Error('Invalid or duplicate collection folder name: '+name);folders.add(key);}
}
