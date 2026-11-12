"""EA-PDF rule family. Documents/reports are borrowed; the root facade supplies
explicit live dependencies, preserving public patching and ownership contracts.
"""
from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class Dependencies:
    Decimal: Any
    ET: Any
    MAX_TREE_ITEMS: Any
    NS_PDFMAILMETA: Any
    NS_RDF: Any
    XmpArray: Any
    attachment_email_owners: Callable
    check_ambiguous_source_header_values: Callable
    check_native_outlook_core: Callable
    check_single_message_metadata: Callable
    check_source_field_values: Callable
    check_xmp_record_array: Callable
    clean_header_text: Callable
    compare_agent_property: Callable
    compare_native_outlook_raw: Callable
    contains_surrogate: Callable
    decoded_header_text: Callable
    decoded_source_header: Callable
    deepcopy: Any
    diagnostic_value: Callable
    evidence_unavailable: Callable
    find_xmp_values: Callable
    first_message_xmp_value: Callable
    first_present: Callable
    flatten_attachments: Callable
    flatten_email_records: Callable
    guid_value: Callable
    inspect_outlook_source: Callable
    is_nonneg_int_str: Callable
    is_xmp_text: Callable
    looks_like_xmp_datetime: Callable
    mailbox_comparison_key: Callable
    md5hex: Callable
    message_xmp_property_present: Callable
    nonnegative_integer_equals: Callable
    norm_text: Callable
    normalize_foaf_agents: Callable
    parse_nonneg_int_value: Callable
    parse_xmp_datetime: Callable
    qn: Callable
    raw_address_mailbox_keys: Callable
    re: Any
    select_source_email: Callable
    sha256hex: Callable
    source_assertion_key: Callable
    source_date_present: Callable
    source_file_name: Callable
    source_header_candidate_failures: Callable
    source_metadata_result: Callable
    summarize_source_email: Callable
    summarize_source_emails: Callable
    valid_smtp_mailbox: Callable
    validate_attachment_records: Callable
    xmp_array_members: Callable


