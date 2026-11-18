import type {AssociatedFile} from './domain/inspection.js';
import {fileLabel} from './navigation.js';
export function fileCategory(file:AssociatedFile):string {
  const premis=/(?:^|[_. -])premis(?:[_. -]|$)/i.test(fileLabel(file))&&/\.xml$/i.test(fileLabel(file))
    ||file.streams.some(stream=>/premis/i.test(stream.mimeType.value??''));
  if(premis||file.afRelationship.value==='Supplement')return 'Technical and supporting files';
  if(file.afRelationship.value==='Source')return 'Email sources';
  return file.owners.value?.length?'Email attachments':'Archive files';
}
