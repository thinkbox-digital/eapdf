"""EA-PDF rule family. Documents/reports are borrowed; the root facade supplies
explicit live dependencies, preserving public patching and ownership contracts.
"""
from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class Dependencies:
    ALLOWED_EAPDF_CONFORMANCE: Any
    ALLOWED_PDFA: Any
    NS_DC: Any
    NS_PDF: Any
    NS_PDFAID: Any
    NS_PDFMAIL: Any
    NS_PDFMAILID: Any
    NS_PDFMAILMETA: Any
    NS_XMP: Any
    XmpArray: Any
    XmpUri: Any
    chain: Any
    check_single_source_xmp_semantics: Callable
    datetime_values_match: Callable
    dict_get: Callable
    evidence_unavailable: Callable
    find_xmp_values: Callable
    first_message_xmp_value: Callable
    first_present: Callable
    first_xmp_value: Callable
    flatten_attachments: Callable
    flatten_email_records: Callable
    guid_value: Callable
    is_nonneg_int_str: Callable
    is_xmp_text: Callable
    local: Callable
    looks_like_xmp_date: Callable
    looks_like_xmp_datetime: Callable
    nonnegative_integer_equals: Callable
    norm_text: Callable
    observed_pdf_value: Callable
    pdf_object_identity: Callable
    pikepdf: Any
    qn: Callable
    read_docinfo: Callable
    read_stream: Any
    safe_name: Callable
    stream_setting: Any
    summarize_source_email: Callable
    summarize_source_emails: Callable
    text_values_match: Callable
    utf8_xmp_error: Callable
    xmp_default_language_values: Callable
    xmp_descriptions: Callable
    xmp_document_property_values: Callable
    xmp_property_nodes: Callable
    xmp_semantic_type: Callable
    xmp_semantic_value: Callable


def check_xmp_utf8(_services, pdf,report):
    streams={}
    for obj in _services.chain((pdf.Root,),pdf.objects):
        if isinstance(obj,(_services.pikepdf.Dictionary,_services.pikepdf.Stream)):
            metadata=_services.dict_get(obj,"/Metadata",None)
            if isinstance(metadata,_services.pikepdf.Stream):
                streams[_services.pdf_object_identity(metadata)]=metadata
            if isinstance(obj,_services.pikepdf.Stream) and _services.safe_name(_services.dict_get(obj,"/Type",None))=="Metadata":
                streams[_services.pdf_object_identity(obj)]=obj
    if not streams:
        report.fail("XMP_UTF8","No XMP metadata stream is available to verify UTF-8 encoding")
        return
    failures=[]
    for identity,stream in streams.items():
        try:
            error=_services.utf8_xmp_error(_services.read_stream(stream, _services.stream_setting("VALIDATOR_MAX_METADATA_BYTES", 16 * 1024**2)))
        except Exception as exception:
            error="Cannot decode metadata stream: "+str(exception)
        if error:
            failures.append({"object":identity,"error":error})
    if failures:
        report.fail("XMP_UTF8","All XMP streams must use UTF-8, including PDF/A-3 files",{"actual":failures})
    else:
        report.ok("XMP_UTF8","All discovered XMP metadata streams use UTF-8",{"count":len(streams)})

def check_xmp_record_array(_services, root,prop,report,code,required=False):
    values=[_services.xmp_semantic_value(node) for node in _services.xmp_property_nodes(root,_services.NS_PDFMAILMETA,prop)]
    values.extend(desc.get(_services.qn(_services.NS_PDFMAILMETA,prop)) for desc in _services.xmp_descriptions(root)
                  if _services.qn(_services.NS_PDFMAILMETA,prop) in desc.attrib)
    if required and not values:
        report.fail(code,f"Document XMP must include {prop} as an unordered rdf:Bag, even when empty",
                    {"property":prop,"present":False})
    elif any(not isinstance(value,_services.XmpArray) or value.kind!="Bag"
           or any(not isinstance(member,dict) for member in value) for value in values):
        report.fail(code,f"Supplied XMP {prop} must be an unordered array of structured records",
                    {"array_kinds":[getattr(value,"kind",None) for value in values]})
    elif required:
        report.ok(code,f"Document XMP includes {prop} as an unordered rdf:Bag; an empty Bag is permitted",
                  {"property":prop,"records":sum(len(value) for value in values)})