def check_source_field_values(_services, record,source,report):
    if source.get("native_outlook") is not None:
        return
    fields=(("Raw-To","raw_to"),("Raw-From","raw_from"),("Raw-Cc","raw_cc"),
            ("Raw-Bcc","raw_bcc"),("Raw-Sent","sent_raw"),("Subject","subject"),
            ("In-Reply-To","in_reply_to"),("messageid","message_id"))
    for field,source_field in fields:
        actual=_services.first_present(record,field,"Message-ID") if field=="messageid" else record.get(field,"")
        supplied=field in record or field=="messageid" and "Message-ID" in record
        expected=source.get(source_field,"")
        required=bool(expected) and field in ("Subject","In-Reply-To","messageid")
        if required:
            _services.source_metadata_result(report,"PASS" if actual else "FAIL",field,
                "Nonempty original Core Field requires corresponding email metadata",{"source":expected,"xmp":actual},True)
        if not supplied:
            continue
        shape=(isinstance(actual,_services.XmpArray) and actual.kind=="Bag" and all(_services.is_xmp_text(value) for value in actual)
               if field=="In-Reply-To" else _services.is_xmp_text(actual))
        if not shape:
            _services.source_metadata_result(report,"N/A",field,"Source value comparison requires the independently checked metadata type")
            continue
        if _services.contains_surrogate(expected):
            _services.source_metadata_result(report,"WARN",field,"Source-header value comparison is unavailable for undecodable octets",
                                   {"assessment":"unavailable","source_escaped":_services.diagnostic_value(expected)})
            report.warn("VALIDATION_INCOMPLETE","Required source-header comparison could not be completed",{"field":field,"assessment":"unavailable"})
            continue
        detail={"xmp":actual,"source":expected}
        if field=="In-Reply-To":
            actual_ids=sorted(token for value in actual for token in _services.re.findall(r"<[^>]*>|[^\s]+",_services.decoded_header_text(value)))
            expected_ids=sorted(_services.re.findall(r"<[^>]*>|[^\s]+",_services.decoded_header_text(expected)))
            equal=actual_ids==expected_ids
        elif field in ("Raw-To","Raw-From","Raw-Cc","Raw-Bcc"):
            original=source.get("header_values",{}).get(field[4:].lower(),[])
            original_keys=_services.raw_address_mailbox_keys(", ".join(original)) if original and all(isinstance(value,str) for value in original) else None
            actual_keys=_services.raw_address_mailbox_keys(actual)
            equal=_services.decoded_header_text(actual)==_services.decoded_header_text(expected)
            if original_keys is not None and actual_keys is not None and original_keys!=actual_keys:
                equal=False
                detail.update(source=original,xmp_mailboxes=actual_keys,source_mailboxes=original_keys)
        elif field=="Subject":
            if _services.norm_text(actual)==_services.norm_text(expected):
                equal=True
            else:
                try:
                    original=_services.clean_header_text(_services.decoded_source_header(expected))
                except ValueError:
                    _services.source_metadata_result(report,"WARN",field,"Source Subject normalization is unavailable for this encoding",dict(detail,assessment="unavailable"))
                    report.warn("VALIDATION_INCOMPLETE","Required source Subject comparison could not be completed",{"field":field,"assessment":"unavailable"})
                    continue
                try:
                    supplied_text=_services.clean_header_text(_services.decoded_source_header(actual))
                except ValueError:
                    supplied_text=_services.norm_text(actual)
                equal=supplied_text==original
        else:
            equal=_services.decoded_header_text(actual)==_services.decoded_header_text(expected)
        _services.source_metadata_result(report,"PASS" if equal else "FAIL",field,"Email metadata compared with its matched original Core Field",detail)
    sent_value=record.get("Sent","")
    sent=_services.parse_xmp_datetime(sent_value);expected_sent=_services.parse_xmp_datetime(source.get("sent_norm"))
    if _services.looks_like_xmp_datetime(sent_value) and (source.get("sent_norm") or source.get("sent_normalization_unavailable")):
        detail={"xmp":sent_value,"source":source.get("sent_raw")}
        if sent is None or expected_sent is None or sent.tzinfo is None or expected_sent.tzinfo is None:
            _services.source_metadata_result(report,"WARN","Sent","Source date comparison is unavailable for this valid dateTime",dict(detail,assessment="unavailable"))
            report.warn("VALIDATION_INCOMPLETE","Required source date comparison could not be completed",{"field":"Sent","assessment":"unavailable"})
        else:
            def fraction(value):
                found=_services.re.search(r"\.([0-9]+)(?:Z|[+-][0-9]{2}:[0-9]{2})?$",str(value).strip())
                return _services.Decimal("0."+found.group(1)) if found else _services.Decimal(0)
            equal=(sent.replace(microsecond=0)==expected_sent.replace(microsecond=0)
                   and fraction(sent_value)==fraction(source.get("sent_norm","")))
            _services.source_metadata_result(report,"PASS" if equal else "FAIL","Sent","Email sent instant compared with the matched original",detail)
    for field,expected in (("SizeInBytes",source.get("size_in_bytes",0)),("NumberAttachments",source.get("mime_attachment_count",0))):
        if field=="NumberAttachments" and not source.get("attachment_inventory_available",True):
            _services.source_metadata_result(report,"WARN",field,"Original attachment count is unavailable",{"assessment":"unavailable","reason":source.get("attachment_error")})
            continue
        if field=="NumberAttachments" and expected:
            _services.source_metadata_result(report,"PASS" if record.get(field) else "FAIL",field,
                "An original with attachments requires NumberAttachments",{"source":expected,"xmp":record.get(field)},True)
        if field not in record:
            continue
        if not _services.is_nonneg_int_str(record[field]):
            _services.source_metadata_result(report,"N/A",field,"Source count comparison requires the independently checked nonnegative integer type")
            continue
        _services.source_metadata_result(report,"PASS" if _services.nonnegative_integer_equals(record[field],expected) else "FAIL",field,
            "Email metadata count or size compared with the matched original",{"xmp":record[field],"source":expected})

