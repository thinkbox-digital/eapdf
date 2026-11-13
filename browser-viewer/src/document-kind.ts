import type {InspectionResult} from './domain/inspection.js';
export type Detection={kind:'eapdf'|'portfolio'|'pdf';collection:boolean;notice:string};
export function detectDocument(inspection:InspectionResult):Detection {
  const id=inspection.identification;
  const recognized=id.eapdfVersion.state==='present'&&id.eapdfVersion.value==='1'&&id.declaredProfile.state==='present'&&['s','si','m','mi','c','ci'].includes(id.declaredProfile.value??'');
  const collection=inspection.collection.state==='present';
  const declared=[id.eapdfVersion,id.eapdfRevision,id.declaredProfile].some(v=>v.state!=='missing'&&v.state!=='unavailable');
  return {kind:recognized?'eapdf':collection?'portfolio':'pdf',collection,notice:recognized?'':declared?'EA-PDF identification is incomplete or unsupported; showing the available PDF content.':inspection.collection.state==='invalid'?'The Collection dictionary is invalid; showing the available PDF content.':''};
}