def check_pdfa_xmp(_services, root_xml,report):
    part=_services.first_xmp_value(root_xml,_services.NS_PDFAID,"part")
    conf=_services.first_xmp_value(root_xml,_services.NS_PDFAID,"conformance")
    if str(part) in ("3","4"):
        report.ok("PDFA_PART","pdfaid:part present and allowed",{"part":str(part)})
    else:
        report.fail("PDFA_PART","pdfaid:part must be 3 or 4",{"part":part})
    cu=str(conf).upper() if conf is not None else ""
    if (int(part) if str(part).isdigit() else None, cu if cu else None) in _services.ALLOWED_PDFA:
        report.ok("PDFA_CONFORMANCE","pdfaid:conformance allowed for declared part",{"part":str(part),"conformance":cu})
    else:
        report.fail("PDFA_CONFORMANCE","Disallowed PDF/A conformance for EA-PDF",{"part":str(part),"conformance":cu})
    return (int(part) if str(part).isdigit() else None), cu

def check_eapdf_xmp(_services, root_xml,nsmap,report):
    need={_services.NS_PDFMAIL:"pdfmail",_services.NS_PDFMAILID:"pdfmailid",_services.NS_PDFMAILMETA:"pdfmailmeta"}
    for uri,pfx in need.items():
        if uri in nsmap.values():
            actual=[k for k,v in nsmap.items() if v==uri]
            if pfx in actual:
                report.ok("XMP_NS_PREFIX","Required EA-PDF namespace and prefix present",{"uri":uri,"prefix":pfx})
            else:
                report.fail("XMP_NS_PREFIX","EA-PDF namespace present with wrong prefix",{"uri":uri,"expected_prefix":pfx,"actual_prefixes":actual})
        else:
            report.fail("XMP_NS_PREFIX","Required EA-PDF namespace missing",{"uri":uri,"expected_prefix":pfx})
    creator=_services.find_xmp_values(root_xml,_services.NS_DC,"creator")
    creator_arrays=_services.xmp_document_property_values(root_xml,_services.NS_DC,"creator")
    first_creators=[value[0] if isinstance(value,_services.XmpArray) and value else "" for value in creator_arrays]
    producer_values=_services.xmp_document_property_values(root_xml,_services.NS_PDF,"Producer")
    cdate_values=_services.xmp_document_property_values(root_xml,_services.NS_XMP,"CreateDate")
    producer=producer_values[0] if producer_values else ""
    cdate=cdate_values[0] if cdate_values else ""
    title_values=_services.xmp_default_language_values(root_xml,_services.NS_DC,"title")
    title=title_values[0] if len(title_values)==1 else ""
    creator_details={"value":creator,"first_values":first_creators,
                     "types":[_services.xmp_semantic_type(value) for value in creator_arrays]}
    if creator_arrays and all(isinstance(value,_services.XmpArray) and value.kind=="Seq" and value
            and all(_services.is_xmp_text(name) for name in value) and _services.norm_text(value[0]) for value in creator_arrays):
        report.ok("XMP_CREATOR","Ordered dc:creator entity names include the required populated first item; attribution is checked separately",creator_details)
    else:
        report.fail("XMP_CREATOR","EA-PDF requires ordered dc:creator names with a populated first item",creator_details)
    producer_details={"value":producer,"values":producer_values,
                      "types":[_services.xmp_semantic_type(value) for value in producer_values]}
    if producer_values and all(_services.is_xmp_text(value) and _services.norm_text(value) for value in producer_values):
        report.ok("XMP_PRODUCER","EA-PDF creation software field pdf:Producer is normal Text",producer_details)
    else:
        report.fail("XMP_PRODUCER","EA-PDF requires pdf:Producer as normal Text naming the creation tool",producer_details)
    cdate_details={"value":cdate,"values":cdate_values,
                   "types":[_services.xmp_semantic_type(value) for value in cdate_values]}
    if cdate_values and all(_services.looks_like_xmp_date(value) for value in cdate_values):
        report.ok("XMP_CREATEDATE","EA-PDF creation date field xmp:CreateDate is an XMP Date",cdate_details)
    else:
        report.fail("XMP_CREATEDATE","EA-PDF requires xmp:CreateDate as an XMP Date",cdate_details)
    title_details={"value":title,"default_values":title_values,"expected":'dc:title["x-default"] Text item'}
    if len(title_values)==1:
        report.ok("XMP_TITLE","dc:title x-default Text item is set",title_details)
    else:
        report.warn("XMP_TITLE","Recommended dc:title x-default Text item is missing or ambiguous",title_details)
    versions=_services.xmp_document_property_values(root_xml,_services.NS_PDFMAILID,"version")
    revisions=_services.xmp_document_property_values(root_xml,_services.NS_PDFMAILID,"rev")
    conformances=_services.xmp_document_property_values(root_xml,_services.NS_PDFMAILID,"conformance")
    v=versions[0] if versions else ""
    r=revisions[0] if revisions else ""
    c=conformances[0] if conformances else ""
    version_details={"actual":v}
    revision_details={"actual":r}
    if len(versions)!=1 or not _services.is_xmp_text(v):
        version_details.update(values=versions,types=[_services.xmp_semantic_type(value) for value in versions])
    if len(revisions)!=1 or not _services.is_xmp_text(r):
        revision_details.update(values=revisions,types=[_services.xmp_semantic_type(value) for value in revisions])
    if versions and all(_services.is_xmp_text(value) and _services.nonnegative_integer_equals(value,1) for value in versions): report.ok("EAPDF_VERSION","pdfmailid:version is 1",version_details)
    else: report.fail("EAPDF_VERSION","pdfmailid:version must be 1",version_details)
    if revisions and all(_services.is_xmp_text(value) and _services.nonnegative_integer_equals(value,2024) for value in revisions): report.ok("EAPDF_REV","pdfmailid:rev is 2024",revision_details)
    else: report.fail("EAPDF_REV","pdfmailid:rev must be 2024",revision_details)
    if conformances and all(_services.is_xmp_text(value) and value in _services.ALLOWED_EAPDF_CONFORMANCE for value in conformances):
        report.ok("EAPDF_CONFORMANCE_VALUE","pdfmailid:conformance allowed",{"actual":c})
    else:
        report.fail("EAPDF_CONFORMANCE_VALUE","pdfmailid:conformance invalid",{"actual":c,"values":conformances,
                    "types":[_services.xmp_semantic_type(value) for value in conformances],"allowed":sorted(_services.ALLOWED_EAPDF_CONFORMANCE)})
    cstr=c if _services.is_xmp_text(c) else ""
    if "i" not in cstr or (cstr.endswith("i") and cstr.count("i")==1):
        report.ok("EAPDF_CONFORMANCE_I_LAST","pdfmailid:conformance uses i only as a trailing designator",{"actual":c})
    else:
        report.fail("EAPDF_CONFORMANCE_I_LAST","pdfmailid:conformance must use i only as a trailing designator",{"actual":c})
    return str(c)