def check_ambiguous_source_header_values(_services, root_xml,record,afiles,eapdf_conf,report):
    """Reject field contradictions shared by every possible original.

    Do not use the fields under test to select an owner or exclude candidates.
    An unsupported original or recovered header inventory blocks this proof.
    """
    files=[source for source in afiles if source.get("afrelationship")=="Source"]
    sources=_services.summarize_source_emails(files)
    if not sources or len(sources)!=len(files) or any(source.get("header_parse_defects") for source in sources):
        return
    incomplete=[source for source in sources if source.get("uninspected_email_candidates")]
    if incomplete:
        report.warn("MESSAGE_SOURCE_COVERAGE","Known nested mail candidates or mailbox envelopes have not been inspected",{
            "assessment":"unavailable","source_ownership":"unassigned",
            "candidate_universe":"incomplete","sources":[{
                "source":source.get("source_filename"),"sha256":source.get("source_sha256"),
                "uninspected":source["uninspected_email_candidates"]} for source in incomplete]})
        report.warn("VALIDATION_INCOMPLETE","Outer email headers cannot establish a universal original-source contradiction")
        return
    work=getattr(report,"_source_header_candidate_checks",0)+len(files)
    if work>_services.MAX_TREE_ITEMS:
        report.warn("VALIDATION_INCOMPLETE","Ambiguous source-header comparisons exceeded the configured item limit",{
            "candidate_count":len(files),"limit":_services.MAX_TREE_ITEMS})
        return
    report._source_header_candidate_checks=work
    candidate_failures=[]
    candidate_reports=[]
    for source_file in files:
        source=_services.summarize_source_email([source_file])
        failures=_services.source_header_candidate_failures(root_xml,record,source_file,eapdf_conf,report.path)
        candidate_failures.append({_services.source_assertion_key(item) for item in failures})
        candidate_reports.append((source,failures))
    common=set.intersection(*candidate_failures)
    if not common and all(candidate_failures):
        report.fail("MESSAGE_SOURCE_COVERAGE","No inspectable original satisfies the supplied email Core Fields",{
            "candidate_count":len(sources),"source_ownership":"unassigned",
            "candidates":[{"source":source.get("source_filename"),"sha256":source.get("source_sha256"),"findings":candidate}
                          for source,candidate in candidate_reports]})
    for code,field in sorted(common):
        if any(_services.source_assertion_key(item)==(code,field) and item["level"]=="FAIL" for item in report.items):
            continue
        report.fail(code,"Email metadata is contradicted by every inspectable original source",{
            "field":field,"candidate_count":len(sources),"source_ownership":"unassigned",
            "candidates":[{"source":source.get("source_filename"),"sha256":source.get("source_sha256"),
                           "findings":[item for item in candidate if _services.source_assertion_key(item)==(code,field)]}
                          for source,candidate in candidate_reports]})

