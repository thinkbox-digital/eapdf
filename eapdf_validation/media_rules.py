"""EA-PDF rule family. Documents/reports are borrowed; the root facade supplies
explicit live dependencies, preserving public patching and ownership contracts.
"""
from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class Dependencies:
    GENERIC_BAD_MIME: Any
    MAX_TREE_ITEMS: Any
    NS_PDFMAILID: Any
    check_attachment_rendering_media: Callable
    collect_body_content_sets: Callable
    declared_content_sets_are_nonbody: Callable
    defaultdict: Any
    dict_get: Callable
    embedded_file_candidates: Callable
    embedded_file_observations: Callable
    evidence_unavailable: Callable
    first_xmp_value: Callable
    flatten_assets: Callable
    flatten_email_records: Callable
    get_dpart_nodes: Callable
    get_embedded_files: Callable
    guid_value: Callable
    identities_match: Callable
    identity_fields: Callable
    independent_content_ownership: Callable
    is_nonneg_int_str: Callable
    is_pdf_array: Callable
    is_xmp_text: Callable
    media_type_signature: Callable
    nonnegative_integer_equals: Callable
    norm_text: Callable
    objgen: Callable
    observed_pdf_value: Callable
    original_attachment_media_types: Callable
    page_objgen_map: Callable
    page_range_from_dpart: Callable
    pikepdf: Any
    re: Any
    safe_name: Callable
    sha256hex: Callable
    source_email_for_record: Callable
    source_file_name: Callable
    summarize_source_emails: Callable


def check_source_media_types(_services, afiles,eapdf_conf,report):
    source=[f for f in afiles if f["afrelationship"]=="Source"]
    if not source:
        if eapdf_conf in ("si","mi","ci"):
            report.ok("SOURCE_MIME_NONE","Source-optional profile omits an embedded Source AF, so no source media type is available to check",{"source_files":_services.embedded_file_observations(source)})
        else:
            report.warn("SOURCE_MIME_NONE","No Source AF files available for media type checks",{"source_files":_services.embedded_file_observations(source)})
        return
    for f in source:
        ct=_services.norm_text(f["subtype"])
        name=f["ufilename"] or f["filename"] or f["tree_name"]
        if ct in _services.GENERIC_BAD_MIME:
            report.warn("SOURCE_MIME_GENERIC","Source file uses discouraged generic media type",{"filename":name,"contenttype":ct})
        elif ct:
            report.ok("SOURCE_MIME_TYPE","Source file media type present",{"filename":name,"contenttype":ct})
        else:
            report.fail("SOURCE_MIME_TYPE","Source file media type missing",{"filename":name,"contenttype":ct})

def check_rendered_asset_counts(_services, xroot,afiles,report,ownership):
    records=_services.flatten_email_records(xroot) if xroot is not None else []
    bindings=getattr(report,"_email_source_summaries",{})
    sources=[file for file in afiles if file.get("afrelationship")=="Source"]
    by_checksum=_services.defaultdict(dict)
    for source in sources:
        if source.get("objgen") not in (None,(0,0)):
            by_checksum[str(source.get("md5","")).lower()][source["objgen"]]=source
    all_bound=ownership["complete"] and bool(records) and all(
        bindings.get(index) is not None and _services.guid_value(record.get("Mail_GUID","")) in ownership["rendered_guids"]
        for index,record in enumerate(records,1))
    counts=_services.defaultdict(int)
    if all_bound:
        for index in range(1,len(records)+1):
            summary=bindings[index]
            counts[(summary.get("source_objgen"),summary.get("source_sha256"))]+=1
    for asset in _services.flatten_assets(xroot) if xroot is not None else []:
        if "NumberMessages" not in asset:
            continue
        if not _services.is_nonneg_int_str(asset["NumberMessages"]):
            report.na("ASSET_NUMBERMESSAGES_MATCH_RENDERED","Rendered count comparison requires the separately checked integer type",{"file":asset.get("Filename")})
            continue
        checksum=asset.get("Checksum")
        unique=by_checksum.get(checksum.lower(),{}) if _services.is_xmp_text(checksum) and _services.re.fullmatch(r"[0-9a-fA-F]{32}",checksum) else {}
        details={"assertion":"T16-NumberMessages","check":"rendered_message_count","file":asset.get("Filename"),"declared":asset["NumberMessages"]}
        known=len(unique)==1 and all_bound
        if not known:
            _services.evidence_unavailable(report,"ASSET_NUMBERMESSAGES_MATCH_RENDERED","The integer value does not establish this source's successfully rendered message count",details)
            continue
        source=next(iter(unique.values()))
        checksum=source.get("sha256") or _services.sha256hex(source.get("bytes",b""))
        count=counts.get((source["objgen"],checksum),0)
        details["rendered_messages"]=count
        (report.ok if _services.nonnegative_integer_equals(asset["NumberMessages"],count) else report.fail)(
            "ASSET_NUMBERMESSAGES_MATCH_RENDERED","NumberMessages compared with independently bound, rendered email records",details)

