"""EA-PDF rule family. Documents/reports are borrowed; the root facade supplies
explicit live dependencies, preserving public patching and ownership contracts.
"""
from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class Dependencies:
    ALLOWED_EAPDF_CONFORMANCE: Any
    BytesIO: Any
    ET: Any
    GENERIC_BAD_MIME: Any
    MAX_TREE_ITEMS: Any
    NS_PDFMAILID: Any
    attachment_candidates_for_embedded: Callable
    attachment_comparison_signature: Callable
    attachment_email_owners: Callable
    attachment_file_names: Callable
    attachment_source_checksum: Callable
    check_ambiguous_source_asset_values: Callable
    check_xmp_record_array: Callable
    collect_file_attachment_annotations: Callable
    collect_marked_content_af_specs: Callable
    collect_object_af_specs: Callable
    defaultdict: Any
    dict_get: Callable
    effective_pdf_version: Callable
    embedded_file_candidates: Callable
    embedded_file_observations: Callable
    evidence_unavailable: Callable
    first_xmp_value: Callable
    flatten_assets: Callable
    flatten_attachments: Callable
    get_af_specs: Callable
    get_declared_pdf_version: Callable
    get_embedded_files: Callable
    identity_fields: Callable
    is_nonneg_int_str: Callable
    is_pdf_array: Callable
    is_xmp_text: Callable
    looks_like_pdf_date: Callable
    map_embedded_by_objgen: Callable
    map_stream_by_objgen: Callable
    media_type_signature: Callable
    nonnegative_integer_equals: Callable
    norm_text: Callable
    normalize_path_tail: Callable
    observed_pdf_value: Callable
    parse_filespec: Callable
    parse_nonneg_int_value: Callable
    pdf_object_identity: Callable
    pikepdf: Any
    re: Any
    read_metadata_stream: Callable
    reconstruct_embedded_media_type: Callable
    represented_email_attachment_presence: Callable
    safe_name: Callable
    source_file_name: Callable
    source_set_supplements: Callable
    xmp_semantic_type: Callable


def check_file_attachment_contents(_services, pdf,report):
    annotations=_services.collect_file_attachment_annotations(pdf)
    code="ANNOTATION_CONTENTS_REQUIRED"
    if not annotations:
        report.na(code,"No FileAttachment annotation requires Contents",{"reason":"No FileAttachment annotations are present."})
    for annotation in annotations:
        details={key:annotation[key] for key in ("page","objgen","contents")}
        if not annotation["contents_is_text"]:
            report.fail(code,"FileAttachment annotation requires a PDF text-string Contents entry",details)
        elif annotation["contents_unavailable"]:
            report.warn(code,"Attachment annotation Contents text could not be decoded",dict(details,assessment="unavailable"))
            report.warn("VALIDATION_INCOMPLETE","Attachment annotation Contents could not be assessed",{"rules":[code],"assessment":"unavailable"})
        else:
            report.ok(code,"FileAttachment annotation contains Contents text",details)