def check_message_metadata(_services, root_xml,afiles,eapdf_conf,report):
    if root_xml is None:
        return
    report._email_source_summaries={}
    report._eapdf_conf=eapdf_conf
    report._originals_supplied=any(source.get("afrelationship")=="Source" for source in afiles)
    _services.check_xmp_record_array(root_xml,"attachments",report,"ATTACHMENT_XMP_CONTAINER",required=True)
    if eapdf_conf in ("c","ci"):
        # Declared attachment records retain Table 19's required fields in containers.
        # Native payload/source comparisons are performed in the owning email children.
        _services.validate_attachment_records(_services.flatten_attachments(root_xml),[],report)
        return
    if eapdf_conf not in ("s","si","m","mi"):
        return
    email_nodes=_services.xmp_array_members(root_xml,"email")
    records=_services.flatten_email_records(root_xml)
    if not email_nodes or len(email_nodes)!=len(records):
        _services.check_single_message_metadata(root_xml,afiles,eapdf_conf,report)
        return
    sources=_services.summarize_source_emails(afiles)
    remaining=list(sources)
    attachment_nodes=_services.xmp_array_members(root_xml,"attachments")
    attachment_records=_services.flatten_attachments(root_xml)
    attachment_owners=[_services.attachment_email_owners(attachment,records) for attachment in attachment_records]
    assigned_attachments=set()
    for index,(record,node) in enumerate(zip(records,email_nodes),start=1):
        scoped=_services.ET.Element(_services.qn(_services.NS_RDF,"RDF"))
        description=_services.ET.SubElement(scoped,_services.qn(_services.NS_RDF,"Description"))
        container=_services.ET.SubElement(_services.ET.SubElement(description,_services.qn(_services.NS_PDFMAILMETA,"email")),_services.qn(_services.NS_RDF,"Bag"))
        container.append(_services.deepcopy(node))
        attachment_bag=_services.ET.SubElement(_services.ET.SubElement(description,_services.qn(_services.NS_PDFMAILMETA,"attachments")),_services.qn(_services.NS_RDF,"Bag"))
        guid=_services.guid_value(record.get("Mail_GUID",""))
        for attachment_index,(attachment,attachment_node) in enumerate(zip(attachment_records,attachment_nodes)):
            owners=attachment_owners[attachment_index]
            if len(owners)==1 and owners[0] is record:
                attachment_bag.append(_services.deepcopy(attachment_node))
                assigned_attachments.add(attachment_index)
        selected=_services.select_source_email(record,remaining)
        if selected is not None and (sum(source.get("afrelationship")=="Source" for source in afiles)>1
                                     or selected.get("uninspected_email_candidates")):
            # Scored header values are a tentative match, not independent
            # ownership evidence for a contradictory field under test.
            chosen=next((source for source in afiles if source.get("_email_summary") is selected),None)
            work=getattr(report,"_source_header_candidate_checks",0)+1
            if chosen is None or work>_services.MAX_TREE_ITEMS:
                selected=None
            else:
                report._source_header_candidate_checks=work
                if _services.source_header_candidate_failures(scoped,record,chosen,eapdf_conf,report.path):
                    selected=None
        if selected is not None:
            remaining.remove(selected)
        report._email_source_summaries[index]=selected
        scoped_sources=[]
        if selected is not None:
            for source_file in afiles:
                if source_file.get("objgen")==selected["source_objgen"] and _services.sha256hex(source_file.get("bytes",b""))==selected["source_sha256"]:
                    scoped_source=dict(source_file)
                    scoped_source["_email_summary"]=dict(selected,mail_guid=guid)
                    scoped_sources=[scoped_source]
                    break
            report.ok("MESSAGE_SOURCE_COVERAGE","Email metadata is matched to one inspectable source email",{
                "email":index,"Mail_GUID":guid,"source":selected["source_filename"],
                "source_sha256":selected["source_sha256"],"source_objgen":selected["source_objgen"],
                "source_md5":_services.md5hex(scoped_sources[0]["bytes"]) if scoped_sources else None,
                "source_size":len(scoped_sources[0]["bytes"]) if scoped_sources else None})
        elif sources or any(source.get("afrelationship")=="Source" for source in afiles):
            report.warn("MESSAGE_SOURCE_COVERAGE","Email metadata cannot be matched unambiguously to a source email",{
                "email":index,"Mail_GUID":guid,"source_ownership":"unassigned"})
            report.warn("VALIDATION_INCOMPLETE","Source-header comparisons could not be completed for an email",{"email":index})
        first_item=len(report.items)
        _services.check_single_message_metadata(scoped,scoped_sources,eapdf_conf,report)
        if selected is None and sources:
            _services.check_ambiguous_source_header_values(scoped,record,afiles,eapdf_conf,report)
        if selected is None and len(records)==1 and eapdf_conf in ("s","si"):
            originals=[source for source in afiles if source.get("afrelationship")=="Source"]
            if len(originals)==1:
                _services.check_native_outlook_core(record,originals[0],report,
                    [attachment for attachment,owners in zip(attachment_records,attachment_owners)
                     if len(owners)==1 and owners[0] is record])
        for item in report.items[first_item:]:
            item["details"]=dict(item.get("details") or {},email=index,Mail_GUID=guid)
    unassigned=[attachment for index,attachment in enumerate(attachment_records) if index not in assigned_attachments]
    if unassigned:
        _services.validate_attachment_records(unassigned,[],report)
        report.warn("VALIDATION_INCOMPLETE","Attachment records could not be assigned to one email without relying on duplicate or missing Message-IDs",{"count":len(unassigned)})