def check_native_media_parameters(_services, file,expected,report,evidence="associated XMP"):
    supplied=_services.dict_get(file.get("stream"),"/Mail_MediaTypeParameters",None)
    detail={"file":_services.source_file_name(file),"supplied":_services.observed_pdf_value(supplied),"evidence":evidence}
    if expected is None:
        code="EMBEDDED_MEDIATYPE_PARAMETERS_TYPE"
        if supplied is None:
            report.na(code,"No Mail_MediaTypeParameters value is supplied",detail)
        else:
            (report.ok if isinstance(supplied,_services.pikepdf.String) else report.fail)(code,"Supplied Mail_MediaTypeParameters must be a PDF text string",detail)
        return
    # Agreement with stored XMP belongs to the complete reconstructed media rule.
    if evidence!="original attachment bytes":
        return
    wanted=expected.partition(";")[2] if isinstance(expected,str) else ""
    detail["expected_parameters"]=wanted
    if not wanted.strip():
        for code in ("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED","SOURCE_MEDIATYPE_PARAMETERS_MATCH"):
            report.na(code,"The independently matched original supplies no media parameters",detail)
        return
    (report.ok if supplied is not None else report.fail)("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED",
        "Original media parameters require Mail_MediaTypeParameters on the embedded stream",detail)
    if supplied is None or not isinstance(supplied,_services.pikepdf.String):
        report.na("SOURCE_MEDIATYPE_PARAMETERS_MATCH","Original parameter value comparison requires a supplied PDF string; presence and type are reported separately",detail)
        return
    try:
        actual=str(supplied)
    except (ValueError,UnicodeError,_services.pikepdf.PdfError):
        _services.evidence_unavailable(report,"SOURCE_MEDIATYPE_PARAMETERS_MATCH","The stored media-parameter string cannot be decoded",detail)
        return
    (report.ok if _services.media_type_signature("application/octet-stream;"+wanted)==_services.media_type_signature("application/octet-stream;"+actual) else report.fail)(
        "SOURCE_MEDIATYPE_PARAMETERS_MATCH","Stored media parameters compared with independently associated original attachment bytes",detail)

def check_body_media_types(_services, pdf,xroot,afiles,report,profile=None):
    code="DPM_BODY_MEDIA_TYPE_MATCH_SOURCE"
    profile=profile or (_services.first_xmp_value(xroot,_services.NS_PDFMAILID,"conformance") if xroot is not None else "")
    sources=_services.summarize_source_emails(afiles)
    bindings=getattr(report,"_email_source_summaries",None)
    _root,_rootnode,nodes=_services.get_dpart_nodes(pdf)
    evidence=_services.independent_content_ownership(pdf,xroot,report)
    records=_services.flatten_email_records(xroot) if xroot is not None else []
    original_omitted=str(profile).endswith("i") and not any(file.get("afrelationship")=="Source" for file in afiles)
    attachment_nodes=[node for node in nodes if not node["child_count"] and _services.safe_name(node["dpm"].get("Mail_ContentSetType"))=="AttachmentRendering"]
    _services.check_attachment_rendering_media(pdf,records,sources,bindings,attachment_nodes,evidence,report,afiles,original_omitted)
    if _services.declared_content_sets_are_nonbody(pdf,nodes):
        report.na(code,"No declared BodyRendering Content Set requires a body-media comparison",
                  {"assertion":"T8-SUBTYPE","check":"body_source_media_type","reason":"Every declared Content Set has a known non-body type and a valid page range; no BodyRendering DPart is declared."})
        return
    if original_omitted:
        report.na(code,"Original body-media comparison was not performed: isolated output omits original sources",
                  {"assertion":"T8-SUBTYPE","check":"body_source_media_type","scope":"original-source comparison only"})
        return
    if xroot is None or not sources or not records:
        _services.evidence_unavailable(report,code,"No inspectable source body is available for an applicable media-type comparison",
                             {"assertion":"T8-SUBTYPE","check":"body_source_media_type","xmp_readable":xroot is not None,"inspectable_sources":len(sources)})
        return
    bodies=_services.collect_body_content_sets(pdf,nodes)
    checked=0
    comparisons=[]
    unresolved=[]
    failures=[]
    for record in records:
        source=_services.source_email_for_record(record,records,sources,bindings)
        expected=set(source.get("body_media_types",[])) if source else set()
        relevant=[body for body in bodies if _services.identities_match(record,body["identity"])]
        if not expected or not relevant:
            unresolved.append(_services.identity_fields(record))
            continue
        for body in relevant:
            independent=evidence["owners"].get(body.get("objgen"),set())
            if not body.get("subtype") or independent!={_services.guid_value(record.get("Mail_GUID",""))}:
                unresolved.append(_services.identity_fields(record))
                continue
            checked+=1
            comparisons.append({"identity":_services.identity_fields(record),"pages":body["pages"],"actual":body["subtype"],"source_media_types":sorted(expected)})
            if _services.media_type_signature(body["subtype"]) not in {_services.media_type_signature(value) for value in expected}:
                failures.append({"identity":_services.identity_fields(record),"pages":body["pages"],"actual":body["subtype"],"source_media_types":sorted(expected)})
    if failures:
        report.fail(code,"BodyRendering media type differs from every relevant source body representation",{"assertion":"T8-SUBTYPE","check":"body_source_media_type","actual":failures})
    elif unresolved:
        _services.evidence_unavailable(report,code,"Some source body media types could not be compared unambiguously",{"assertion":"T8-SUBTYPE","check":"body_source_media_type","actual":unresolved})
    else:
        report.ok(code,"BodyRendering media types agree with inspectable source body representations",{"assertion":"T8-SUBTYPE","check":"body_source_media_type","content_sets":checked,"comparisons":comparisons})