def check_keywords(_services, pdf,root_xml,pdfa_part,report):
    xmp_keywords=_services.first_xmp_value(root_xml,_services.NS_PDF,"Keywords")
    if "EA-PDF" in str(xmp_keywords):
        report.ok("XMP_KEYWORDS","XMP pdf:Keywords contains EA-PDF",{"value":xmp_keywords})
    else:
        report.fail("XMP_KEYWORDS","XMP pdf:Keywords must include EA-PDF",{"value":xmp_keywords})
    info=_services.read_docinfo(pdf)
    if pdfa_part==3:
        if info:
            report.ok("DOCINFO_PRESENT","Document Info present for PDF/A-3",{"keys":sorted(info.keys()),"values":info})
        else:
            report.fail("DOCINFO_PRESENT","Document Info required for PDF/A-3",{"values":info})
        kw=info.get("Keywords","")
        if "EA-PDF" in kw:
            report.ok("DOCINFO_KEYWORDS","Document Info Keywords contains EA-PDF",{"value":kw})
        else:
            report.fail("DOCINFO_KEYWORDS","Document Info Keywords must include EA-PDF for PDF/A-3",{"value":kw})
    else:
        if info:
            report.warn("DOCINFO_PRESENT","Document Info present in PDF/A-4 based file",{"keys":sorted(info.keys()),"values":info})
        else:
            report.ok("DOCINFO_PRESENT","No Document Info present in PDF/A-4 based file",{"values":info})

