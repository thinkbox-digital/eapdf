import type {AssociatedFile,EmbeddedStream} from './domain/inspection.js';
import {fileLabel} from './navigation.js';

export const isPdf=(file:AssociatedFile,stream:EmbeddedStream)=>stream.mimeType.value?.split(';')[0]?.toLowerCase()==='application/pdf'||/\.pdf$/i.test(fileLabel(file));
export const canView=(file:AssociatedFile,stream:EmbeddedStream)=>isPdf(file,stream)||/^(text\/|message\/(rfc822|global)|image\/(png|jpeg|gif|svg\+xml)|application\/(json|xml))/.test(stream.mimeType.value??'')||/\.(png|jpe?g|gif|txt|csv|tsv|log|json|xml|html?|eml|svg|md)$/i.test(fileLabel(file));
export function sizeLabel(size:number|null):string {if(size===null)return 'Size not recorded';if(size<1024)return `${size} B`;if(size<1024**2)return `${(size/1024).toFixed(1)} KiB`;return `${(size/1024**2).toFixed(1)} MiB`;}