def check_attachment_stream_consistency(_services, attachments, files, report, email_records=(), source_summaries=(), ambiguities=None, owner_scopes=None):
    """Check associated native bytes, independently of body annotations.

    This is bounded internal consistency, not an original-attachment inventory
    or occurrence/owner proof. Equivalent metadata candidates permit the same
    comparisons for each record; size/type never select among differing records.
    A supplied size/checksum/media value contradicted by every possible native payload can
    fail independently while the association remains unavailable.
    """
    comparison_groups={}
    unavailable=[]
    native_files=[fileinfo for fileinfo in files if fileinfo.get("afrelationship") not in ("Source","Alternative")]
    source_checksums={id(att):_services.attachment_source_checksum(att,{"identity":_services.identity_fields(att)},email_records,source_summaries,
                                                      getattr(report,"_email_source_summaries",None))
                      for att in attachments}
    comparison_candidates={}
    possible_files=_services.defaultdict(list)
    attachment_owners={id(att):_services.attachment_email_owners(att,email_records) for att in attachments} if owner_scopes else {}
    for fileinfo in native_files:
        scope=(owner_scopes or {}).get(tuple(fileinfo.get("objgen") or ()))
        scoped_attachments=[att for att in attachments if len(attachment_owners[id(att)])!=1
                            or id(attachment_owners[id(att)][0]) in scope] if scope else attachments
        candidates=_services.attachment_candidates_for_embedded(fileinfo,scoped_attachments,source_checksums)
        for attachment in candidates:
            possible_files[id(attachment)].append(fileinfo)
        if len(candidates)>1:
            signature=_services.attachment_comparison_signature(candidates[0])
            if signature is None or any(_services.attachment_comparison_signature(att)!=signature for att in candidates[1:]):
                unavailable.append({"filenames":sorted(_services.attachment_file_names(fileinfo)),"candidate_count":len(candidates)})
                continue
        comparison_candidates[id(fileinfo)]=len(candidates)
        if candidates:
            key=tuple(id(attachment) for attachment in candidates)
            comparison_groups.setdefault(key,[]).append(fileinfo)
    failures=[]
    media_failures=[]
    parameter_failures=[]
    comparisons=[]
    associations={}
    def check_unavailable_comparison(attachment):
        # Do not choose an occurrence using the field being checked. A failure
        # needs a nonempty candidate set and a contradiction from every member;
        # a missing observation or any compatible value prevents that failure.
        candidates=possible_files[id(attachment)]
        if not candidates:
            declared_media=attachment.get("Content-Type")
            if native_files and _services.is_xmp_text(declared_media) and ";" in declared_media:
                unavailable.append({"filename":attachment.get("Filename"),"xmp_content_type":declared_media,
                    "candidate_count":0,"parameter_comparison_unavailable":True,
                    "reason":"Native files exist but no payload association establishes the declared parameter comparison"})
            return
        mismatches=[]
        declared_size=attachment.get("SizeInBytes")
        if _services.is_nonneg_int_str(declared_size) and all(
                type(f.get("size")) is int and f["size"]>=0
                and not _services.nonnegative_integer_equals(declared_size,f["size"]) for f in candidates):
            mismatches.append("SizeInBytes")
        declared_checksum=attachment.get("CheckSum")
        if _services.is_xmp_text(declared_checksum) and _services.re.fullmatch(r"[0-9A-Fa-f]{32}",declared_checksum) and all(
                isinstance(f.get("md5"),str) and _services.re.fullmatch(r"[0-9A-Fa-f]{32}",f["md5"])
                and declared_checksum.lower()!=f["md5"].lower() for f in candidates):
            mismatches.append("CheckSum")
        declared_media=attachment.get("Content-Type")
        if _services.is_xmp_text(declared_media) and declared_media.strip():
            observed=[]
            for fileinfo in candidates:
                actual=fileinfo.get("reconstructed_media_type")
                if "reconstructed_media_type" not in fileinfo:
                    subtype=fileinfo.get("subtype")
                    parameters=fileinfo.get("media_type_parameters","")
                    if isinstance(subtype,str) and subtype and isinstance(parameters,str):
                        actual=subtype+";"+parameters
                observed.append(actual if isinstance(actual,str) and actual else None)
            detail={"filename":attachment.get("Filename"),"xmp_content_type":declared_media,
                "native_candidates":[{"objgen":f.get("objgen"),"reconstructed_media_type":actual}
                                     for f,actual in zip(candidates,observed)],
                "association_scope":"all_candidate_payloads","unique_occurrence_identity":False}
            if all(actual is not None and _services.media_type_signature(actual)!=_services.media_type_signature(declared_media)
                   for actual in observed):
                media_failures.append(detail)
            if ";" in declared_media:
                expected="application/octet-stream;"+declared_media.split(";",1)[1]
                if all(actual is not None and _services.media_type_signature("application/octet-stream;"+actual.partition(";")[2])
                       !=_services.media_type_signature(expected) for actual in observed):
                    parameter_failures.append(detail)
                else:
                    unavailable.append(dict(detail,parameter_comparison_unavailable=True))
        if mismatches:
            failures.append({"filename":attachment.get("Filename"),
                "xmp_size":declared_size,"xmp_checksum":declared_checksum,
                "native_candidates":[{"objgen":f.get("objgen"),"size":f.get("size"),"checksum":f.get("md5")}
                                     for f in candidates],
                "mismatched_fields":mismatches,"association_scope":"all_candidate_payloads",
                "unique_occurrence_identity":False})
    for attachment in attachments:
        candidates=[fileinfo for record_ids,group in comparison_groups.items()
                    if id(attachment) in record_ids for fileinfo in group]
        if not candidates:
            check_unavailable_comparison(attachment)
            if native_files:
                unavailable.append({"filename":attachment.get("Filename"),"reason":"No unique native payload association established"})
            continue
        observations={(fileinfo.get("size"),fileinfo.get("md5"),_services.media_type_signature(
            fileinfo.get("reconstructed_media_type",str(fileinfo.get("subtype",""))+
                         (";"+fileinfo["media_type_parameters"] if fileinfo.get("media_type_parameters") else ""))))
                      for fileinfo in candidates}
        if len(observations)>1:
            check_unavailable_comparison(attachment)
            unavailable.append({"filename":attachment.get("Filename"),"candidate_count":len(candidates),
                                "reason":"Multiple native payloads have different size/checksum/media observations"})
            continue
        fileinfo=candidates[0]
        size,checksum=fileinfo.get("size"),_services.norm_text(fileinfo.get("md5","")).lower()
        if size is None or not _services.re.fullmatch(r"[0-9a-f]{32}",checksum):
            check_unavailable_comparison(attachment)
            unavailable.append({"filename":attachment.get("Filename"),"reason":"Native payload observations unavailable"})
            continue
        for candidate in candidates:
            # The caller only needs media-type evidence. A projection avoids
            # returning an arbitrary owner's record for equivalent candidates.
            associations[id(candidate)]=(attachment if comparison_candidates[id(candidate)]==1
                                         else {"Content-Type":attachment["Content-Type"]})
        detail={"filename":attachment.get("Filename"),"native_files":[f.get("objgen") for f in candidates],
                "xmp_size":attachment.get("SizeInBytes"),"actual_size":size,
                "xmp_checksum":attachment.get("CheckSum"),"actual_checksum":checksum}
        if any(comparison_candidates[id(candidate)]>1 for candidate in candidates):
            detail.update(association_scope="equivalent_metadata_comparisons",unique_occurrence_identity=False)
        mismatches=[]
        if _services.is_nonneg_int_str(attachment.get("SizeInBytes")) and not _services.nonnegative_integer_equals(attachment["SizeInBytes"],size):
            mismatches.append("SizeInBytes")
        declared=attachment.get("CheckSum")
        if _services.is_xmp_text(declared) and _services.re.fullmatch(r"[0-9A-Fa-f]{32}",declared) and declared.lower()!=checksum:
            mismatches.append("CheckSum")
        comparisons.append(detail)
        if mismatches:
            failures.append(dict(detail,mismatched_fields=mismatches))
    for field,code in (("SizeInBytes","ATTACHMENT_XMP_SIZE_MATCH"),("CheckSum","ATTACHMENT_XMP_CHECKSUM_MATCH")):
        bad=[detail for detail in failures if field in detail["mismatched_fields"]]
        comparable=[detail for detail in comparisons if
                    (_services.is_nonneg_int_str(detail.get("xmp_size")) if field=="SizeInBytes" else
                     isinstance(detail.get("xmp_checksum"),str) and _services.re.fullmatch(r"[0-9A-Fa-f]{32}",detail["xmp_checksum"]))]
        if bad:
            report.fail(code,"Attachment XMP "+field+" differs from associated native bytes",{"field":field,"actual":bad})
        elif comparable:
            report.ok(code,"Attachment XMP "+field+" agrees with associated native bytes",{"field":field,"comparisons":comparable})
        if unavailable and (field=="SizeInBytes" or any("CheckSum" in attachment for attachment in attachments)):
            report.warn(code,"Native-byte comparison is unavailable for unresolved associations",{"field":field,"assessment":"unavailable","actual":unavailable})
        elif field=="CheckSum" and not any("CheckSum" in attachment for attachment in attachments):
            report.na(code,"No optional attachment CheckSum is supplied")
    if media_failures:
        report.fail("EMBEDDED_MEDIA_TYPE_MATCH_XMP","Attachment XMP media type contradicts every possible native payload",{"actual":media_failures})
    if parameter_failures:
        report.fail("EMBEDDED_MEDIA_TYPE_MATCH_XMP","Attachment XMP media parameters contradict every possible native payload",{"actual":parameter_failures})
    if unavailable:
        report.warn("VALIDATION_INCOMPLETE","Attachment native-byte consistency could not be fully verified",{"assessment":"unavailable"})
    if ambiguities is not None:
        ambiguities.extend(unavailable)
    return associations