def check_docinfo_consistency(_services, pdf,root_xml,pdfa_part,report):
    if pdfa_part!=3: return
    info=_services.read_docinfo(pdf)
    if not info: return
    info_object=_services.dict_get(pdf.trailer,"/Info",None)
    field_codes={"Title":"DOCINFO_TITLE_MATCH","Author":"DOCINFO_AUTHOR_MATCH",
                 "Subject":"DOCINFO_SUBJECT_MATCH","Keywords":"DOCINFO_KEYWORDS",
                 "Creator":"DOCINFO_CREATOR_MATCH","Producer":"DOCINFO_PRODUCER_MATCH",
                 "CreationDate":"DOCINFO_CREATIONDATE_MATCH","ModDate":"DOCINFO_MODDATE_MATCH"}
    for field,code in field_codes.items():
        if field not in info:
            continue
        value=_services.dict_get(info_object,"/"+field,None)
        if value is None:
            # ISO32000-2 7.3.9: null dictionary entries are absent.
            info.pop(field,None)
        elif not isinstance(value,_services.pikepdf.String):
            report.fail(code,"Document Info field must retain its PDF string type",{
                "field":field,"actual":_services.observed_pdf_value(value),"is_pdf_string":False,
                "expected":"PDF string; numbers, names, arrays and dictionaries are not string values"})
            info.pop(field,None)
    title_values=_services.xmp_default_language_values(root_xml,_services.NS_DC,"title")
    subject_values=_services.xmp_default_language_values(root_xml,_services.NS_DC,"description")
    xmp_title=title_values[0] if len(title_values)==1 else None
    xmp_subject=subject_values[0] if len(subject_values)==1 else None
    xmp_producer=_services.first_xmp_value(root_xml,_services.NS_PDF,"Producer")
    xmp_creator=_services.find_xmp_values(root_xml,_services.NS_DC,"creator")
    xmp_creator_tool=_services.first_xmp_value(root_xml,_services.NS_XMP,"CreatorTool")
    xmp_create_date=_services.first_xmp_value(root_xml,_services.NS_XMP,"CreateDate")
    xmp_modify_date=_services.first_xmp_value(root_xml,_services.NS_XMP,"ModifyDate")
    if "Title" in info:
        if xmp_title is not None and _services.text_values_match(info["Title"], xmp_title):
            report.ok("DOCINFO_TITLE_MATCH","Document Info Title matches XMP dc:title",{"title":info["Title"]})
        elif xmp_title is not None:
            report.fail("DOCINFO_TITLE_MATCH","Document Info Title differs from XMP dc:title",{"docinfo":info["Title"],"xmp":xmp_title})
        else:
            report.fail("DOCINFO_TITLE_MATCH","Document Info Title has no matching XMP dc:title",{"docinfo":info["Title"]})
    if "Producer" in info:
        if xmp_producer and _services.text_values_match(info["Producer"], xmp_producer):
            report.ok("DOCINFO_PRODUCER_MATCH","Document Info Producer matches XMP pdf:Producer",{"producer":info["Producer"]})
        elif xmp_producer:
            report.fail("DOCINFO_PRODUCER_MATCH","Document Info Producer differs from XMP pdf:Producer",{"docinfo":info["Producer"],"xmp":xmp_producer})
        else:
            report.fail("DOCINFO_PRODUCER_MATCH","Document Info Producer has no matching XMP pdf:Producer",{"docinfo":info["Producer"]})
    if "Author" in info:
        first=_services.norm_text(xmp_creator[0] if xmp_creator else "")
        if first and _services.text_values_match(info["Author"], first):
            report.ok("DOCINFO_AUTHOR_MATCH","Document Info Author matches first XMP dc:creator",{"author":info["Author"]})
        elif first:
            report.fail("DOCINFO_AUTHOR_MATCH","Document Info Author differs from first XMP dc:creator",{"docinfo":info["Author"],"xmp":first})
        else:
            report.fail("DOCINFO_AUTHOR_MATCH","Document Info Author has no matching XMP dc:creator",{"docinfo":info["Author"]})
    if "Subject" in info:
        if xmp_subject is not None and _services.text_values_match(info["Subject"], xmp_subject):
            report.ok("DOCINFO_SUBJECT_MATCH","Document Info Subject matches XMP dc:description",{"subject":info["Subject"]})
        elif xmp_subject is not None:
            report.fail("DOCINFO_SUBJECT_MATCH","Document Info Subject differs from XMP dc:description",{"docinfo":info["Subject"],"xmp":xmp_subject})
        else:
            report.fail("DOCINFO_SUBJECT_MATCH","Document Info Subject has no matching XMP dc:description",{"docinfo":info["Subject"]})
    if "Creator" in info:
        if xmp_creator_tool and _services.text_values_match(info["Creator"], xmp_creator_tool):
            report.ok("DOCINFO_CREATOR_MATCH","Document Info Creator matches XMP xmp:CreatorTool",{"creator":info["Creator"]})
        elif xmp_creator_tool:
            report.fail("DOCINFO_CREATOR_MATCH","Document Info Creator differs from XMP xmp:CreatorTool",{"docinfo":info["Creator"],"xmp":xmp_creator_tool})
        else:
            report.fail("DOCINFO_CREATOR_MATCH","Document Info Creator has no matching XMP xmp:CreatorTool",{"docinfo":info["Creator"]})
    if "CreationDate" in info:
        if xmp_create_date and _services.datetime_values_match(info["CreationDate"], xmp_create_date):
            report.ok("DOCINFO_CREATIONDATE_MATCH","Document Info CreationDate matches XMP xmp:CreateDate",{"creation_date":info["CreationDate"],"xmp":xmp_create_date})
        elif xmp_create_date:
            report.fail("DOCINFO_CREATIONDATE_MATCH","Document Info CreationDate differs from XMP xmp:CreateDate",{"docinfo":info["CreationDate"],"xmp":xmp_create_date})
        else:
            report.fail("DOCINFO_CREATIONDATE_MATCH","Document Info CreationDate has no matching XMP xmp:CreateDate",{"docinfo":info["CreationDate"]})
    if "ModDate" in info:
        if xmp_modify_date and _services.datetime_values_match(info["ModDate"], xmp_modify_date):
            report.ok("DOCINFO_MODDATE_MATCH","Document Info ModDate matches XMP xmp:ModifyDate",{"mod_date":info["ModDate"],"xmp":xmp_modify_date})
        elif xmp_modify_date:
            report.fail("DOCINFO_MODDATE_MATCH","Document Info ModDate differs from XMP xmp:ModifyDate",{"docinfo":info["ModDate"],"xmp":xmp_modify_date})
        else:
            report.fail("DOCINFO_MODDATE_MATCH","Document Info ModDate has no matching XMP xmp:ModifyDate",{"docinfo":info["ModDate"]})
    if "Keywords" in info:
        values=_services.xmp_document_property_values(root_xml,_services.NS_PDF,"Keywords")
        details={"docinfo":info["Keywords"],"xmp_values":values,
                 "types":[_services.xmp_semantic_type(value) for value in values]}
        if values and all(_services.is_xmp_text(value) and _services.text_values_match(info["Keywords"],value) for value in values):
            report.ok("DOCINFO_KEYWORDS","Document Info Keywords matches XMP pdf:Keywords",details)
        else:
            report.fail("DOCINFO_KEYWORDS","Document Info Keywords requires matching normal XMP pdf:Keywords Text",details)