def check_native_outlook_core(_services, record,source,report,attachments=None,native=None):
    """Check definite stored MAPI facts without claiming a header inventory."""
    name=_services.norm_text(source.get("ufilename") or source.get("filename") or source.get("tree_name","")).lower()
    if _services.norm_text(source.get("subtype","")).lower()!="application/vnd.ms-outlook" and not name.endswith(".msg"):
        return
    if native is None:
        native=_services.inspect_outlook_source(source.get("bytes") or b"",native_core_only=True)
    if native is None:
        return
    detail={"source":_services.source_file_name(source),"evidence":"stored MSG properties; transport headers remain unavailable"}
    for field,code in (("Subject","MESSAGE_SUBJECT_XMP"),("messageid","MESSAGE_ID_XMP")):
        if field not in native:
            continue
        actual=_services.first_present(record,"messageid","Message-ID") if field=="messageid" else record.get(field,"")
        if _services.is_xmp_text(actual) and _services.norm_text(actual)==_services.norm_text(native[field]):
            _services.source_metadata_result(report,"PASS",field,"Email metadata agrees with the stored Outlook property",detail)
        else:
            _services.source_metadata_result(report,"FAIL",field,"Email metadata differs from the stored Outlook property",dict(detail,xmp=actual,source_value=native[field]),required=bool(native[field]) and not bool(actual))
    for field,code in (("SizeInBytes","MESSAGE_SIZE_XMP"),("NumberAttachments","MESSAGE_ATTACHMENT_COUNT_XMP")):
        if native.get(field) is not None and (field in record or field=="NumberAttachments" and native[field]>0):
            equal=_services.parse_nonneg_int_value(record.get(field))==native[field]
            _services.source_metadata_result(report,"PASS" if equal else "FAIL",field,"Email metadata compared with stored MSG evidence",dict(detail,source_value=native[field],xmp=record.get(field)),required=field=="NumberAttachments" and native[field]>0 and field not in record)
    from collections import Counter
    for field,expected in native["addresses"].items():
        value=record.get(field,[])
        actual=[agent for member in value for agent in _services.normalize_foaf_agents(member)] if isinstance(value,list) else []
        raw=native.get("raw_addresses",{}).get(field)
        present=(_services.norm_text(record.get("Raw-"+field,""))==_services.norm_text(raw["display"]) if not expected and raw and raw["display"] else not expected or bool(actual))
        _services.source_metadata_result(report,"PASS" if present else "FAIL",field,"Stored Outlook mailboxes require corresponding metadata",detail,True)
        valid=all(not agent.get("mbox_uri_error") and _services.valid_smtp_mailbox(agent.get("mbox","")) for agent in actual)
        equal=valid and Counter(_services.mailbox_comparison_key(agent["mbox"]) for agent in actual)==Counter(_services.mailbox_comparison_key(address) for address in expected)
        _services.source_metadata_result(report,"PASS" if equal else "FAIL",field,
            "Mailbox occurrences compared with the stored Outlook recipient table; original header order remains unavailable",
            dict(detail,expected=expected,xmp=[agent.get("mbox","") for agent in actual]))
    if attachments is not None:
        _services.validate_attachment_records(attachments,{"attachments":native["attachments"] or [],"attachment_inventory_available":native["attachments"] is not None,"attachment_error":native["attachment_error"],
            "message_id":native.get("messageid",""),"mail_guid":_services.guid_value(record.get("Mail_GUID","")),"native_files":source.get("_native_files",source.get("_email_summary",{}).get("native_files",[]))},report)
    for field in ("To","From","Cc","Bcc"):
        if field not in native["addresses"]:
            for required in (False,True):
                _services.source_metadata_result(report,"WARN",field,"Stored Outlook addresses cannot be resolved to SMTP mailboxes",dict(detail,assessment="unavailable"),required)
        if "Raw-"+field in record:
            _services.compare_native_outlook_raw(record,native,field,report)
    if "Raw-Sent" in record:
        _services.source_metadata_result(report,"WARN","Raw-Sent","The MSG has no transport Date header for comparing Raw-Sent",dict(detail,assessment="unavailable"))
    expected_reply=sorted(_services.re.findall(r"<[^>]*>|[^\s]+",native.get("reply","")))
    actual=record.get("In-Reply-To",[])
    actual_reply=sorted(token for value in (actual if isinstance(actual,list) else [actual]) for token in _services.re.findall(r"<[^>]*>|[^\s]+",str(value)))
    _services.source_metadata_result(report,"PASS" if actual_reply==expected_reply else "FAIL","In-Reply-To","Reply identifiers compared with the stored Outlook In-Reply-To property",detail,required=bool(expected_reply) and not bool(actual_reply))
    report.na("MESSAGE_RAW_SENT_OMIT_IF_VALID","No transport Date header is stored in this MSG",detail)
    if "Sent" in native:
        actual=record.get("Sent","")
        if not actual:
            _services.source_metadata_result(report,"FAIL","Sent","Stored Outlook submission time requires Sent metadata",detail,True)
        elif _services.is_xmp_text(actual) and _services.looks_like_xmp_datetime(actual):
            # Reuse the existing exact instant/fraction comparison; this is not a Date header.
            _services.check_source_field_values({"Sent":actual},{"sent_norm":native["Sent"],"sent_raw":native["Sent"],
                "size_in_bytes":native["SizeInBytes"],"mime_attachment_count":0},report)