def check_attachment_rendering_media(_services, pdf,records,sources,bindings,nodes,ownership,report,afiles,original_omitted=False):
    code="DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE"
    if not nodes:
        detail={"assertion":"T8-SUBTYPE","check":"attachment_source_media_type"}
        if _services.declared_content_sets_are_nonbody(pdf,ownership["nodes"],"AttachmentRendering"):
            report.na(code,"No AttachmentRendering content set activates the attachment-source comparison",detail)
        elif original_omitted:
            report.na(code,"Original attachment-rendering comparison was not performed: isolated output omits original sources",detail)
        else:
            _services.evidence_unavailable(report,code,"The Content Set inventory cannot establish whether an attachment-rendering comparison applies",detail)
        return
    candidates=getattr(report,"_embedded_media_candidates",None)
    if candidates is None:
        candidates=_services.embedded_file_candidates(pdf,_services.get_embedded_files(pdf),afiles)
    by_spec=_services.defaultdict(list)
    for file in candidates:
        by_spec[file.get("objgen")].append(file)
    page_map=_services.page_objgen_map(pdf);work=0
    for node in nodes:
        detail={"assertion":"T8-SUBTYPE","check":"attachment_source_media_type","objgen":node["objgen"]}
        if original_omitted:
            report.na(code,"Original attachment-rendering comparison was not performed: isolated output omits original sources",detail)
            continue
        owners=ownership["owners"].get(node["objgen"],set())
        matches=[record for record in records if _services.guid_value(record.get("Mail_GUID","")) in owners] if len(owners)==1 else []
        source=_services.source_email_for_record(matches[0],records,sources,bindings) if len(matches)==1 else None
        raw=node["dpm"].get("Mail_Subtype")
        subtype=_services.safe_name(raw) if isinstance(raw,_services.pikepdf.Name) else ""
        bounds=_services.page_range_from_dpart(node,page_map)
        detail["declared"]=subtype
        if not source or not subtype or not bounds:
            _services.evidence_unavailable(report,code,"Attachment rendering needs an independently owned source and readable subtype",detail)
            continue
        associated=set();contexts=[node["node"],_services.dict_get(node["node"],"/DPM",None)]
        for number in range(bounds[0],bounds[1]+1):
            work+=1
            if work>_services.MAX_TREE_ITEMS:
                _services.evidence_unavailable(report,code,"Attachment-rendering inspection reached its limit",detail);return
            contexts.append(pdf.pages[number-1].obj)
        for context in contexts:
            associated_files=_services.dict_get(context,"/AF",[])
            if not _services.is_pdf_array(associated_files) and associated_files!=[]:
                _services.evidence_unavailable(report,code,"Attachment-rendering source association is not an AF array",detail)
                associated.clear();break
            for spec in associated_files or []:
                work+=1
                if work>_services.MAX_TREE_ITEMS:
                    _services.evidence_unavailable(report,code,"Attachment-rendering inspection reached its limit",detail);return
                if _services.safe_name(_services.dict_get(spec,"/AFRelationship",None))=="Source":
                    associated.add(_services.objgen(spec))
        types=set()
        for spec in associated:
            for file in by_spec.get(spec,[]):
                types.update(_services.original_attachment_media_types(file,source))
        if len(types)==1 and next(iter(types)):
            expected=next(iter(types));detail["source_type"]=expected
            (report.ok if _services.media_type_signature(subtype)==_services.media_type_signature(expected) else report.fail)(
                code,"AttachmentRendering subtype compared with original payload bytes explicitly associated with its content",detail)
        else:
            originals=source.get("mime_attachments",source.get("attachments",[]))
            work+=len(originals)
            if work>_services.MAX_TREE_ITEMS:
                _services.evidence_unavailable(report,code,"Attachment-rendering source comparison reached its limit",detail);return
            contradicted=bool(originals) and source.get("attachment_inventory_available",True) and all(
                item.get("content_type") and _services.media_type_signature(subtype)!=_services.media_type_signature(item["content_type"]) for item in originals)
            if contradicted:
                report.fail(code,"AttachmentRendering subtype contradicts every attachment in its independently established original email",detail)
            else:
                _services.evidence_unavailable(report,code,"No unique original payload association establishes the attachment-rendering media type",detail)