def check_standard_xmp_semantics(_services, root_xml, afiles, report):
    sources=[source for source in afiles if _services.summarize_source_emails([source])]
    if not sources:
        _services.check_single_source_xmp_semantics(root_xml,[],report)
    for source in sources:
        _services.check_single_source_xmp_semantics(root_xml,[source],report)

def check_single_source_xmp_semantics(_services, root_xml, afiles, report):
    if root_xml is None:
        return
    summary=_services.summarize_source_email(afiles)
    creator_vals=_services.xmp_document_property_values(root_xml,_services.NS_DC,"creator")
    creator=(creator_vals[0][0] if creator_vals and isinstance(creator_vals[0],_services.XmpArray) and creator_vals[0] else "")
    producer=_services.first_xmp_value(root_xml,_services.NS_PDF,"Producer")
    cdate=_services.first_xmp_value(root_xml,_services.NS_XMP,"CreateDate")

    if creator:
        report.warn("XMP_CREATOR_SEMANTICS","dc:creator identifies the responsible person, organization or service; software belongs in xmp:CreatorTool and pdf:Producer. Attribution requires independent creation provenance",{
            "value":creator,"assessment":"unavailable",
            "reason":"A person's, organization's or service's creator name may resemble an email address or match an email sender.",
            "needed_evidence":"Independent evidence of the entity responsible for creating the EA-PDF resource; first-item presence is checked separately."})

    if producer:
        report.warn("XMP_PRODUCER_SEMANTICS","Producer attribution requires independent creation provenance",{
            "value":producer,"assessment":"unavailable",
            "reason":"The PDF creation tool has no required name grammar; mailbox-like text or sender equality does not prove wrong attribution.",
            "needed_evidence":"Independent evidence of the tool that created the PDF; required property presence is checked separately."})

    if cdate:
        report.warn("XMP_CREATEDATE_SEMANTICS","Creation provenance cannot be established from xmp:CreateDate and source Sent alone",{
            "value":cdate,"source_sent":summary.get("sent_raw","") if summary else "",
            "assessment":"unavailable",
            "reason":"Equality, ordering or difference between the dates does not prove which resource or event the timestamp describes.",
            "needed_evidence":"Independent evidence of the EA-PDF creation event; required field presence and PDF/A metadata validation remain separate.",
            "upstream_issue":"https://github.com/pdf-association/report-an-issue/issues/22",
        })