def check_single_message_metadata(_services, root_xml,afiles,eapdf_conf,report):
    if root_xml is None:
        return
    msgid=_services.first_message_xmp_value(root_xml,"messageid","Message-ID")
    mail_guid=_services.guid_value(_services.first_message_xmp_value(root_xml,"Mail_GUID"))
    if msgid:
        report.ok("MESSAGE_ID_XMP","pdfmailmeta:messageid present",{"value":msgid})
    else:
        report.ok("MESSAGE_ID_XMP","pdfmailmeta:messageid absent, which is allowed",{"value":msgid})
    if mail_guid:
        report.ok("MAIL_GUID_XMP","pdfmailmeta:Mail_GUID present",{"value":mail_guid})
    elif eapdf_conf in ("s","si","m","mi"):
        report.fail("MAIL_GUID_XMP","pdfmailmeta:Mail_GUID required for mail profiles",{"value":mail_guid})
    else:
        report.warn("MAIL_GUID_XMP","pdfmailmeta:Mail_GUID not found",{"value":mail_guid})
    if eapdf_conf in ("s","si","m","mi"):
        summary=_services.summarize_source_email(afiles)
        if summary is None:
            supplied_original=any(source.get("afrelationship")=="Source" for source in afiles) or getattr(report,"_originals_supplied",False)
            omitted=eapdf_conf.endswith("i") and not supplied_original
            for code in ("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED","MESSAGE_METADATA_MATCH_SOURCE"):
                if omitted:
                    report.na(code,"Original comparison was not performed: isolated output omits original sources",{"field":"all","scope":"original-source comparison"})
                else:
                    _services.evidence_unavailable(report,code,"An independently associated readable original is unavailable",{"field":"all"})
            if any(source.get("afrelationship")=="Source" for source in afiles):
                report.warn("MESSAGE_SOURCE_COVERAGE","Attached original sources cannot be inspected for this email",{
                    "assessment":"unavailable","reason":"No supported source email summary could be obtained."})
                report.warn("VALIDATION_INCOMPLETE","Required source-header comparisons could not be completed",{
                    "assessment":"unavailable"})
            for prop,presence_code,foaf_code in (("To","MESSAGE_TO_XMP","MESSAGE_TO_FOAF"),
                                               ("From","MESSAGE_FROM_XMP","MESSAGE_FROM_FOAF"),
                                               ("Cc","MESSAGE_CC_XMP","MESSAGE_CC_FOAF"),
                                               ("Bcc","MESSAGE_BCC_XMP","MESSAGE_BCC_FOAF")):
                _services.compare_agent_property(root_xml,report,prop,None,presence_code,foaf_code)
            attachments=_services.flatten_attachments(root_xml)
            attachment_summary = _services.summarize_source_emails(afiles) if eapdf_conf in ("m", "mi") else []
            _services.validate_attachment_records(attachments,attachment_summary,report)
            return
        if summary.get("native_outlook") is not None:
            records=_services.flatten_email_records(root_xml)
            if len(records)==1:
                _services.check_native_outlook_core(records[0],dict(summary["native_source"],_native_files=summary.get("native_files",[])),report,_services.flatten_attachments(root_xml),summary["native_outlook"])
            return
        _services.compare_agent_property(root_xml,report,"To",summary["to_agents"],"MESSAGE_TO_XMP","MESSAGE_TO_FOAF",summary["raw_to"])
        _services.compare_agent_property(root_xml,report,"From",summary["from_agents"],"MESSAGE_FROM_XMP","MESSAGE_FROM_FOAF",summary["raw_from"])
        _services.compare_agent_property(root_xml,report,"Cc",summary["cc_agents"],"MESSAGE_CC_XMP","MESSAGE_CC_FOAF",summary["raw_cc"])
        _services.compare_agent_property(root_xml,report,"Bcc",summary["bcc_agents"],"MESSAGE_BCC_XMP","MESSAGE_BCC_FOAF",summary["raw_bcc"])
        sent=_services.first_message_xmp_value(root_xml,"Sent")
        raw_sent=_services.first_message_xmp_value(root_xml,"Raw-Sent")
        raw_sent_present=_services.message_xmp_property_present(root_xml,"Raw-Sent")
        raw_to=_services.first_message_xmp_value(root_xml,"Raw-To")
        raw_from=_services.first_message_xmp_value(root_xml,"Raw-From")
        raw_cc=_services.first_message_xmp_value(root_xml,"Raw-Cc")
        raw_bcc=_services.first_message_xmp_value(root_xml,"Raw-Bcc")
        subject=_services.first_message_xmp_value(root_xml,"Subject")
        in_reply_to=_services.find_xmp_values(root_xml,_services.NS_PDFMAILMETA,"In-Reply-To")
        if not in_reply_to:
            emails=_services.flatten_email_records(root_xml)
            for email in emails:
                val=_services.first_present(email,"In-Reply-To")
                if val:
                    in_reply_to=[val]
                    break
        size_in_bytes=_services.first_message_xmp_value(root_xml,"SizeInBytes")
        number_attachments=_services.first_message_xmp_value(root_xml,"NumberAttachments","Mail_NumberAttachments")
        if _services.source_date_present(summary):
            if sent:
                report.ok("MESSAGE_SENT_XMP","XMP Sent present",{"value":sent})
                if _services.looks_like_xmp_datetime(sent):
                    report.ok("MESSAGE_SENT_FORMAT","XMP Sent is a valid xsd:dateTime",{"value":sent})
                else:
                    report.fail("MESSAGE_SENT_FORMAT","XMP Sent must be a valid xsd:dateTime",{"value":sent})
            else:
                report.fail("MESSAGE_SENT_XMP","XMP Sent missing even though source email has Date header",{"source_date":summary["sent_raw"]})
            if summary.get("sent_normalization_unavailable"):
                report.warn("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","Raw-Sent applicability cannot be established within the source date parser's range",{
                    "source_date":summary["sent_raw"],"assessment":"unavailable"})
                report.warn("VALIDATION_INCOMPLETE","Required source date normalization assessment could not be completed",{"assessment":"unavailable"})
            elif not summary["sent_norm"]:
                if raw_sent_present:
                    report.ok("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","XMP Raw-Sent present for non-normalizable source date",{"value":raw_sent})
                else:
                    report.fail("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","XMP Raw-Sent required when source Date header is not a valid XMP dateTime",{"source_date":summary["sent_raw"]})
            elif raw_sent_present:
                report.warn("MESSAGE_RAW_SENT_OMIT_IF_VALID","XMP Raw-Sent SHOULD be omitted when the source date is valid",{"value":raw_sent,"source_date":summary["sent_raw"]})
            else:
                report.ok("MESSAGE_RAW_SENT_OMIT_IF_VALID","XMP Raw-Sent is omitted because source Date header is already a valid XMP dateTime",{"source_date":summary["sent_raw"]})
        if summary["raw_to"]:
            if _services.message_xmp_property_present(root_xml,"Raw-To"):
                report.ok("MESSAGE_RAW_TO_XMP","XMP Raw-To present",{"value":raw_to})
            else:
                report.na("MESSAGE_RAW_TO_XMP","Optional Raw-To is not supplied",{"reason":"Raw-To is an optional property; no value is supplied for this email."})
        if summary["raw_from"]:
            if _services.message_xmp_property_present(root_xml,"Raw-From"):
                report.ok("MESSAGE_RAW_FROM_XMP","XMP Raw-From present",{"value":raw_from})
            else:
                report.na("MESSAGE_RAW_FROM_XMP","Optional Raw-From is not supplied",{"reason":"Raw-From is an optional property; no value is supplied for this email."})
        if summary["raw_cc"]:
            if _services.message_xmp_property_present(root_xml,"Raw-Cc"):
                report.ok("MESSAGE_RAW_CC_XMP","XMP Raw-Cc present",{"value":raw_cc})
            else:
                report.na("MESSAGE_RAW_CC_XMP","Optional Raw-Cc is not supplied",{"reason":"Raw-Cc is an optional property; no value is supplied for this email."})
        if summary["raw_bcc"]:
            if _services.message_xmp_property_present(root_xml,"Raw-Bcc"):
                report.ok("MESSAGE_RAW_BCC_XMP","XMP Raw-Bcc present",{"value":raw_bcc})
            else:
                report.na("MESSAGE_RAW_BCC_XMP","Optional Raw-Bcc is not supplied",{"reason":"Raw-Bcc is an optional property; no value is supplied for this email."})
        records=_services.flatten_email_records(root_xml)
        record=records[0] if len(records)==1 else {
            field:_services.first_message_xmp_value(root_xml,field) for field in
            ("Subject","messageid","Message-ID","Sent","Raw-Sent","Raw-To","Raw-From","Raw-Cc","Raw-Bcc","SizeInBytes","NumberAttachments","In-Reply-To")
            if _services.message_xmp_property_present(root_xml,field)}
        _services.check_source_field_values(record,summary,report)
        attachments=_services.flatten_attachments(root_xml)
        attachment_summary = _services.summarize_source_emails(afiles) if eapdf_conf in ("m", "mi") else summary
        _services.validate_attachment_records(attachments,attachment_summary,report)