def check_source_file_sets(_services, pdf,root_xml,afiles,report,expected_sets=None):
    """expected_sets maps a primary PDF filename to required companion filenames.

    Supply the original inventory when available: a PDF alone cannot establish
    that a completely unrecorded companion ever existed. Multiple Source AFs
    are independent assets unless there is actual file-set evidence.
    """
    sources={}
    related_streams={}
    errors=[]
    unavailable=[]
    unknown_names=_services.defaultdict(int)
    declarations=0
    for spec in _services.dict_get(pdf.Root,"/AF",[]) or []:
        if _services.safe_name(_services.dict_get(spec,"/AFRelationship",None))!="Source":
            continue
        name=str(_services.dict_get(spec,"/UF",_services.dict_get(spec,"/F","")))
        related=set()
        sources[name]=related
        rf=_services.dict_get(spec,"/RF",None)
        if rf is None:
            continue
        declarations+=1
        ef=_services.dict_get(spec,"/EF",None)
        if not isinstance(rf,_services.pikepdf.Dictionary) or not rf or not isinstance(ef,_services.pikepdf.Dictionary) \
                or _services.safe_name(_services.dict_get(spec,"/Type",None))!="Filespec" or not spec.is_indirect:
            errors.append({"primary":name,"error":"RF requires an indirect Filespec and RF/EF dictionaries"})
            continue
        for key,array in rf.items():
            if key not in ef or not isinstance(ef[key],_services.pikepdf.Stream) or not _services.is_pdf_array(array) \
                    or not array or len(array)%2 or len(array)>_services.MAX_TREE_ITEMS*2:
                errors.append({"primary":name,"key":key,"error":"RF requires an EF key and nonempty filename/stream pairs"})
                continue
            for index in range(0,len(array),2):
                filename,stream=array[index],array[index+1]
                if not isinstance(filename,_services.pikepdf.String) or not isinstance(stream,_services.pikepdf.Stream) \
                        or _services.safe_name(_services.dict_get(stream,"/Type",None))!="EmbeddedFile":
                    errors.append({"primary":name,"key":key,"pair":index//2,"error":"Invalid related filename or embedded stream"})
                    continue
                try:
                    decoded=str(filename)
                except UnicodeError as error:
                    decoded=None
                    unknown_names[name]+=1
                    unavailable.append({"primary":name,"key":key,"pair":index//2,
                                        "filename_bytes":bytes(filename).hex(),"reason":str(error)})
                if decoded is not None:
                    if not decoded.strip():
                        errors.append({"primary":name,"key":key,"pair":index//2,"error":"Invalid related filename or embedded stream"})
                        continue
                    related.add(decoded)
                identity=_services.pdf_object_identity(stream)
                if identity not in related_streams:
                    # An unknown name supplies no filename association evidence.
                    # Retain the stream so checksum/byte/asset checks can proceed.
                    value=filename if decoded is not None else _services.pikepdf.String("")
                    related_streams[identity]=_services.parse_filespec(decoded or "",_services.pikepdf.Dictionary(
                        F=value,UF=value,EF=_services.pikepdf.Dictionary(UF=stream)),source="RF")
                    if decoded is None:
                        related_streams[identity]["filename_unavailable"]=True
    supplements=_services.source_set_supplements(root_xml,afiles)
    supplement_names={f["ufilename"] or f["filename"] for f in supplements}
    for primary,members in (expected_sets or {}).items():
        available=sources.get(primary,set()) | {f["ufilename"] or f["filename"] for f in afiles
            if f["afrelationship"]=="Supplement" and f.get("stream") is not None}
        missing=set(members)-available
        if primary not in sources or len(missing)>unknown_names[primary]:
            details={"primary":primary,"error":"Required source set is not preserved through RF or catalog Supplement"}
            if unknown_names[primary]:
                details.update(missing_candidates=sorted(missing),minimum_missing=len(missing)-unknown_names[primary])
            else:
                details["missing"]=sorted(missing)
            errors.append(details)
    if errors:
        report.fail("SOURCE_FILE_SET","Source email file sets require valid RF entries or all required catalog Supplement members",{"actual":errors})
    elif not unavailable and (declarations or supplements or expected_sets):
        report.ok("SOURCE_FILE_SET","Declared source file-set relationships are valid",{"related_sets":declarations,"supplements":sorted(supplement_names),"original_inventory_supplied":expected_sets is not None})
    elif not unavailable:
        report.na("SOURCE_FILE_SET","No source file set is identified",{"reason":"No RF, source-asset Supplement metadata or original file-set inventory is present. Independent Source assets do not imply a file set; omitted undeclared companions cannot be inferred from the PDF alone."})
    if unavailable:
        report.warn("SOURCE_FILE_SET","Related-file filename comparison is unavailable",{
            "assessment":"unavailable","actual":unavailable,"original_inventory_supplied":expected_sets is not None})
        report.warn("VALIDATION_INCOMPLETE","Required source file-set filename checks could not be completed",{
            "assessment":"unavailable","rules":["SOURCE_FILE_SET"]})
    return related_streams,supplements

def check_collection_content(_services, pdf,efiles,afiles,eapdf_conf,report,related_streams=(),supplements=()):
    if eapdf_conf not in ("c","ci"):
        report.na("COLLECTION_CONTENT_TYPES","Contained-file restrictions apply only to collection profiles")
        return
    sources={f["objgen"] for f in afiles if f["afrelationship"]=="Source"}
    source_members={f["objgen"] for f in supplements}
    invalid=[]
    checked=0
    for file in _services.embedded_file_candidates(pdf,efiles,afiles):
        if file["objgen"] in sources | source_members or file.get("ef_objgen") in related_streams:
            continue
        checked+=1
        try:
            with _services.pikepdf.Pdf.open(_services.BytesIO(file["bytes"])) as child:
                metadata=_services.ET.fromstring(_services.read_metadata_stream(child))
                valid=_services.first_xmp_value(metadata,_services.NS_PDFMAILID,"conformance") in _services.ALLOWED_EAPDF_CONFORMANCE \
                    and _services.first_xmp_value(metadata,_services.NS_PDFMAILID,"version")=="1" \
                    and _services.first_xmp_value(metadata,_services.NS_PDFMAILID,"rev")=="2024"
        except Exception:
            valid=False
        if not valid:
            invalid.append({"filename":file["ufilename"] or file["filename"] or file["tree_name"],"relationship":file["afrelationship"]})
    if invalid:
        report.fail("COLLECTION_CONTENT_TYPES","Every contained file except original source assets must be an EA-PDF",{"actual":invalid})
    else:
        report.ok("COLLECTION_CONTENT_TYPES","Contained files are EA-PDFs or declared original source assets",{"checked":checked})

def check_embedded_files(_services, pdf,root_xml,eapdf_conf,pdfa_part,report):
    efiles=_services.get_embedded_files(pdf)
    afiles=_services.get_af_specs(pdf)
    marked_afiles=_services.collect_marked_content_af_specs(pdf)
    object_afiles=_services.collect_object_af_specs(pdf)
    source_optional_profile = eapdf_conf in ("si","mi","ci")
    afarr=_services.dict_get(pdf.Root,"/AF",None)
    if efiles:
        report.ok("EMBEDDEDFILES_PRESENT","EmbeddedFiles name tree present",{"count":len(efiles),"files":_services.embedded_file_observations(efiles)})
    elif source_optional_profile and (afarr is None or len(afarr)==0) and not marked_afiles:
        report.ok("EMBEDDEDFILES_PRESENT","EmbeddedFiles name tree absent because this source-optional profile does not embed a source email or other associated files",{"count":len(efiles),"files":[]})
    else:
        report.fail("EMBEDDEDFILES_PRESENT","EmbeddedFiles name tree missing or empty",{"count":len(efiles),"files":[]})
    isolated=eapdf_conf.endswith("i") if eapdf_conf else False
    if isolated:
        if afarr is None:
            report.ok("AF_RULE","Isolated profile may omit catalog AF",{"AF":None})
        else:
            report.ok("AF_RULE","Catalog AF present in isolated profile",{"count":len(afarr),"files":_services.embedded_file_observations(afiles)})
    else:
        if source_optional_profile and (afarr is None or len(afarr)==0):
            report.ok("AF_RULE","Source-optional profile may omit catalog AF when no source email or other associated files are embedded",{"AF":_services.observed_pdf_value(afarr)})
        elif afarr is None or len(afarr)==0:
            report.fail("AF_RULE","Non-isolated profile must have catalog AF with at least one entry",{"AF":_services.observed_pdf_value(afarr)})
        else:
            report.ok("AF_RULE","Catalog AF present for non-isolated profile",{"count":len(afarr),"files":_services.embedded_file_observations(afiles)})
    byspec=_services.map_embedded_by_objgen(efiles)
    bystream=_services.map_stream_by_objgen(efiles)
    _=bystream
    associated_seen=set()
    associated_count=0
    for af in object_afiles + marked_afiles:
        identity=af["objgen"]
        if identity and identity!=(0,0):
            if identity in associated_seen:
                continue
            associated_seen.add(identity)
        associated_count+=1
        for key,stream in af.get("embedded_streams") or [("missing",None)]:
            date=_services.dict_get(_services.dict_get(stream,"/Params",None),"/ModDate",None)
            detail={"filename":af["ufilename"] or af["filename"],"objgen":identity,
                    "association":af["source"],"ef_key":key,"actual":_services.observed_pdf_value(date)}
            if isinstance(date,_services.pikepdf.String) and _services.looks_like_pdf_date(str(date)):
                report.ok("ASSOCIATED_FILE_MODDATE","Associated embedded file has a ModDate PDF date string",detail)
            else:
                report.fail("ASSOCIATED_FILE_MODDATE","Associated embedded file Params requires a ModDate PDF date string",detail)
        if af["objgen"] in byspec:
            report.ok("AF_IN_NAME_TREE","AF file specification also listed in EmbeddedFiles name tree",{"filename":af["ufilename"] or af["filename"] or af["tree_name"],"objgen":af["objgen"]})
        else:
            report.fail("AF_IN_NAME_TREE","AF file specification missing from EmbeddedFiles name tree",{"filename":af["ufilename"] or af["filename"] or af["tree_name"],"objgen":af["objgen"]})
    if not associated_count:
        report.na("ASSOCIATED_FILE_MODDATE","No associated embedded files require ModDate",{"reason":"No AF array or AF marked-content file associations were found."})
    source=[f for f in afiles if f["afrelationship"]=="Source"]
    if isolated:
        if source:
            report.warn("SOURCE_IN_ISOLATED","Isolated source provenance cannot be inferred from associated-file presence",{
                "count":len(source),"files":_services.embedded_file_observations(source),"assessment":"unavailable",
                "reason":"Isolated profiles may retain partial or modified originals. Independent provenance is needed to establish faithful preservation."})
        else: report.ok("SOURCE_IN_ISOLATED","Isolated profile contains no Source AF",{"count":len(source),"files":[]})
    else:
        if source:
            report.ok("SOURCE_AF","Non-isolated profile has Source AF",{"count":len(source),"files":_services.embedded_file_observations(source)})
        elif source_optional_profile:
            report.ok("SOURCE_AF","Source-optional profile may omit the embedded Source AF",{"count":len(source),"files":[]})
        else:
            report.fail("SOURCE_AF","Non-isolated profile must contain at least one Source AF",{"count":len(source),"files":[]})
    for f in _services.embedded_file_candidates(pdf,efiles,afiles):
        name=f["ufilename"] or f["filename"] or f["tree_name"]
        if f["stream"] is None:
            report.fail("EMBEDDED_STREAM","Embedded file specification lacks embedded file stream",{"name":name})
            continue
        for key,stream in f["embedded_streams"]:
            details={"name":name,"ef_key":key}
            if not isinstance(_services.dict_get(stream,"/Subtype",None),_services.pikepdf.Name):
                report.warn("EMBEDDED_MEDIATYPE_RECONSTRUCTION","Media-type reconstruction unavailable until the upstream Subtype requirement is satisfied",dict(details,assessment="unavailable",reason="Subtype is not a PDF name"))
                continue
            try:
                media_type=_services.reconstruct_embedded_media_type(stream)
            except (ValueError,UnicodeError) as error:
                report.fail("EMBEDDED_MEDIATYPE_RECONSTRUCTION","Embedded media type cannot be reconstructed under EA-PDF 9.4",dict(details,reason=str(error)))
            else:
                report.ok("EMBEDDED_MEDIATYPE_RECONSTRUCTION","Embedded media type reconstructed from UTF-8 name and PDF parameter text",dict(details,media_type=media_type))
        parameter_size=_services.dict_get(_services.dict_get(f.get("stream"),"/Params",None),"/Size",None)
        if parameter_size is None:
            report.warn("EMBEDDED_SIZE_RECOMMENDED","Embedded file stream should supply /Size",{"name":name,"actual":len(f["bytes"])})
            report.na("EMBEDDED_SIZE_MATCH","No supplied /Size to compare",{"name":name})
        elif type(parameter_size) is int and len(f["bytes"])==parameter_size:
            report.ok("EMBEDDED_SIZE_RECOMMENDED","Embedded file stream supplies /Size",{"name":name})
            report.ok("EMBEDDED_SIZE_MATCH","Embedded file stream length matches Params /Size",{"name":name,"size":parameter_size})
        else:
            report.ok("EMBEDDED_SIZE_RECOMMENDED","Embedded file stream supplies /Size",{"name":name})
            report.fail("EMBEDDED_SIZE_MATCH","Embedded file stream length does not match integer Params /Size",{"name":name,"size":_services.observed_pdf_value(parameter_size),"actual":len(f["bytes"])})
        (report.ok if f.get("checksum_parameter_present") else report.warn)("EMBEDDED_MD5_RECOMMENDED",
            "Embedded file stream should supply /CheckSum",{"name":name,"present":bool(f.get("checksum_parameter_present"))})
        if f.get("checksum_parameter_present") and f.get("checksum_parameter_valid"):
            if _services.norm_text(f["checksum"]).lower()==f["md5"].lower():
                report.ok("EMBEDDED_MD5_MATCH","Embedded file /CheckSum matches data MD5",{"name":name,"md5":f["md5"]})
            else:
                report.fail("EMBEDDED_MD5_MATCH","Embedded file /CheckSum does not match data MD5",{"name":name,"pdf_checksum":f["checksum"],"actual_md5":f["md5"]})
        else:
            report.na("EMBEDDED_MD5_MATCH","No readable supplied /CheckSum to compare; supplied type is checked separately",{"name":name})
        if f.get("checksum_parameter_present"):
            if f.get("checksum_parameter_valid"):
                report.ok("EMBEDDED_MD5_BINARY_FORMAT","Embedded-file CheckSum parameter contains 16 binary MD5 bytes",{"name":name,"checksum":f["checksum"],"binary_format":f.get("checksum_parameter_valid")})
            else:
                report.fail("EMBEDDED_MD5_BINARY_FORMAT","Embedded-file CheckSum parameter must be a 16-byte PDF string, not hexadecimal text",{"name":name,"checksum":f["checksum"],"binary_format":f.get("checksum_parameter_valid")})
    if "EMBEDDED_STREAM" not in report.seen_codes:
        candidates=_services.embedded_file_candidates(pdf,efiles,afiles)
        if candidates:
            absent=[file.get("filename","") for file in candidates if file.get("stream") is None]
            if absent:
                report.fail("EMBEDDED_STREAM","File specifications require embedded streams",{"files":absent})
            else:
                report.ok("EMBEDDED_STREAM","All inspected file specifications have embedded streams",{"files":len(candidates)})
    if "EMBEDDED_MEDIATYPE_RECONSTRUCTION" not in report.seen_codes:
        report.na("EMBEDDED_MEDIATYPE_RECONSTRUCTION","No readable embedded streams require media-type reconstruction",{"reason":"No embedded file stream was available; required stream presence is evaluated separately."})
    return efiles,afiles

def check_profile_rules(_services, pdf,efiles,afiles,eapdf_conf,pdfa_part,report,root_xml=None):
    if not eapdf_conf:
        report.warn("PROFILE_RULES_SKIPPED","Skipped EA-PDF profile-specific checks because XMP profile declaration is unavailable",{"profile":eapdf_conf})
        return
    report.ok("PROFILE_RULES_SKIPPED","EA-PDF profile-specific checks were applied",{"profile":eapdf_conf})
    root=pdf.Root
    page_mode_value=_services.dict_get(root,"/PageMode",None)
    page_mode=_services.safe_name(page_mode_value)
    page_mode_is_name=isinstance(page_mode_value,_services.pikepdf.Name)
    vp=_services.dict_get(root,"/ViewerPreferences",None)
    nfsm_value=_services.dict_get(vp,"/NonFullScreenPageMode",None)
    nfsm=_services.safe_name(nfsm_value)
    nfsm_is_name=isinstance(nfsm_value,_services.pikepdf.Name)
    non_source=[f for f in efiles if f["afrelationship"]!="Source"]
    has_non_source_embeds=len(non_source)>0
    has_email_attachments=_services.represented_email_attachment_presence(
        root_xml,afiles,getattr(report,"_email_source_summaries",None))
    if eapdf_conf in ("m","mi"):
        if page_mode_is_name and page_mode=="UseOutlines": report.ok("PROFILE_PAGEMODE_MULTI","Profile PageMode correct",{"profile":eapdf_conf,"PageMode":page_mode,"is_pdf_name":page_mode_is_name})
        else: report.fail("PROFILE_PAGEMODE_MULTI","PDF/mail-1{m,mi} must use PageMode UseOutlines",{"profile":eapdf_conf,"PageMode":page_mode,"is_pdf_name":page_mode_is_name})
        if nfsm_is_name and nfsm=="UseOutlines": report.ok("PROFILE_NFSM_MULTI","Profile NonFullScreenPageMode correct",{"profile":eapdf_conf,"NonFullScreenPageMode":nfsm,"is_pdf_name":nfsm_is_name})
        else: report.fail("PROFILE_NFSM_MULTI","PDF/mail-1{m,mi} must use NonFullScreenPageMode UseOutlines",{"profile":eapdf_conf,"NonFullScreenPageMode":nfsm,"is_pdf_name":nfsm_is_name})
    elif eapdf_conf in ("c","ci"):
        if page_mode_is_name and page_mode=="UseAttachments": report.ok("PROFILE_PAGEMODE_CONTAINER","Profile PageMode correct",{"profile":eapdf_conf,"PageMode":page_mode,"is_pdf_name":page_mode_is_name})
        else: report.fail("PROFILE_PAGEMODE_CONTAINER","PDF/mail-1{c,ci} must use PageMode UseAttachments",{"profile":eapdf_conf,"PageMode":page_mode,"is_pdf_name":page_mode_is_name})
        expected_nfsm="UseOutlines" if str(pdfa_part)=="3" else "UseAttachments"
        details={"profile":eapdf_conf,"NonFullScreenPageMode":nfsm,"is_pdf_name":nfsm_is_name,
                 "expected":expected_nfsm,"local_interpretation":"REEXAM-5"}
        if nfsm_is_name and nfsm==expected_nfsm: report.ok("PROFILE_NFSM_CONTAINER","Profile NonFullScreenPageMode correct",details)
        else: report.fail("PROFILE_NFSM_CONTAINER","Collection NonFullScreenPageMode must use the value permitted for its PDF version",details)
        if _services.dict_get(root,"/Collection",None) is not None:
            report.ok("PROFILE_COLLECTION","Container profile has Collection dictionary",{"Collection":_services.observed_pdf_value(root.Collection)})
        else:
            report.fail("PROFILE_COLLECTION","PDF/mail-1{c,ci} should have Collection dictionary",{"Collection":None})
    elif eapdf_conf in ("s","si"):
        if has_email_attachments is None:
            for field,value,is_name,codes,allowed in (
                    ("PageMode",page_mode,page_mode_is_name,
                     ("PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS","PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS"),
                     {"UseOutlines","UseAttachments"}),
                    ("NonFullScreenPageMode",nfsm,nfsm_is_name,
                     ("PROFILE_NFSM_SINGLE_NO_ATTACHMENTS","PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS"),
                     {"UseOutlines"} if pdfa_part==3 else {"UseOutlines","UseAttachments"})):
                invalid=not is_name or value not in allowed
                for code in codes:
                    emit=report.fail if invalid else report.warn
                    emit(code,"Viewer mode violates every possible attachment branch" if invalid else
                         "Viewer attachment condition is unavailable without a matched original",{
                             field:value,"is_pdf_name":is_name,"source_ownership":"unassigned",
                             "assessment":"all_branches_contradicted" if invalid else "unavailable",
                             "possible_values":sorted(allowed)})
            report.warn("VALIDATION_INCOMPLETE","Single-message viewer attachment conditions could not be established")
            return
        use_attachments = has_email_attachments
        expected_pm="UseAttachments" if use_attachments else "UseOutlines"
        if use_attachments:
            if page_mode_is_name and page_mode==expected_pm:
                report.ok("PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS","Profile PageMode consistent with attachment-aware rule",{"profile":eapdf_conf,"PageMode":page_mode,"is_pdf_name":page_mode_is_name,"heuristic_has_non_source_embeds":has_non_source_embeds,"source_email_has_attachments":has_email_attachments})
            else:
                report.fail("PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS","PDF/mail-1{s,si} with attachments must use PageMode UseAttachments",{"profile":eapdf_conf,"PageMode":page_mode,"is_pdf_name":page_mode_is_name,"expected":expected_pm,"heuristic_has_non_source_embeds":has_non_source_embeds,"source_email_has_attachments":has_email_attachments})
        else:
            if page_mode_is_name and page_mode==expected_pm:
                report.ok("PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS","Profile PageMode consistent with no-attachment rule",{"profile":eapdf_conf,"PageMode":page_mode,"is_pdf_name":page_mode_is_name})
            else:
                report.fail("PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS","PDF/mail-1{s,si} without attachments must use PageMode UseOutlines",{"profile":eapdf_conf,"PageMode":page_mode,"is_pdf_name":page_mode_is_name,"expected":expected_pm})
        expect_nfsm="UseOutlines" if not use_attachments else ("UseOutlines" if pdfa_part==3 else "UseAttachments")
        if use_attachments:
            if nfsm_is_name and nfsm==expect_nfsm:
                report.ok("PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS","Profile NonFullScreenPageMode consistent with attachment-aware rule",{"profile":eapdf_conf,"NonFullScreenPageMode":nfsm,"is_pdf_name":nfsm_is_name,"expected":expect_nfsm})
            else:
                report.fail("PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS","PDF/mail-1{s,si} with attachments must use the required NonFullScreenPageMode",{"profile":eapdf_conf,"NonFullScreenPageMode":nfsm,"is_pdf_name":nfsm_is_name,"expected":expect_nfsm,"source_email_has_attachments":has_email_attachments,"pdfa_part":pdfa_part})
        else:
            if nfsm_is_name and nfsm==expect_nfsm:
                report.ok("PROFILE_NFSM_SINGLE_NO_ATTACHMENTS","Profile NonFullScreenPageMode consistent with no-attachment rule",{"profile":eapdf_conf,"NonFullScreenPageMode":nfsm,"is_pdf_name":nfsm_is_name})
            else:
                report.fail("PROFILE_NFSM_SINGLE_NO_ATTACHMENTS","PDF/mail-1{s,si} without attachments must use NonFullScreenPageMode UseOutlines",{"profile":eapdf_conf,"NonFullScreenPageMode":nfsm,"is_pdf_name":nfsm_is_name,"expected":expect_nfsm})

def check_collection_rules(_services, pdf,eapdf_conf,report):
    root=pdf.Root
    collection=_services.dict_get(root,"/Collection",None)
    if eapdf_conf in ("c","ci"):
        if collection is None:
            report.fail("COLLECTION_REQUIRED","Container profile requires Collection dictionary",{"Collection":None})
            return
        report.ok("COLLECTION_REQUIRED","Container profile has Collection dictionary",{"Collection":_services.observed_pdf_value(collection)})
    else:
        if collection is None:
            report.ok("COLLECTION_FORBIDDEN","Non-container profile has no Collection dictionary",{"Collection":None})
        else:
            report.fail("COLLECTION_FORBIDDEN","Non-container profile must not have Collection dictionary",{"Collection":_services.observed_pdf_value(collection)})
        return
    hdr,rv=_services.get_declared_pdf_version(pdf)
    effective=_services.effective_pdf_version(hdr,rv)
    if effective=="1.7":
        ext=_services.dict_get(root,"/Extensions",None)
        adbe=_services.dict_get(ext,"/ADBE",None) if ext is not None else None
        base_value=_services.dict_get(adbe,"/BaseVersion",None) if adbe is not None else None
        base=_services.safe_name(base_value)
        level=_services.dict_get(adbe,"/ExtensionLevel",None) if adbe is not None else None
        if (isinstance(base_value,_services.pikepdf.Name) and base=="1.7"
                and isinstance(level,int) and not isinstance(level,bool) and level==5):
            report.ok("COLLECTION_EXTENSIONS","PDF 1.7 container has required Adobe Extension Level 5 metadata",{"BaseVersion":base,"ExtensionLevel":_services.observed_pdf_value(level)})
        else:
            report.fail("COLLECTION_EXTENSIONS","PDF 1.7 container missing required Adobe Extension Level 5 metadata",{"BaseVersion":base,"ExtensionLevel":str(level)})
    if _services.dict_get(collection,"/D",None) is None:
        report.ok("COLLECTION_D_FORBIDDEN","Collection.D is absent",{"D":None})
    else:
        report.fail("COLLECTION_D_FORBIDDEN","Collection.D must not be present",{"D":_services.observed_pdf_value(collection.D)})
    view=_services.safe_name(_services.dict_get(collection,"/View",None))
    if view=="H":
        report.fail("COLLECTION_VIEW_FORBIDDEN","Collection.View must not be H",{"View":view})
    else:
        report.ok("COLLECTION_VIEW_FORBIDDEN","Collection.View is allowed",{"View":view})
    nav=_services.dict_get(collection,"/Navigator",None)
    if nav is not None and (_services.dict_get(nav,"/SWF",None) is not None or _services.dict_get(nav,"/APIVersion",None) is not None):
        report.fail("COLLECTION_NAVIGATOR_FORBIDDEN","Collection.Navigator must not contain SWF or APIVersion",{"Navigator":_services.observed_pdf_value(nav)})
    else:
        report.ok("COLLECTION_NAVIGATOR_FORBIDDEN","Collection.Navigator contains no forbidden SWF/APIVersion entries",{"Navigator":_services.observed_pdf_value(nav)})
    schema=_services.dict_get(collection,"/Schema",None)
    if schema is None:
        report.fail("COLLECTION_SCHEMA_PRESENT","Collection.Schema missing",{"Schema":None})
        return
    report.ok("COLLECTION_SCHEMA_PRESENT","Collection.Schema present",{"Schema":_services.observed_pdf_value(schema)})
    message_required={"To":"S","From":"S","Sent":"D","Subject":"S","Message-ID":"S","Cc":"S","Bcc":"S","Size":"Size","Attachments":"N"}
    required = message_required
    for name,subtype in required.items():
        field=_services.dict_get(schema,"/"+name,None)
        if field is None:
            report.fail("COLLECTION_SCHEMA_FIELD","Required Collection schema field missing",{"field":name,"actual_subtype":None,"expected_subtype":subtype})
            continue
        actual_value=_services.dict_get(field,"/Subtype",None)
        actual=_services.safe_name(actual_value)
        is_name=isinstance(actual_value,_services.pikepdf.Name)
        if is_name and actual==subtype:
            report.ok("COLLECTION_SCHEMA_FIELD","Required Collection schema field present with correct subtype",{"field":name,"subtype":actual,"is_pdf_name":is_name})
        else:
            report.fail("COLLECTION_SCHEMA_FIELD","Collection schema field has wrong subtype",{"field":name,"expected_subtype":subtype,"actual_subtype":actual,"is_pdf_name":is_name})
    for name,field in schema.items():
        e=_services.dict_get(field,"/E",None)
        if e is None or e is False:
            report.ok("COLLECTION_SCHEMA_E_FALSE","Collection schema field E entry absent or false",{"field":name,"actual":_services.observed_pdf_value(e)})
        else:
            report.fail("COLLECTION_SCHEMA_E_FALSE","Collection schema field E entry must be false",{"field":name,"actual":_services.observed_pdf_value(e)})

def check_asset_metadata(_services, root_xml,efiles,afiles,eapdf_conf,report,related_assets=()):
    if root_xml is None:
        report.warn("ASSET_XMP_SKIPPED","Skipped asset metadata checks because XMP metadata is unavailable",{"xmp_readable":False})
        return [],[]
    report.ok("ASSET_XMP_SKIPPED","Asset metadata checks were applied because XMP metadata was readable",{"xmp_readable":True})
    _services.check_xmp_record_array(root_xml,"assets",report,"ASSET_XMP")
    assets=_services.flatten_assets(root_xml)
    attachments=_services.flatten_attachments(root_xml)
    isolated=eapdf_conf.endswith("i") if eapdf_conf else False
    source_optional_profile = eapdf_conf in ("si","mi","ci")
    src=[f for f in afiles if f["afrelationship"]=="Source"]+_services.source_set_supplements(root_xml,afiles)+list(related_assets)
    if not assets and src and not isolated:
        missing_detail={"source_files":[f["ufilename"] or f["filename"] or f["tree_name"] for f in src]}
        for code, msg in (
            ("ASSET_XMP_FIELDS","Required asset XMP records are missing, so asset field presence cannot be validated"),
            ("ASSET_SIZE_TYPE","Required asset XMP records are missing, so asset SizeInBytes cannot be validated"),
            ("ASSET_CHECKSUM_FORM","Required asset XMP records are missing, so asset CheckSum format cannot be validated"),
            ("ASSET_CONTENTTYPE","Required asset XMP records are missing, so asset ContentType cannot be validated"),
            ("ASSET_MATCH","Required asset XMP records are missing, so embedded Source files cannot be matched to asset records"),
            ("ASSET_MD5_MATCH","Required asset XMP records are missing, so embedded Source file checksums cannot be matched to asset records"),
            ("ASSET_SIZE_MATCH","Required asset XMP records are missing, so embedded Source file sizes cannot be matched to asset records"),
            ("ASSET_CONTENTTYPE_MATCH","Required asset XMP records are missing, so embedded Source file content types cannot be matched to asset records"),
        ):
            report.fail(code, msg, missing_detail)
    if isolated:
        if assets:
            report.ok("ASSET_XMP","Source asset XMP present in isolated profile",{"count":len(assets),"assets":assets})
        elif not src:
            report.ok("ASSET_XMP","Isolated profile omits both embedded Source AF and source asset XMP",{"assets":assets,"source_files":_services.embedded_file_observations(src)})
        else:
            report.warn("ASSET_XMP","Embedded Source AF present in isolated profile but no source asset XMP found",{"source_files":[f["ufilename"] or f["filename"] or f["tree_name"] for f in src]})
    else:
        if assets:
            report.ok("ASSET_XMP","Source asset XMP assets present",{"count":len(assets),"assets":assets})
        elif source_optional_profile and not src:
            report.ok("ASSET_XMP","Source-optional profile omits both embedded Source AF and source asset XMP",{"assets":assets,"source_files":_services.embedded_file_observations(src)})
        else:
            report.fail("ASSET_XMP","Non-isolated profile must include pdfmailmeta:assets in XMP",{"assets":assets})
    for a in assets:
        miss=[k for k in ("Filename","SizeInBytes","ContentType","Checksum") if not _services.norm_text(a.get(k,""))]
        miss=list(dict.fromkeys(miss+[k for k in ("Filename","ContentType","Checksum")
                                     if not _services.is_xmp_text(a.get(k))]))
        if miss:
            report.fail("ASSET_XMP_FIELDS","Asset XMP record requires canonical fields with normal text values",{"asset":a,"missing_or_invalid":miss})
            if "SizeInBytes" in miss:
                report.fail("ASSET_SIZE_TYPE","Asset XMP SizeInBytes missing",{"asset":a})
            if "Checksum" in miss:
                report.fail("ASSET_CHECKSUM_FORM","Asset XMP checksum missing",{"asset":a})
            if "ContentType" in miss:
                report.fail("ASSET_CONTENTTYPE","Asset XMP content type missing",{"asset":a})
            continue
        report.ok("ASSET_XMP_FIELDS","Asset XMP record contains required fields",{"asset":a})
        if _services.is_nonneg_int_str(a.get("SizeInBytes","")):
            report.ok("ASSET_SIZE_TYPE","Asset XMP SizeInBytes is a non-negative integer",{"Filename":a.get("Filename"),"SizeInBytes":a.get("SizeInBytes")})
        else:
            report.fail("ASSET_SIZE_TYPE","Asset XMP SizeInBytes must be a non-negative integer",{"asset":a})
        csum=str(a.get("Checksum",""))
        if _services.re.fullmatch(r"[0-9A-Fa-f]{32}",csum):
            report.ok("ASSET_CHECKSUM_FORM","Asset XMP checksum format valid",{"Filename":a.get("Filename"),"Checksum":csum})
        else:
            report.fail("ASSET_CHECKSUM_FORM","Asset XMP checksum must be 32 hex chars",{"asset":a})
        ctype=_services.norm_text(a.get("ContentType",""))
        report.ok("ASSET_CONTENTTYPE","Asset XMP content type text is present",{"Filename":a.get("Filename"),"ContentType":ctype})
        if ctype in _services.GENERIC_BAD_MIME:
            report.warn("ASSET_CONTENTTYPE_GENERIC","Asset XMP uses discouraged generic media type",{"Filename":a.get("Filename"),"ContentType":ctype})
        num_messages=a.get("NumberMessages","")
        if "NumberMessages" in a:
            if _services.is_nonneg_int_str(num_messages):
                report.ok("ASSET_NUMBERMESSAGES_TYPE","Asset XMP NumberMessages is a non-negative integer",{"Filename":a.get("Filename"),"NumberMessages":str(num_messages)})
            else:
                report.fail("ASSET_NUMBERMESSAGES_TYPE","Supplied asset NumberMessages must be a non-negative integer",{
                    "asset":a,"actual_type":_services.xmp_semantic_type(num_messages)})
    matches=[]
    unmatched_assets=[]
    remaining=list(src)
    ambiguous_sources=set()
    ambiguous_assets=0
    for a in assets:
        found=None
        afn=_services.normalize_path_tail(a.get("Filename",""))
        named=[f for f in remaining if afn and afn==_services.normalize_path_tail(_services.source_file_name(f))]
        checksum=a.get("Checksum","")
        checksummed=[f for f in remaining if _services.is_xmp_text(checksum)
                     and _services.re.fullmatch(r"[0-9A-Fa-f]{32}",checksum)
                     and checksum.lower()==f["md5"].lower()]
        # Original XMP paths and sanitized PDF filenames can collide. A unique
        # checksum match must not be overridden by the first matching basename.
        candidates=checksummed or named
        if len(candidates)==1:
            found=candidates[0]
        elif candidates:
            named_candidates=[f for f in candidates if any(f is item for item in named)]
            if len(named_candidates)==1:
                found=named_candidates[0]
            elif all(f["bytes"]==candidates[0]["bytes"] and
                     _services.media_type_signature(f.get("reconstructed_media_type",f["subtype"]))==
                     _services.media_type_signature(candidates[0].get("reconstructed_media_type",candidates[0]["subtype"]))
                     for f in candidates):
                # Identical payload/media observations give the same comparisons;
                # this does not establish original filename or occurrence identity.
                found=candidates[0]
            else:
                ambiguous_assets+=1
                ambiguous_sources.update(id(f) for f in candidates)
                report.warn("ASSET_MATCH","Source asset association is ambiguous",{
                    "asset":a,"assessment":"unavailable",
                    "candidates":[f["ufilename"] or f["filename"] or f["tree_name"] for f in candidates]})
                report.warn("VALIDATION_INCOMPLETE","Source asset comparisons require an unambiguous association")
                _services.check_ambiguous_source_asset_values(a,candidates,report)
                if (_services.is_xmp_text(checksum) and _services.re.fullmatch(r"[0-9A-Fa-f]{32}",checksum)
                        and not any(checksum.lower()==f["md5"].lower() for f in src)):
                    report.fail("ASSET_MD5_MATCH","Asset checksum does not match any embedded Source candidate",{
                        "asset":a,"source_checksums":[f["md5"] for f in src]})
                continue
        if found is None:
            unmatched_assets.append(a)
            report.fail("ASSET_MATCH","Could not match XMP asset record to embedded Source file",{"asset":a})
            continue
        remaining.remove(found)
        fn=found["ufilename"] or found["filename"] or found["tree_name"]
        details={"asset_filename":a.get("Filename"),"embedded_filename":fn,"embedded_md5":found["md5"],"embedded_size":found["size"],"embedded_subtype":found["subtype"],"xmp_checksum":a.get("Checksum"),"xmp_size":a.get("SizeInBytes"),"xmp_contenttype":a.get("ContentType")}
        if str(a.get("Checksum","")).lower()==found["md5"].lower():
            report.ok("ASSET_MD5_MATCH","XMP asset checksum matches embedded Source file",details)
        else:
            report.fail("ASSET_MD5_MATCH","XMP asset checksum does not match embedded Source file",dict(details,**{"xmp_checksum":a.get("Checksum")}))
        try:
            if not _services.is_xmp_text(a.get("SizeInBytes")):
                report.warn("ASSET_SIZE_MATCH","Source size comparison is unavailable for an invalid semantic value",{
                    **details,"state":"unavailable","actual_type":_services.xmp_semantic_type(a.get("SizeInBytes")),
                    "reason":"SizeInBytes must first satisfy its simple integer type; the schema violation is reported separately."})
            elif _services.nonnegative_integer_equals(a.get("SizeInBytes"),found["size"]):
                report.ok("ASSET_SIZE_MATCH","XMP asset size matches embedded Source file",details)
            else:
                report.fail("ASSET_SIZE_MATCH","XMP asset size does not match embedded Source file",dict(details,**{"xmp_size":a.get("SizeInBytes")}))
        except Exception:
            report.fail("ASSET_SIZE_MATCH","XMP asset size not comparable",dict(details,**{"xmp_size":a.get("SizeInBytes")}))
        reconstructed=found.get("reconstructed_media_type",found["subtype"])
        details["embedded_media_type"]=reconstructed
        if reconstructed and _services.media_type_signature(a.get("ContentType",""))==_services.media_type_signature(reconstructed):
            report.ok("ASSET_CONTENTTYPE_MATCH","XMP asset content type matches embedded Source file",details)
        else:
            report.fail("ASSET_CONTENTTYPE_MATCH","XMP asset content type does not match embedded Source file",dict(details,**{"xmp_contenttype":a.get("ContentType")}))
        report.ok("ASSET_MATCH","XMP asset record matched an embedded Source file",details)
        matches.append((a,found))
    ambiguous_remaining=sum(id(f) in ambiguous_sources for f in remaining)
    if ambiguous_remaining>ambiguous_assets:
        report.fail("SOURCE_WITHOUT_XMP","There are more unmatched Source files than ambiguous asset records",{
            "unmatched_source_candidates":ambiguous_remaining,"ambiguous_asset_records":ambiguous_assets,
            "minimum_unrepresented_sources":ambiguous_remaining-ambiguous_assets})
    for f in remaining:
        details={"filename":f["ufilename"] or f["filename"] or f["tree_name"],"md5":f["md5"],"size":f["size"],"contenttype":f["subtype"]}
        if id(f) in ambiguous_sources:
            report.warn("SOURCE_WITHOUT_XMP","Source metadata coverage is unavailable because asset association is ambiguous",dict(details,assessment="unavailable"))
        else:
            report.fail("SOURCE_WITHOUT_XMP","Embedded Source file lacks matching XMP asset record",details)
    if any(file["afrelationship"]=="Source" for file in afiles) and "SOURCE_WITHOUT_XMP" not in report.seen_codes:
        report.ok("SOURCE_WITHOUT_XMP","All retained Source files have matching asset metadata")
    return assets,attachments

def check_ambiguous_source_asset_values(_services, asset,candidates,report):
    # A universally contradicted value is invalid even without assigning an
    # owner. Compatible or unavailable observations block that field's failure.
    # Use only the association candidates, never values under test to pick one.
    if not candidates:
        return
    observations=[{"filename":f.get("ufilename") or f.get("filename") or f.get("tree_name"),
                   "size":f.get("size"),
                   "media_type":f.get("reconstructed_media_type",f.get("subtype"))}
                  for f in candidates]
    details={"asset":asset,"source_candidates":observations,
             "association_scope":"all_candidate_sources","unique_occurrence_identity":False}
    size=asset.get("SizeInBytes")
    if _services.is_xmp_text(size) and _services.is_nonneg_int_str(size) and all(
            type(item["size"]) is int and item["size"]>=0
            and not _services.nonnegative_integer_equals(size,item["size"]) for item in observations):
        report.fail("ASSET_SIZE_MATCH","Asset size is contradicted by every possible embedded Source",details)
    media=asset.get("ContentType")
    if _services.is_xmp_text(media) and media.strip() and all(
            isinstance(item["media_type"],str) and item["media_type"]
            and _services.media_type_signature(media)!=_services.media_type_signature(item["media_type"]) for item in observations):
        report.fail("ASSET_CONTENTTYPE_MATCH","Asset media type is contradicted by every possible embedded Source",details)

def check_collection_asset_counts(_services, xroot,afiles,report):
    proof=getattr(report,"_collection_count_proof",{"complete":False,"messages":{}})
    counts=_services.defaultdict(int)
    for source in proof["messages"].values():
        counts[source]+=1
    sources=_services.defaultdict(set)
    for file in afiles:
        if file.get("afrelationship")=="Source" and file.get("objgen") not in (None,(0,0)):
            sources[(str(file.get("md5","")).lower(),file.get("size"))].add(file["objgen"])
    for asset in _services.flatten_assets(xroot) if xroot is not None else []:
        if "NumberMessages" not in asset:
            continue
        if not _services.is_nonneg_int_str(asset["NumberMessages"]):
            report.na("ASSET_NUMBERMESSAGES_MATCH_RENDERED","Rendered count comparison requires the separately checked integer type",{"file":asset.get("Filename")})
            continue
        checksum=asset.get("Checksum");size=_services.parse_nonneg_int_value(asset.get("SizeInBytes"))
        key=(checksum.lower(),size) if _services.is_xmp_text(checksum) else None
        detail={"assertion":"T16-NumberMessages","check":"rendered_message_count","scope":"validated collection children",
                "file":asset.get("Filename"),"declared":asset["NumberMessages"]}
        if not proof["complete"] or key is None or len(sources.get(key,set()))!=1:
            _services.evidence_unavailable(report,"ASSET_NUMBERMESSAGES_MATCH_RENDERED","Validated children do not establish a complete source-bound rendered message count",detail)
            continue
        count=counts.get(key,0);detail["rendered_messages"]=count
        (report.ok if _services.nonnegative_integer_equals(asset["NumberMessages"],count) else report.fail)(
            "ASSET_NUMBERMESSAGES_MATCH_RENDERED","NumberMessages compared with distinct source-bound messages in validated children",detail)
