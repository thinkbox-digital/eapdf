import type { AssociatedFile, InspectionResult, MailMessage } from './domain/inspection.js';

// Navigate only to body pages with one proven owner. Metadata remains selectable
// even when a malformed or ambiguous DPart prevents page navigation.
export function messagePages(inspection: InspectionResult, message: MailMessage): number[] {
  const pages = new Set<number>();
  for (const part of inspection.parts) {
    if (!message.partIds.value?.includes(part.id) ||
        part.dpm.Mail_ContentSetType?.value !== 'BodyRendering' ||
        part.messageIds.value?.length !== 1 || part.messageIds.value[0] !== message.id ||
        part.pageRange.state !== 'present') continue;
    const { start, end } = part.pageRange.value;
    if (start < 0 || end >= inspection.pageCount || start > end) continue;
    for (let page = start; page <= end; page++) pages.add(page + 1);
  }
  return [...pages].sort((a, b) => a - b);
}

export function fileLabel(file: AssociatedFile): string {
  return file.unicodeFilename.value ?? file.filename.value ?? 'Unnamed embedded file';
}

export function downloadName(name: string): string {
  // Keep payload bytes intact; remove path/control characters from the save name.
  return name.replace(/[\\/\u0000-\u001f\u007f<>:"|?*\u202a-\u202e\u2066-\u2069]/g, '_')
    .replace(/^\.+|[. ]+$/g, '').slice(0, 180) || 'embedded-file';
}

// Only explicit, unambiguous collection membership permits automatic file changes.
// Unknown folders must never be treated as the root folder.
export function folderFiles(inspection:InspectionResult,fileId:string):readonly AssociatedFile[] {
  const collection=inspection.collection.value;
  if(!collection||!['c','ci'].includes(inspection.identification.declaredProfile.value??'')||
    inspection.diagnostics.some(d=>d.code.includes('FOLDER')))return [];
  const folder=(id:string):string|null=>{
    const entries=collection.entries.filter(e=>e.fileId===id);
    if(!entries.length||entries.some(e=>e.folderIds.state!=='present'||e.folderIds.value!.length>1))return null;
    const ids=new Set(entries.map(e=>e.folderIds.value![0]??''));if(ids.size!==1)return null;
    const idValue=[...ids][0]!;let cursor=idValue;const seen=new Set<string>();
    while(cursor){
      if(seen.has(cursor))return null;seen.add(cursor);
      const node=collection.folders.find(f=>f.id===cursor);if(!node)return null;cursor=node.parentId??'';
    }
    return idValue;
  };
  const current=folder(fileId);if(current===null)return [];
  return inspection.files.filter(file=>folder(file.id)===current);
}