def check_xmp_dependent_unchecked(_services, report,xmp_bytes=b""):
    details={"xmp_readable":False,"metadata_bytes":len(xmp_bytes)}
    report.fail("PDFA_XMP_UNCHECKED","Could not validate PDF/A identification metadata because XMP metadata is missing or unreadable",details)
    report.fail("EAPDF_XMP_UNCHECKED","Could not validate EA-PDF identification metadata because XMP metadata is missing or unreadable",details)
    report.fail("XMP_KEYWORDS_UNCHECKED","Could not validate XMP keywords because XMP metadata is missing or unreadable",details)

def check_required_email_metadata(_services, root_xml,conformance,report):
    if root_xml is None or conformance not in {"s","si","m","mi"}:
        return
    containers=_services.xmp_property_nodes(root_xml,_services.NS_PDFMAILMETA,"email")
    values=[_services.xmp_semantic_value(node) for node in containers]
    container_details={"containers":[[_services.local(child.tag) for child in container] for container in containers],"emails":_services.flatten_email_records(root_xml)}
    container_details["count"]=len(container_details["emails"])
    valid_container=bool(containers) and all(isinstance(value,_services.XmpArray) and value.kind=="Bag"
                             and all(isinstance(member,dict) for member in value) for value in values)
    if not valid_container:
        report.fail("EMAIL_XMP_CONTAINER","Email Core Fields must be stored in an unordered rdf:Bag of email containers",container_details)
    else:
        report.ok("EMAIL_XMP_CONTAINER","Email Core Fields use unordered email containers",container_details)
    records=_services.flatten_email_records(root_xml)
    expected_count=1 if conformance in {"s","si"} else 2
    cardinality={"count":len(records),"profile":conformance,"expected":"exactly one" if expected_count==1 else "at least two"}
    if not valid_container:
        _services.evidence_unavailable(report,"EMAIL_XMP_CARDINALITY","Malformed email containers do not establish a reliable record count",cardinality)
    else:
        valid_count=len(records)>=expected_count and (expected_count!=1 or len(records)==1)
        (report.ok if valid_count else report.fail)("EMAIL_XMP_CARDINALITY","Email metadata count compared with the declared profile",cardinality)
    if not records:
        records=[{"Sent":_services.first_message_xmp_value(root_xml,"Sent"),
                  "Mail_GUID":_services.first_message_xmp_value(root_xml,"Mail_GUID")}]
    identifiers=set()
    attachment_records=_services.flatten_attachments(root_xml)
    # Table 19's GUID is the containing email's generated GUID. Establish a
    # complete typed inventory before proving that a referenced owner is absent.
    email_guids={_services.guid_value(record.get("Mail_GUID")) for record in records}-{''}
    guid_inventory_complete=bool(records) and all(_services.guid_value(record.get("Mail_GUID")) for record in records)
    for attachment in attachment_records:
        guid=attachment.get("Mail_GUID")
        if not _services.is_xmp_text(guid) or guid in email_guids:
            continue
        details={"Filename":attachment.get("Filename"),"Mail_GUID":guid,
                 "known_email_guids":sorted(email_guids-{''}),"complete_guid_inventory":guid_inventory_complete}
        if guid_inventory_complete:
            report.fail("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID",
                        "Attachment Mail_GUID does not identify any email in document XMP",details)
        else:
            report.warn("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID",
                        "Attachment GUID correspondence is unavailable because email GUID metadata is incomplete",
                        dict(details,assessment="unavailable"))
            report.warn("VALIDATION_INCOMPLETE","Attachment GUID owner correspondence could not be assessed",
                        {"rules":["ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID"],"assessment":"unavailable"})
    for index,record in enumerate(records, start=1):
        # Supplied Table 18 types are file-internal requirements. Source absence
        # cannot turn malformed metadata into N/A or merely a recommendation.
        text_fields={"Subject":"MESSAGE_SUBJECT_XMP","Raw-To":"MESSAGE_RAW_TO_XMP",
                     "Raw-From":"MESSAGE_RAW_FROM_XMP","Raw-Cc":"MESSAGE_RAW_CC_XMP",
                     "Raw-Bcc":"MESSAGE_RAW_BCC_XMP","Raw-Sent":"MESSAGE_RAW_SENT_REQUIRED_IF_INVALID",
                     "messageid":"MESSAGE_ID_XMP","Message-ID":"MESSAGE_ID_XMP"}
        for field,code in text_fields.items():
            if field in record:
                valid=_services.is_xmp_text(record[field])
                report.add("PASS" if valid else "FAIL",code,
                           "Supplied email free text has the required simple-string type" if valid else
                           "Supplied email free text must be a normal simple string",{
                               "email":index,"field":field,"value":record[field],"actual_type":_services.xmp_semantic_type(record[field]),
                               "source_comparison":"Not established by this schema check"})
        if "Message-ID" in record and "messageid" in record:
            canonical,legacy=record["Message-ID"],record["messageid"]
            if _services.is_xmp_text(canonical) and _services.is_xmp_text(legacy) and canonical!=legacy:
                report.fail("MESSAGE_ID_XMP","Message-ID aliases contain conflicting identifiers",
                            {"email":index,"Message-ID":canonical,"messageid":legacy})
        if "In-Reply-To" in record:
            replies=record["In-Reply-To"]
            valid=isinstance(replies,_services.XmpArray) and replies.kind=="Bag" and all(_services.is_xmp_text(value) for value in replies)
            report.add("PASS" if valid else "FAIL","MESSAGE_INREPLYTO_XMP",
                       "Reply identifiers use an unordered Bag of text strings" if valid else
                       "In-Reply-To must be an unordered rdf:Bag of normal text strings",
                       {"email":index,"value":replies,"actual_type":_services.xmp_semantic_type(replies),
                        "local_interpretation":"SPEC-INREPLYTO-ARRAY"})
        for field,code in (("SizeInBytes","MESSAGE_SIZE_XMP"),("NumberAttachments","MESSAGE_ATTACHMENT_COUNT_XMP")):
            if field in record:
                valid=_services.is_xmp_text(record[field]) and _services.is_nonneg_int_str(record[field])
                report.add("PASS" if valid else "FAIL",code,
                           "Supplied email count has the required nonnegative-integer type" if valid else
                           "Supplied email count must be a nonnegative integer simple value",{
                               "email":index,"field":field,"value":record[field],"actual_type":_services.xmp_semantic_type(record[field]),
                               "source_comparison":"Not established by this schema check"})
        sent=_services.first_present(record,"Sent")
        guid=_services.guid_value(record.get("Mail_GUID",""))
        if not sent:
            report.fail("MESSAGE_SENT_XMP","Every email requires XMP Sent, including isolated emails",{"email":index,"value":sent})
        elif not _services.looks_like_xmp_datetime(sent):
            report.fail("MESSAGE_SENT_FORMAT","XMP Sent must be a valid xsd:dateTime",{"email":index,"value":sent,"actual_type":_services.xmp_semantic_type(sent)})
        else:
            report.ok("MESSAGE_SENT_XMP","Email XMP Sent is present",{"email":index,"value":sent})
            report.ok("MESSAGE_SENT_FORMAT","XMP Sent is a valid xsd:dateTime",{"email":index,"value":sent})
        if not guid:
            report.fail("MAIL_GUID_XMP","Every email requires Mail_GUID as an XMP normal simple string",
                        {"email":index,"value":record.get("Mail_GUID"),"expected_type":"normal string",
                         "actual_type":("URI" if isinstance(record.get("Mail_GUID"),_services.XmpUri) else
                                        "array" if isinstance(record.get("Mail_GUID"),list) else
                                        type(record.get("Mail_GUID")).__name__)})
        elif guid in identifiers:
            report.fail("MAIL_GUID_UNIQUE","Mail_GUID must uniquely identify an email",{"email":index,"value":guid})
        else:
            report.ok("MAIL_GUID_XMP","Email XMP Mail_GUID is present",{"email":index,"value":guid})
            identifiers.add(guid)
            report.ok("MAIL_GUID_UNIQUE","Email Mail_GUID is unique within this document",{"email":index,"value":guid})
