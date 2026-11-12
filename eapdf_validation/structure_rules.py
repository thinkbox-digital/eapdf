"""EA-PDF rule family. Documents/reports are borrowed; the root facade supplies
explicit live dependencies, preserving public patching and ownership contracts.
"""
from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class Dependencies:
    Decimal: Any
    InvalidOperation: Any
    MAIL_STRUCTURE_TYPES: Any
    MAX_TREE_ITEMS: Any
    Report: Any
    StructureRoles: Any
    analyze_page_content: Callable
    check_dpart_object_structure: Callable
    check_page_dpart_links: Callable
    check_structure_correspondence: Callable
    check_user_properties: Callable
    datetime: Any
    decoded_source_header: Callable
    defaultdict: Any
    dict_get: Callable
    dpart_ancestor_guid: Callable
    dpart_level_name_assessment: Callable
    dpart_message_identity: Callable
    emit_if_unseen: Callable
    evidence_unavailable: Callable
    flatten_email_records: Callable
    get_dpart_nodes: Callable
    get_outline_items: Callable
    get_outline_page_objgens: Callable
    guid_value: Callable
    identity_fields: Callable
    independent_content_ownership: Callable
    inspect_dpm_value_graph: Callable
    is_pdf_array: Callable
    is_pdf_ascii_string: Callable
    looks_like_pdf_date: Callable
    norm_text: Callable
    normalize_role_map: Callable
    objgen: Callable
    observed_header_source_inventory: Callable
    observed_pdf_value: Callable
    page_has_visibleish_content: Callable
    page_objgen_map: Callable
    page_range_from_dpart: Callable
    parse_userproperties: Callable
    parse_xmp_datetime: Callable
    pdf_object_identity: Callable
    pikepdf: Any
    re: Any
    safe_name: Callable
    source_email_for_record: Callable
    struct_children: Callable
    structure_field_text_sequences: Callable
    summarize_source_emails: Callable
    timedelta: Any
    timezone: Any
    userprop_name: Callable
    userprop_value_kind: Callable
    visit_tree_node: Callable
    walk_struct_elems: Callable
    xmp_value_set: Callable


def check_custom_tag_operands(_services, pdf,report):
    """EA-PDF 9.7: compare each sequence with its actual structure owner."""
    code="CUSTOM_TAG_OPERAND_MATCH"
    owners=_services.defaultdict(list)
    stream_pages=_services.defaultdict(set)
    sequences={}
    unavailable=[]
    failures=[]
    compared=[]
    observed_keys=set()
    for number,page in enumerate(pdf.pages,start=1):
        page_id=_services.pdf_object_identity(page.obj)
        streams=_services.dict_get(page.obj,"/Contents",[])
        for stream in streams if _services.is_pdf_array(streams) else [streams]:
            if isinstance(stream,_services.pikepdf.Stream):
                stream_pages[_services.pdf_object_identity(stream)].add(page_id)
        try:
            for sequence in _services.analyze_page_content(page)["sequences"]:
                location=(_services.pdf_object_identity(sequence["scope"]),sequence["instruction_index"])
                sequences.setdefault(location,dict(sequence,page_number=number))
        except Exception as error:
            unavailable.append({"page":number,"reason":str(error)})

    seen=set()
    def walk(element,inherited_page=None,depth=0):
        _services.visit_tree_node(element,seen,len(seen),depth,"custom operand ownership")
        page=_services.dict_get(element,"/Pg",inherited_page)
        tag=_services.safe_name(_services.dict_get(element,"/S",None))
        children=_services.dict_get(element,"/K",[])
        for child in children if _services.is_pdf_array(children) else [children]:
            if _services.dict_get(child,"/S",None) is not None:
                walk(child,page,depth+1)
                continue
            if isinstance(child,int) and not isinstance(child,bool):
                mcid=child
                target=page
                content_page=page
            elif _services.safe_name(_services.dict_get(child,"/Type",None))=="MCR":
                mcid=_services.dict_get(child,"/MCID",None)
                content_page=_services.dict_get(child,"/Pg",page)
                target=_services.dict_get(child,"/Stm",content_page)
            else:
                continue
            if target is None or not isinstance(mcid,int) or isinstance(mcid,bool) or mcid<0:
                if tag.startswith("Mail_"):
                    unavailable.append({"owner":_services.objgen(element),"reason":"Custom content reference has no valid scope/MCID"})
                continue
            scope=_services.pdf_object_identity(target)
            aliases=stream_pages.get(scope,set())
            page_id=_services.pdf_object_identity(content_page)
            if page_id in aliases:
                scope=page_id
            elif len(aliases)==1:
                scope=next(iter(aliases))
            elif aliases:
                unavailable.append({"owner":_services.objgen(element),"reason":"Content stream reference has ambiguous page scope"})
                continue
            owners[(scope,mcid)].append({"tag":tag,"object":_services.objgen(element)})

    structure=_services.dict_get(pdf.Root,"/StructTreeRoot",None)
    roots=_services.dict_get(structure,"/K",[])
    try:
        for root in roots if _services.is_pdf_array(roots) or isinstance(roots,list) else [roots]:
            walk(root)
    except Exception as error:
        unavailable.append({"reason":str(error)})
    for sequence in sequences.values():
        mcid=sequence["mcid"]
        valid_mcid=isinstance(mcid,int) and not isinstance(mcid,bool) and mcid>=0
        key=(_services.pdf_object_identity(sequence["scope"]),mcid) if valid_mcid else None
        if key is not None:
            observed_keys.add(key)
        candidates=owners.get(key,[]) if valid_mcid else []
        if not sequence["tag"].startswith("Mail_") and not any(c["tag"].startswith("Mail_") for c in candidates):
            continue
        detail={"page":sequence["page_number"],"scope":_services.objgen(sequence["scope"]),
                "mcid":mcid,"actual":sequence["tag"],"owners":candidates}
        if len(candidates)!=1:
            unavailable.append(dict(detail,reason="No unique corresponding structure owner is available"))
        elif sequence["tag"]!=candidates[0]["tag"]:
            failures.append(dict(detail,expected=candidates[0]["tag"]))
        else:
            compared.append(detail)
    for key,candidates in owners.items():
        if key not in observed_keys and any(c["tag"].startswith("Mail_") for c in candidates):
            unavailable.append({"scope":key[0],"mcid":key[1],"owners":candidates,
                                "reason":"Custom structure content reference has no observed sequence"})
    details={"comparisons":compared,"unavailable":unavailable}
    if failures:
        report.fail(code,"Marked-content operands differ from their corresponding custom structure element names",
                    dict(details,actual=failures))
    elif unavailable:
        report.warn(code,"Custom marked-content operand ownership could not be fully verified",dict(details,assessment="unavailable"))
        report.warn("VALIDATION_INCOMPLETE","Custom marked-content operand comparisons require available content ownership",details)
    elif compared:
        report.ok(code,"Marked-content operands match their corresponding custom structure element names",details)
    else:
        report.na(code,"No custom marked-content sequences or custom owners of marked content require comparison",
                  {"reason":"No observed sequence uses a custom EA-PDF operand or has a custom EA-PDF structure owner."})

def check_outlines(_services, pdf,report):
    outlines=_services.dict_get(pdf.Root,"/Outlines",None)
    if outlines is None:
        report.fail("OUTLINES_PRESENT","Document catalog Outlines entry missing",{"Outlines":None})
        return [],[]
    report.ok("OUTLINES_PRESENT","Document catalog Outlines entry present",{"Outlines":_services.observed_pdf_value(outlines)})
    items=_services.get_outline_items(pdf)
    if items:
        report.ok("OUTLINE_ITEMS_PRESENT","Outline items present",{"count":len(items),"items":_services.observed_pdf_value(items)})
    else:
        report.fail("OUTLINE_ITEMS_PRESENT","No outline items found",{"count":len(items),"items":[]})
    pages=_services.get_outline_page_objgens(pdf)
    return items,pages

def check_page_dpart_links(_services, pdf,nodes,report):
    page_map=_services.page_objgen_map(pdf)
    leaf_ranges={}
    coverage_changes=[0]*(len(pdf.pages)+1)
    previous_range=None
    ordering_errors=[]
    ordering_count=0
    for item in nodes:
        if item["child_count"]:
            continue
        bounds=_services.page_range_from_dpart(item,page_map)
        if bounds is None:
            continue
        start_page,end_page,_,_=bounds
        if previous_range is not None and start_page<=previous_range[1]:
            ordering_count+=1
            if len(ordering_errors)<25:
                ordering_errors.append({"previous_range":previous_range,
                                        "range":[start_page,end_page],"objgen":item["objgen"]})
        previous_range=[start_page,end_page]
        leaf_ranges[item["objgen"]]=(start_page,end_page)
        coverage_changes[start_page-1]+=1
        coverage_changes[end_page]-=1
    missing=[]
    uncovered=[]
    overlapping=[]
    invalid=[]
    affected=[]
    page_issues={}
    coverage=0
    for page_number,page in enumerate(pdf.pages,start=1):
        coverage+=coverage_changes[page_number-1]
        link=_services.dict_get(page.obj,"/DPart",None)
        issues=[]
        if link is None:
            missing.append(page_number)
            issues.append("Page dictionary has no /DPart entry")
        else:
            linked_range=leaf_ranges.get(_services.objgen(link)) if isinstance(link,_services.pikepdf.Dictionary) and link.is_indirect else None
            if linked_range is None:
                invalid.append(page_number)
                issues.append("/DPart does not reference a reachable leaf content set with a valid page range")
            elif not linked_range[0]<=page_number<=linked_range[1]:
                invalid.append(page_number)
                issues.append("/DPart references a content set whose page range does not include this page")
        if coverage==0:
            uncovered.append(page_number)
            issues.append("No DPart content-set range covers this page")
        elif coverage>1:
            overlapping.append(page_number)
            issues.append("More than one DPart content-set range covers this page")
        if issues:
            affected.append(page_number)
            if len(page_issues)<25:
                page_issues[f"PDF page {page_number}"]={"page_object":_services.objgen(page.obj),"issues":issues}
    if not affected and not ordering_count:
        report.ok("PAGE_DPART_PRESENT","Every PDF page links to its unique DPart range and leaf ranges follow page-tree order",{"page_count":len(pdf.pages)})
        return
    reasons=[]
    for pages,reason in ((missing,"Missing /DPart entry"),
                         (uncovered,"Not covered by any DPart content-set range"),
                         (overlapping,"Covered by overlapping DPart content-set ranges"),
                         (invalid,"Invalid or mismatched /DPart link")):
        if not pages:
            continue
        label="PDF page" if len(pages)==1 else "PDF pages"
        numbers=", ".join(str(number) for number in pages[:25])
        if len(pages)>25:
            numbers+=f" (first 25 of {len(pages)})"
        reasons.append(f"{reason} on {label} {numbers}")
    if ordering_count:
        reasons.append("DPart leaf page ranges do not follow page-tree order")
    report.fail("PAGE_DPART_PRESENT","; ".join(reasons),
                {"pages":affected[:25],"count":len(affected),"page_numbering":"1-based PDF page numbers",
                 "missing_dpart_pages":missing[:25],"uncovered_pages":uncovered[:25],
                 "overlapping_pages":overlapping[:25],"ordering_violations":ordering_count,
                 "ordering_errors":ordering_errors,
                 "invalid_dpart_pages":invalid[:25],"page_issues":page_issues})

def check_dpart_and_content_sets(_services, pdf,root_xml,eapdf_conf,report,outline_pages):
    page_map=_services.page_objgen_map(pdf)
    dpart_root,dpart_rootnode,nodes=_services.get_dpart_nodes(pdf)
    if dpart_root is None:
        report.fail("DPARTROOT_PRESENT","Document catalog DPartRoot missing",{"DPartRoot":None})
        return []
    report.ok("DPARTROOT_PRESENT","Document catalog DPartRoot present",{"DPartRoot":_services.observed_pdf_value(dpart_root)})
    if dpart_rootnode is None:
        report.fail("DPM_ROOTNODE_PRESENT","DPartRootNode missing from DPartRoot",{"DPartRootNode":None})
        return []
    report.ok("DPM_ROOTNODE_PRESENT","DPartRootNode present",{"DPartRootNode":_services.observed_pdf_value(dpart_rootnode)})
    _services.check_dpart_object_structure(dpart_root,dpart_rootnode,nodes,report)

    _services.check_page_dpart_links(pdf,nodes,report)

    mail_guid_set=_services.xmp_value_set(root_xml,"Mail_GUID")
    messageid_set=_services.xmp_value_set(root_xml,"messageid","Message-ID")
    leaf_nodes=[]
    msg_nodes=[]
    for item in nodes:
        dpm=item["dpm"]
        is_leaf=item["start"] is not None or item["end"] is not None
        is_identity_intermediate=(not is_leaf) and ("Mail_GUID" in dpm or "Mail_MessageID" in dpm)
        if is_identity_intermediate:
            msg_nodes.append(item)
        if is_identity_intermediate or "Mail_GUID" in dpm:
            try:
                val=_services.guid_value(dpm["Mail_GUID"]) if isinstance(dpm.get("Mail_GUID"),_services.pikepdf.String) else ""
            except (UnicodeError,ValueError,_services.pikepdf.PdfError):
                val=None
            if val is None:
                for code in (("DPM_MAIL_GUID_PRESENT","DPM_MAIL_GUID_MATCH") if is_identity_intermediate else ("DPM_MAIL_GUID_MATCH",)):
                    report.warn(code,"DPM GUID text could not be decoded",
                                {"objgen":item["objgen"],"assessment":"unavailable","actual":_services.observed_pdf_value(dpm["Mail_GUID"])})
                report.warn("VALIDATION_INCOMPLETE","Required DPM GUID presence and correspondence could not be assessed",
                            {"rules":(["DPM_MAIL_GUID_PRESENT","DPM_MAIL_GUID_MATCH"] if is_identity_intermediate else ["DPM_MAIL_GUID_MATCH"]),"assessment":"unavailable","objgen":item["objgen"]})
            elif val:
                if is_identity_intermediate:
                    report.ok("DPM_MAIL_GUID_PRESENT","Intermediate DPM node contains Mail_GUID",{"objgen":item["objgen"],"Mail_GUID":val})
                if val in mail_guid_set:
                    report.ok("DPM_MAIL_GUID_MATCH","DPM Mail_GUID matches document XMP",{"value":val})
                else:
                    report.fail("DPM_MAIL_GUID_MATCH","DPM Mail_GUID missing from document XMP",{"value":val,"objgen":item["objgen"]})
            else:
                code="DPM_MAIL_GUID_PRESENT" if is_identity_intermediate else "DPM_MAIL_GUID_MATCH"
                report.fail(code,"DPM Mail_GUID requires a nonempty PDF text string matching document XMP",{"objgen":item["objgen"],"actual":_services.observed_pdf_value(dpm.get("Mail_GUID"))})
        if "Mail_MessageID" in dpm:
            value=dpm["Mail_MessageID"]
            try:
                mid=_services.norm_text(str(value)) if isinstance(value,_services.pikepdf.String) else ""
            except (UnicodeError,ValueError,_services.pikepdf.PdfError):
                mid=None
            if mid is None:
                report.warn("DPM_MAIL_MESSAGEID_MATCH","DPM Mail_MessageID text could not be decoded",
                            {"objgen":item["objgen"],"assessment":"unavailable","value":_services.observed_pdf_value(value)})
                report.warn("VALIDATION_INCOMPLETE","Supplied DPM MessageID correspondence could not be assessed",
                            {"rules":["DPM_MAIL_MESSAGEID_MATCH"],"assessment":"unavailable","objgen":item["objgen"]})
            elif isinstance(value,_services.pikepdf.String) and mid in messageid_set:
                report.ok("DPM_MAIL_MESSAGEID_MATCH","DPM Mail_MessageID matches document XMP",{"value":mid})
            else:
                report.fail("DPM_MAIL_MESSAGEID_MATCH","DPM Mail_MessageID must be a PDF text string matching document XMP",{"value":_services.observed_pdf_value(value),"objgen":item["objgen"]})

        if is_leaf:
            leaf_nodes.append(item)
            if isinstance(_services.dict_get(item["node"],"/DPM",None),_services.pikepdf.Dictionary):
                report.ok("DPM_LEAF_REQUIRED","Leaf DPart node contains a /DPM dictionary",{"objgen":item["objgen"],"DPM":_services.observed_pdf_value(dpm)})
            else:
                report.fail("DPM_LEAF_REQUIRED","Leaf DPart node must contain a /DPM dictionary",{"objgen":item["objgen"]})
            ctype=_services.safe_name(dpm.get("Mail_ContentSetType",None))
            desc_value=dpm.get("Mail_Desc")
            desc_unavailable=False
            try:
                desc=_services.norm_text(desc_value) if isinstance(desc_value,_services.pikepdf.String) else ""
            except (UnicodeError,ValueError,_services.pikepdf.PdfError):
                desc=""
                desc_unavailable=True
            subtype=_services.safe_name(dpm.get("Mail_Subtype",None))
            if dpm.get("Mail_ContentSetType") is not None:
                report.ok("DPM_CONTENTSETTYPE_PRESENT","Leaf DPM node contains Mail_ContentSetType",{"objgen":item["objgen"],"type":ctype})
            else:
                report.fail("DPM_CONTENTSETTYPE_PRESENT","Leaf DPM node missing Mail_ContentSetType",{"objgen":item["objgen"]})
            if desc_unavailable:
                report.warn("DPM_DESC_REQUIRED","DPM description text could not be decoded",
                            {"objgen":item["objgen"],"assessment":"unavailable","actual":_services.observed_pdf_value(desc_value)})
                report.warn("VALIDATION_INCOMPLETE","DPM human-readable description could not be assessed",
                            {"rules":["DPM_DESC_REQUIRED"],"assessment":"unavailable","objgen":item["objgen"]})
            elif desc:
                report.ok("DPM_DESC_REQUIRED","Leaf DPM node contains Mail_Desc",{"objgen":item["objgen"],"Mail_Desc":desc})
            else:
                report.fail("DPM_DESC_REQUIRED","Leaf DPM node requires Mail_Desc as a human-readable PDF text string",{"objgen":item["objgen"],"actual":_services.observed_pdf_value(desc_value)})
            if ctype in ("BodyRendering","AttachmentRendering"):
                if dpm.get("Mail_Subtype") is not None:
                    report.ok("DPM_SUBTYPE_REQUIRED","Leaf DPM node contains Mail_Subtype where required",{"objgen":item["objgen"],"type":ctype,"subtype":subtype})
                else:
                    report.fail("DPM_SUBTYPE_REQUIRED","Leaf DPM node missing Mail_Subtype for rendering content set",{"objgen":item["objgen"],"type":ctype})
            if eapdf_conf in ("c","ci") and ctype=="AttachmentList":
                report.fail("DPM_ATTACHMENTLIST_FORBIDDEN","AttachmentList DPM content set forbidden in container profiles",{"objgen":item["objgen"]})
            if eapdf_conf in ("c","ci") and ctype in {"AttachmentRendering","BodyRendering","EmailHeaderRendering","RawEmailRendering"}:
                report.fail("DPM_CONTAINER_CONTENTSET_TYPES","Email rendering Content Set type is forbidden in container profiles",{"objgen":item["objgen"],"type":ctype,"profile":eapdf_conf})

            rng=_services.page_range_from_dpart(item,page_map)
            if rng is None:
                report.fail("CONTENT_SET_COUNT","Leaf DPart Start/End do not resolve to pages",{"objgen":item["objgen"]})
            else:
                start_idx,end_idx,start_og,_=rng
                if end_idx >= start_idx:
                    report.ok("CONTENT_SET_COUNT","Leaf DPart defines a content set page range",{"objgen":item["objgen"],"start_page":start_idx,"end_page":end_idx})
                nonblank=False
                for pno in range(start_idx,end_idx+1):
                    if _services.page_has_visibleish_content(pdf.pages[pno-1]):
                        nonblank=True
                        break
                if nonblank:
                    report.ok("CONTENT_SET_NONBLANK","Content set is not blank",{"objgen":item["objgen"],"start_page":start_idx,"end_page":end_idx})
                else:
                    report.fail("CONTENT_SET_NONBLANK","Content set appears blank",{"objgen":item["objgen"],"start_page":start_idx,"end_page":end_idx})
                if start_og in outline_pages:
                    report.ok("CONTENT_SET_OUTLINE_REF","First page of content set is referenced by an outline item",{"objgen":item["objgen"],"start_page":start_idx})
                else:
                    report.fail("CONTENT_SET_OUTLINE_REF","First page of content set is not referenced by any outline item",{"objgen":item["objgen"],"start_page":start_idx})

    if not leaf_nodes:
        report.fail("DPM_LEAF_REQUIRED","DPart tree contains no leaf content-set nodes",{"count":0})

    if eapdf_conf in ("s","si"):
        if len(msg_nodes)==1:
            report.ok("DPM_INTERMEDIATE_COUNT_SINGLE","Single-message profile has exactly one intermediate DPM email node",{"count":len(msg_nodes)})
        else:
            report.fail("DPM_INTERMEDIATE_COUNT_SINGLE","Single-message profile must have exactly one intermediate DPM email node",{"count":len(msg_nodes)})
        if len(leaf_nodes)>=1:
            report.ok("PROFILE_EMAIL_COUNT_SINGLE","Single-message profile has at least one content set",{"content_sets":len(leaf_nodes)})
        else:
            report.fail("PROFILE_EMAIL_COUNT_SINGLE","Single-message profile must have at least one content set",{"content_sets":len(leaf_nodes)})
    elif eapdf_conf in ("m","mi"):
        if len(msg_nodes)>=2:
            report.ok("DPM_INTERMEDIATE_COUNT_MULTI","Multi-message profile has at least two intermediate DPM email nodes",{"count":len(msg_nodes)})
        else:
            report.fail("DPM_INTERMEDIATE_COUNT_MULTI","Multi-message profile must have at least two intermediate DPM email nodes",{"count":len(msg_nodes)})
        if len(leaf_nodes)>=2:
            report.ok("PROFILE_EMAIL_COUNT_MULTI","Multi-message profile has at least two content sets",{"content_sets":len(leaf_nodes)})
        else:
            report.fail("PROFILE_EMAIL_COUNT_MULTI","Multi-message profile must have at least two content sets",{"content_sets":len(leaf_nodes)})
    elif eapdf_conf in ("c","ci"):
        if len(msg_nodes)==0:
            report.ok("DPM_INTERMEDIATE_COUNT_CONTAINER","Container profile has no intermediate DPM email nodes",{"count":len(msg_nodes)})
        else:
            report.fail("DPM_INTERMEDIATE_COUNT_CONTAINER","Container profile must not have intermediate DPM email nodes",{"count":len(msg_nodes)})
    if "DPM_KEY_PREFIX" not in report.seen_codes:
        report.ok("DPM_KEY_PREFIX","EA-PDF DPM fields use the Mail_ prefix; unrelated extension keys are allowed",{"nodes":len(nodes),"extension_keys":sorted({key for item in nodes for key in item["dpm"] if not key.startswith("Mail_")}),"scope":"Unprefixed extensions cannot satisfy required Mail_ fields; extension intent is not inferred from a key name."})
    if eapdf_conf in ("c","ci") and "DPM_ATTACHMENTLIST_FORBIDDEN" not in report.seen_codes:
        report.ok("DPM_ATTACHMENTLIST_FORBIDDEN","Container content sets do not use AttachmentList",{"content_sets":len(leaf_nodes)})
    if eapdf_conf in ("c","ci") and "DPM_CONTAINER_CONTENTSET_TYPES" not in report.seen_codes and leaf_nodes:
        report.ok("DPM_CONTAINER_CONTENTSET_TYPES","Container content sets do not use the four prohibited email rendering types",{"content_sets":len(leaf_nodes)})
    return leaf_nodes

def check_logical_structure(_services, pdf,eapdf_conf,report):
    st=_services.dict_get(pdf.Root,"/StructTreeRoot",None)
    if st is None:
        report.warn("STRUCTTREE_PRESENT","StructTreeRoot not present",{"StructTreeRoot":None})
        return
    report.ok("STRUCTTREE_PRESENT","StructTreeRoot present",{"StructTreeRoot":_services.observed_pdf_value(st)})
    k=_services.dict_get(st,"/K",None)
    root_elem=None
    if _services.is_pdf_array(k) or isinstance(k,list):
        if len(k)==1:
            root_elem=k[0]
    else:
        root_elem=k
    roles=_services.StructureRoles(st,str(pdf.pdf_version)=="2.0" or _services.safe_name(_services.dict_get(pdf.Root,"/Version",None))=="2.0")
    root_role,role_error=roles.element(root_elem) if isinstance(root_elem,_services.pikepdf.Dictionary) else ("","Root must be exactly one structure dictionary")
    if role_error==_services.StructureRoles.LIMIT:
        report.warn("STRUCTTREE_ROOT_DOCUMENT",role_error,{"assessment":"unavailable"})
        return
    if root_role=="Document":
        report.ok("STRUCTTREE_ROOT_DOCUMENT","StructTreeRoot K resolves through the applicable role map to exactly one Document dictionary",{"K":_services.observed_pdf_value(k),"array_length":len(k) if _services.is_pdf_array(k) or isinstance(k,list) else None})
    else:
        report.fail("STRUCTTREE_ROOT_DOCUMENT","StructTreeRoot K must resolve through the applicable role map to exactly one Document dictionary",{"K":_services.observed_pdf_value(k),"array_length":len(k) if _services.is_pdf_array(k) or isinstance(k,list) else None})
        return
    elems=[]
    _services.walk_struct_elems(root_elem,elems,None,class_map=_services.dict_get(st,"/ClassMap",None))
    attribute_errors=[{"object":e["objgen"],"errors":e["attribute_errors"]} for e in elems if e["attribute_errors"]]
    if attribute_errors:
        report.warn("VALIDATION_INCOMPLETE","Structure attributes could not be resolved completely",{"attributes":attribute_errors})
    custom=[e for e in elems if e["type"].startswith("Mail_")]
    custom_types={e["type"] for e in custom}
    rolemap=_services.normalize_role_map(_services.dict_get(st,"/RoleMap",None))
    required_map={"Mail_Message":"Document","Mail_ContentGroup":"Part","Mail_ContentSet":"Art"}
    if custom_types:
        missing=[]
        for k,v in required_map.items():
            if k in custom_types and rolemap.get(k)!=v:
                missing.append({"tag":k,"expected":v,"actual":rolemap.get(k,"")})
        if missing:
            report.fail("ROLEMAP_MAIL_TAGS","RoleMap missing or incorrect for EA-PDF custom tags",{"mappings":missing})
        else:
            report.ok("ROLEMAP_MAIL_TAGS","RoleMap contains required mappings for EA-PDF custom tags",{"count":len(custom_types),"mappings":rolemap,"custom_tags":sorted(custom_types)})
        namespaces=_services.dict_get(st,"/Namespaces",None)
        ns_ok=True
        limited=False
        if roles.pdf20:
            for element in custom:
                binding=_services.dict_get(element["elem"],"/NS",None)
                resolved,error=roles.element(element["elem"])
                limited |= error==_services.StructureRoles.LIMIT
                if (not isinstance(binding,_services.pikepdf.Dictionary) or _services.norm_text(_services.dict_get(binding,"/NS",""))!="https://pdfa.org/ns/ea-pdf/mail-1"
                    or _services.dict_get(_services.dict_get(binding,"/RoleMapNS",None),"/"+element["type"],None) is None
                    or (error!=_services.StructureRoles.LIMIT and (not resolved or (element["type"] in required_map and resolved!=required_map[element["type"]])))):
                    ns_ok=False
        if not ns_ok:
            report.fail("ROLEMAPNS_PRESENT","PDF 2.0 custom tags require registered EA-PDF namespaces and valid RoleMapNS chains to required standard roles",{"Namespaces":_services.observed_pdf_value(namespaces),"custom_tags":sorted(custom_types)})
        elif limited:
            report.warn("ROLEMAPNS_PRESENT",_services.StructureRoles.LIMIT,{"assessment":"unavailable"})
        else:
            report.ok("ROLEMAPNS_PRESENT","Namespace role mappings resolve to required standard roles",{"Namespaces":_services.observed_pdf_value(namespaces),"custom_tags":sorted(custom_types)})
    msg=[e for e in custom if e["type"]=="Mail_Message"]
    cset=[e for e in custom if e["type"]=="Mail_ContentSet"]
    cgroup=[e for e in custom if e["type"]=="Mail_ContentGroup"]
    fields=[e for e in custom if e["type"]=="Mail_Field"]
    fieldnames=[e for e in custom if e["type"]=="Mail_FieldName"]
    fieldvalues=[e for e in custom if e["type"]=="Mail_FieldValue"]
    if not custom_types.intersection(_services.MAIL_STRUCTURE_TYPES):
        for code in ("MAIL_MESSAGE_COUNT_SINGLE","MAIL_MESSAGE_COUNT_MULTI","MAIL_CONTENTSET_COUNT"):
            report.na(code,"Optional EA-PDF custom tags are not used",{"reason":"Standard logical structure does not require the optional EA-PDF custom vocabulary."})
        if eapdf_conf in ("c","ci"):
            report.ok("MAIL_MESSAGE_COUNT_CONTAINER","Container profile has no Mail_Message logical elements",{"count":0})
    elif eapdf_conf in ("s","si"):
        if len(msg)==1:
            report.ok("MAIL_MESSAGE_COUNT_SINGLE","Single-message profile has one Mail_Message logical element",{"count":len(msg)})
        else:
            report.fail("MAIL_MESSAGE_COUNT_SINGLE","Single-message profile must have exactly one Mail_Message logical element",{"count":len(msg)})
    elif eapdf_conf in ("m","mi"):
        if len(msg)>=2:
            report.ok("MAIL_MESSAGE_COUNT_MULTI","Multi-message profile has at least two Mail_Message logical elements",{"count":len(msg)})
        else:
            report.fail("MAIL_MESSAGE_COUNT_MULTI","Multi-message profile must have at least two Mail_Message logical elements",{"count":len(msg)})
    elif eapdf_conf in ("c","ci"):
        if len(msg)==0:
            report.ok("MAIL_MESSAGE_COUNT_CONTAINER","Container profile has no Mail_Message logical elements",{"count":len(msg)})
        else:
            report.fail("MAIL_MESSAGE_COUNT_CONTAINER","Container profile must not have Mail_Message logical elements",{"count":len(msg)})
    if cset:
        report.ok("MAIL_CONTENTSET_COUNT","Logical structure contains Mail_ContentSet elements",{"count":len(cset)})
    elif custom_types.intersection(_services.MAIL_STRUCTURE_TYPES):
        report.fail("MAIL_CONTENTSET_COUNT","Logical structure must contain at least one Mail_ContentSet element",{"count":len(cset)})
    # Table 11 constrains only the six defined custom tags as immediate children.
    # Standard tags and unrelated extension tags do not close that vocabulary.
    for elements,code,allowed,minimum in (
        (msg,"MAIL_MESSAGE_CHILDREN",{"Mail_ContentGroup","Mail_ContentSet"},1),
        (cgroup,"MAIL_CONTENTGROUP_CHILDREN",{"Mail_ContentSet"},1),
        (cset,"MAIL_CONTENTSET_CHILDREN",{"Mail_Field"},0),
        (fields,"MAIL_FIELD_CHILDREN",{"Mail_FieldName","Mail_FieldValue"},1),
    ):
        for e in elements:
            kids=[_services.safe_name(_services.dict_get(child,"/S",None)) for child in _services.struct_children(e["elem"])]
            forbidden=[tag for tag in kids if tag in _services.MAIL_STRUCTURE_TYPES and tag not in allowed]
            required={"Mail_FieldName"} if e["type"]=="Mail_Field" else allowed
            count=sum(tag in required for tag in kids)
            details={"objgen":e["objgen"],"children":kids,"allowed_custom_children":sorted(allowed),
                     "forbidden_custom_children":forbidden,"required_child_count":count,"minimum":minimum}
            if count>=minimum and not forbidden:
                report.ok(code,f"{e['type']} immediate children satisfy Table 11",details)
            else:
                report.fail(code,f"{e['type']} immediate children must satisfy Table 11",details)
    for e in fieldvalues:
        parent_kids=[_services.safe_name(_services.dict_get(child,"/S",None)) for child in _services.struct_children(e["parent"])]
        if _services.safe_name(_services.dict_get(e["parent"],"/S",None))=="Mail_Field" and "Mail_FieldName" in parent_kids:
            report.ok("MAIL_FIELDVALUE_WITH_NAME","Mail_FieldValue occurs with sibling Mail_FieldName",{"objgen":e["objgen"],"siblings":parent_kids})
        else:
            report.fail("MAIL_FIELDVALUE_WITH_NAME","Mail_FieldValue must not occur without Mail_FieldName in same Mail_Field",{"objgen":e["objgen"],"siblings":parent_kids})
    for code, elems_list, tag in (
        ("MAIL_FIELD_PROFILE_USE", fields, "Mail_Field"),
        ("MAIL_FIELDNAME_PROFILE_USE", fieldnames, "Mail_FieldName"),
        ("MAIL_FIELDVALUE_PROFILE_USE", fieldvalues, "Mail_FieldValue"),
    ):
        if eapdf_conf in ("c","ci") and elems_list:
            report.fail(code, f"Container profiles must not use {tag} tags", {"count":len(elems_list)})
        else:
            report.ok(code, f"{tag} usage is confined to permitted mail profiles", {"count":len(elems_list)})
    _services.check_user_properties(custom,report)

def check_structure_correspondence(_services, pdf,report):
    errors=[]
    def problem(message):
        if len(errors)<100:
            errors.append(message)
    actual={}
    scopes={}
    locations=set()
    object_pages={}
    stream_scopes={}
    for page_number,page in enumerate(pdf.pages,start=1):
        page_id=_services.pdf_object_identity(page.obj)
        scopes[page_id]=page.obj
        contents=_services.dict_get(page.obj,"/Contents",None)
        for stream in contents if _services.is_pdf_array(contents) else [contents]:
            if isinstance(stream,_services.pikepdf.Stream):
                stream_scopes[_services.pdf_object_identity(stream)]=page_id
        object_pages.setdefault(page_id,set()).add(page_id)
        for annotation in _services.dict_get(page.obj,"/Annots",[]):
            scopes[_services.pdf_object_identity(annotation)]=annotation
            object_pages.setdefault(_services.pdf_object_identity(annotation),set()).add(page_id)
        try:
            content=_services.analyze_page_content(page)
        except Exception as error:
            problem(f"Page {page_number}: {error}")
            continue
        for scope_id,scope in content["forms"].items():
            scopes[scope_id]=scope
            object_pages.setdefault(scope_id,set()).add(page_id)
        for sequence in content["sequences"]:
            mcid=sequence["mcid"]
            if mcid is None:
                continue
            scope=sequence["scope"]
            scope_id=_services.pdf_object_identity(scope)
            scopes[scope_id]=scope
            object_pages.setdefault(scope_id,set()).add(page_id)
            if not isinstance(mcid,int) or isinstance(mcid,bool) or mcid<0:
                problem(f"Page {page_number}: MCID must be a nonnegative integer")
                continue
            location=(scope_id,sequence["instruction_index"])
            key=(scope_id,mcid)
            if location in locations:
                continue
            locations.add(location)
            if key in actual:
                problem(f"Duplicate MCID {mcid} in content scope {scope_id}")
            actual[key]=sequence
    structure=_services.dict_get(pdf.Root,"/StructTreeRoot",None)
    if structure is None:
        if actual:
            problem("Marked content has MCIDs but no StructTreeRoot")
        if errors:
            report.fail("STRUCTURE_CORRESPONDENCE","Content markings are invalid",{"errors":errors})
        return
    parent_tree={}
    visited=set()
    def read_number_tree(node,depth=0):
        _services.visit_tree_node(node,visited,len(visited),depth,"ParentTree")
        entries=_services.dict_get(node,"/Nums",None)
        children=_services.dict_get(node,"/Kids",None)
        if entries is not None and children is not None:
            problem("ParentTree node has both Nums and Kids")
        keys=[]
        if entries is not None:
            if not _services.is_pdf_array(entries) or len(entries)%2:
                raise ValueError("ParentTree Nums must contain key/value pairs")
            for index in range(0,len(entries),2):
                key=entries[index]
                if not isinstance(key,int) or isinstance(key,bool) or key<0:
                    raise ValueError("ParentTree keys must be nonnegative integers")
                if key in parent_tree or (keys and key<=keys[-1]):
                    problem("ParentTree keys are duplicated or out of order")
                keys.append(key)
                parent_tree[key]=entries[index+1]
                if len(parent_tree)>_services.MAX_TREE_ITEMS:
                    raise ValueError("Excessively large ParentTree")
        if children is not None:
            if not _services.is_pdf_array(children) or not len(children):
                raise ValueError("ParentTree Kids must be a nonempty array")
            for child in children:
                child_keys=read_number_tree(child,depth+1)
                if child_keys and keys and child_keys[0]<=keys[-1]:
                    problem("ParentTree child key ranges overlap or are out of order")
                keys.extend(child_keys)
        limits=_services.dict_get(node,"/Limits",None)
        if limits is not None and (not keys or list(limits)!=[keys[0],keys[-1]]):
            problem("ParentTree Limits do not describe its actual keys")
        return keys
    try:
        tree=_services.dict_get(structure,"/ParentTree",None)
        if tree is not None:
            read_number_tree(tree)
        owners={}
        object_owners={}
        elements={}
        visited_elements=set()
        def walk_element(element,parent,inherited_page=None,depth=0):
            _services.visit_tree_node(element,visited_elements,len(visited_elements),depth,"structure correspondence")
            element_id=_services.pdf_object_identity(element)
            elements[element_id]=element
            if not _services.objgen(element) or _services.objgen(element)==(0,0):
                problem("Structure element is not an indirect object")
            if _services.pdf_object_identity(_services.dict_get(element,"/P",None))!=_services.pdf_object_identity(parent):
                problem(f"Structure element {element_id} has the wrong parent")
            if not isinstance(_services.dict_get(element,"/S",None),_services.pikepdf.Name):
                problem(f"Structure element {element_id} has no structure type name")
            page=_services.dict_get(element,"/Pg",inherited_page)
            linked_content=set()
            # ISO 32000-2 Table 355 makes K optional (for example, a blank cell).
            kids=_services.dict_get(element,"/K",None)
            entries=[] if kids is None else kids if _services.is_pdf_array(kids) else [kids]
            for child in entries:
                if isinstance(child,_services.pikepdf.Dictionary) and (_services.dict_get(child,"/S",None) is not None or _services.safe_name(_services.dict_get(child,"/Type",None))=="StructElem"):
                    linked_content.update(walk_element(child,element,page,depth+1))
                    continue
                if isinstance(child,int) and not isinstance(child,bool):
                    mcid=child
                    target=page
                    content_page=page
                elif _services.safe_name(_services.dict_get(child,"/Type",None))=="MCR":
                    mcid=_services.dict_get(child,"/MCID",None)
                    content_page=_services.dict_get(child,"/Pg",page)
                    target=_services.dict_get(child,"/Stm",content_page)
                elif _services.safe_name(_services.dict_get(child,"/Type",None))=="OBJR":
                    target=_services.dict_get(child,"/Obj",None)
                    target_id=_services.pdf_object_identity(target)
                    if target is None or target_id in object_owners:
                        problem("OBJR is missing its object or references it more than once")
                    object_owners[target_id]=element_id
                    if target_id in object_pages:
                        linked_content.add((target_id,None))
                    content_page=_services.dict_get(child,"/Pg",page)
                    if content_page is not None and _services.pdf_object_identity(content_page) not in object_pages.get(target_id,set()):
                        problem("OBJR object is not on its declared page")
                    scopes[target_id]=target
                    continue
                else:
                    problem(f"Structure element {element_id} has an invalid K entry")
                    continue
                if not isinstance(mcid,int) or isinstance(mcid,bool) or mcid<0 or target is None:
                    problem("Structure content reference requires a nonnegative MCID and page or stream")
                    continue
                target_id=_services.pdf_object_identity(target)
                target_id=stream_scopes.get(target_id,target_id)
                key=(target_id,mcid)
                if key in owners:
                    problem(f"MCID {mcid} is referenced by more than one structure entry")
                owners[key]=element_id
                if key not in actual:
                    problem(f"Structure references missing MCID {mcid} in {target_id}")
                else:
                    linked_content.add(key)
                if content_page is None or _services.pdf_object_identity(content_page) not in object_pages.get(target_id,set()):
                    problem("Structure content reference does not point to a page using that content")
            if _services.safe_name(_services.dict_get(element,"/S",None)) in ("Mail_Message","Mail_ContentGroup","Mail_ContentSet") and not linked_content:
                problem(f"EA-PDF structure element {element_id} has no content connection")
            return linked_content
        roots=_services.dict_get(structure,"/K",[])
        for root in roots if _services.is_pdf_array(roots) else [roots]:
            walk_element(root,structure)
        used_keys={}
        expected_entries={}
        for key in actual:
            scope_id,mcid=key
            if key not in owners:
                problem(f"Marked MCID {mcid} in {scope_id} has no structure reference")
            scope=scopes[scope_id]
            tree_key=_services.dict_get(scope,"/StructParents",None)
            if not isinstance(tree_key,int) or isinstance(tree_key,bool) or tree_key<0:
                problem(f"Content scope {scope_id} has no valid StructParents key")
                continue
            if tree_key in used_keys and used_keys[tree_key]!=scope_id:
                problem("Different content scopes share a StructParents key")
            used_keys[tree_key]=scope_id
            expected_entries[(tree_key,mcid)]=owners.get(key)
        for target_id,owner in object_owners.items():
            tree_key=_services.dict_get(scopes[target_id],"/StructParent",None)
            if not isinstance(tree_key,int) or isinstance(tree_key,bool) or tree_key<0 or tree_key in used_keys:
                problem("OBJR object has a missing, invalid, or reused StructParent key")
                continue
            used_keys[tree_key]=target_id
            expected_entries[(tree_key,None)]=owner
        marked_scopes={key[0] for key in actual}
        for target_id,target in scopes.items():
            single=_services.dict_get(target,"/StructParent",None)
            plural=_services.dict_get(target,"/StructParents",None)
            if single is not None and target_id not in object_owners:
                problem("Object with StructParent has no OBJR structure reference")
            if plural is not None and target_id not in marked_scopes:
                if plural in parent_tree and any(item is not None for item in parent_tree[plural]):
                    problem("StructParents lookup contains entries for content with no MCIDs")
        for (tree_key,mcid),owner in expected_entries.items():
            entry=parent_tree.get(tree_key)
            if mcid is not None:
                entry=entry[mcid] if _services.is_pdf_array(entry) and mcid<len(entry) else None
            if entry is None or owner is None or _services.pdf_object_identity(entry)!=owner:
                problem(f"ParentTree entry {tree_key}/{mcid} disagrees with its structure owner")
        for tree_key,entry in parent_tree.items():
            entries=enumerate(entry) if _services.is_pdf_array(entry) else [(None,entry)]
            for mcid,owner in entries:
                if owner is not None and (tree_key,mcid) not in expected_entries:
                    problem(f"Orphan ParentTree entry {tree_key}/{mcid}")
        next_key=_services.dict_get(structure,"/ParentTreeNextKey",None)
        if next_key is not None and (not isinstance(next_key,int) or isinstance(next_key,bool) or next_key<0 or (parent_tree and next_key<=max(parent_tree))):
            problem("ParentTreeNextKey does not exceed existing keys")
    except Exception as error:
        problem(str(error))
    if errors:
        report.fail("STRUCTURE_CORRESPONDENCE","Page markings, structure references, and ParentTree disagree",{"errors":errors})
    else:
        report.ok("STRUCTURE_CORRESPONDENCE","Page markings, structure references, and ParentTree agree in both directions",{"marked_content":len(actual),"parent_tree_keys":len(parent_tree)})

def check_user_properties(_services, custom_elems,report):
    allowed={
        "Mail_MessageID":("string",{"Mail_Message"}),
        "Mail_GUID":("string",{"Mail_Message"}),
        "Mail_ContentSetType":("string",{"Mail_ContentGroup","Mail_ContentSet"}),
        "Mail_Subtype":("string",{"Mail_ContentSet"}),
        "Mail_To":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Raw_To":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_From":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Raw_From":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Sent":("pdf-date",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Raw_Sent":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Subject":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_SizeInBytes":("nonneg-int",{"Mail_Message","Mail_FieldValue"}),
        "Mail_NumberAttachments":("nonneg-int",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Cc":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Raw_Cc":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Bcc":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_Raw_Bcc":("string",{"Mail_Message","Mail_FieldValue"}),
        "Mail_InReplyTo":("string",{"Mail_Message","Mail_FieldValue"}),
    }
    for elem in custom_elems:
        for prop in _services.parse_userproperties(elem["attrs"]):
            raw_name=_services.dict_get(prop,"/N",None)
            name=_services.userprop_name(prop)
            value=_services.dict_get(prop,"/V",None)
            if _services.is_pdf_ascii_string(raw_name):
                report.ok("USERPROPS_NAME_ASCII","UserProperties N entry is a PDF ASCII string",{"name":name,"tag":elem["type"]})
            else:
                raw=bytes(raw_name) if isinstance(raw_name,_services.pikepdf.String) else None
                report.fail("USERPROPS_NAME_ASCII","UserProperties N must use PDF ASCII string bytes",{
                    "name":name,"tag":elem["type"],"is_pdf_string":raw is not None,
                    "raw_hex":raw[:64].hex() if raw is not None else None,
                    "byte_length":len(raw) if raw is not None else None,
                    "raw_hex_truncated":raw is not None and len(raw)>64})
            if name in allowed:
                kind,scope=allowed[name]
                if elem["type"] in scope:
                    report.ok("USERPROPS_TAG_SCOPE","UserProperties attribute used on allowed tag",{"name":name,"tag":elem["type"]})
                else:
                    report.fail("USERPROPS_TAG_SCOPE","UserProperties attribute used on disallowed tag",{"name":name,"tag":elem["type"],"allowed_tags":sorted(scope)})
                actual_kind=_services.userprop_value_kind(value)
                good=False
                if kind=="string":
                    good=actual_kind=="string"
                elif kind=="pdf-date":
                    good=actual_kind=="string" and _services.looks_like_pdf_date(value)
                elif kind=="nonneg-int":
                    good=actual_kind=="int" and value>=0
                if good:
                    report.ok("USERPROPS_TYPE","UserProperties V entry has allowed type",{"name":name,"tag":elem["type"],"actual":actual_kind,"value":_services.observed_pdf_value(value)})
                else:
                    report.fail("USERPROPS_TYPE","UserProperties V entry has wrong type",{"name":name,"tag":elem["type"],"expected":kind,"actual":actual_kind,"value":_services.observed_pdf_value(value)})
            else:
                actual_kind=_services.userprop_value_kind(value)
                mail_field=name is not None and name.startswith("Mail_")
                if value is None:
                    report.fail("USERPROPS_TYPE","UserProperties V entry is required",{"name":name,"tag":elem["type"],"actual":"missing"})
                elif not mail_field:
                    report.ok("USERPROPS_TYPE","Generic UserProperties V entry is present; no EA field type is prescribed",{"name":name,"tag":elem["type"],"actual":actual_kind,"value":_services.observed_pdf_value(value)})
                elif actual_kind in ("string","int","real","bool"):
                    report.ok("USERPROPS_TYPE","UserProperties custom Mail_* field uses permitted generic type",{"name":name,"tag":elem["type"],"actual":actual_kind,"value":_services.observed_pdf_value(value)})
                else:
                    report.fail("USERPROPS_TYPE","UserProperties custom Mail_* field must be string, number, or Boolean",{"name":name,"tag":elem["type"],"actual":actual_kind})
                if mail_field:
                    scope={"Mail_Message","Mail_FieldName","Mail_FieldValue"}
                    if elem["type"] in scope:
                        report.ok("USERPROPS_TAG_SCOPE","Custom email header attribute used on allowed tag",{"name":name,"tag":elem["type"]})
                    else:
                        report.fail("USERPROPS_TAG_SCOPE","Custom email header attribute used on disallowed tag",{"name":name,"tag":elem["type"],"allowed_tags":sorted(scope)})

def check_user_property_source_values(_services, elems,root_xml,afiles,report):
    code="USERPROPS_VALUES_UNMODIFIED"
    candidates=[]
    for elem in elems:
        if not elem["type"].startswith("Mail_"):
            continue
        for prop in _services.parse_userproperties(elem["attrs"]):
            name=_services.userprop_name(prop)
            if name is not None and name.startswith("Mail_") and name not in {"Mail_GUID","Mail_ContentSetType","Mail_Subtype"}:
                candidates.append((elem,prop,name))
    sources=_services.summarize_source_emails(afiles)
    if not candidates or not sources:
        reason=("No source-derived mail UserProperties are declared." if not candidates else
                "No inspectable retained source email is available for attribute comparison.")
        report.na(code,"Source-value recommendation cannot be compared",{"reason":reason})
        return
    records=_services.flatten_email_records(root_xml) if root_xml is not None else []
    by_element={id(elem["elem"]):elem for elem in elems}
    header_names={"Mail_MessageID":"message-id","Mail_InReplyTo":"in-reply-to","Mail_Sent":"date",
                  "Mail_Raw_Sent":"date"}
    for field in ("To","From","Cc","Bcc","Subject"):
        header_names["Mail_"+field]=field.lower()
        if field!="Subject":
            header_names["Mail_Raw_"+field]=field.lower()

    def unfolded(text):
        # Unfold field lines and remove separator padding; retain internal spaces.
        return _services.re.sub(r"\r?\n(?=[ \t])","",str(text)).strip(" \t")

    def date_value(value):
        text=str(value)
        if not _services.looks_like_pdf_date(value):
            return None
        match=_services.re.fullmatch(r"D:([0-9]{4}(?:[0-9]{2}){0,5})(.*)",text)
        if match is None:
            return None
        local,suffix=match.groups()
        fields=[int(local[index:index+2]) if len(local)>index else default
                for index,default in ((4,1),(6,1),(8,0),(10,0),(12,0))]
        digits=suffix[1:].replace("'","") if suffix else ""
        offset=_services.timedelta(hours=int(digits[:2] or 0),minutes=int(digits[2:] or 0))
        if suffix.startswith("-"):
            offset=-offset
        try:
            return _services.datetime(int(local[:4]),*fields,tzinfo=_services.timezone(offset))
        except ValueError:
            return None

    for elem,prop,name in candidates:
        value=_services.dict_get(prop,"/V",None)
        kind=_services.userprop_value_kind(value)
        detail={"name":name,"tag":elem["type"],"object":elem["objgen"],"value":_services.observed_pdf_value(value)}
        owner=elem
        seen=set()
        while owner is not None and owner["type"]!="Mail_Message":
            if id(owner) in seen:
                owner=None
                break
            seen.add(id(owner))
            owner=by_element.get(id(owner["parent"]))
        identity={}
        if owner is not None:
            try:
                for prop in _services.parse_userproperties(owner["attrs"]):
                    identity_name=_services.userprop_name(prop)
                    if identity_name not in {"Mail_GUID","Mail_MessageID"}:
                        continue
                    identity_value=_services.dict_get(prop,"/V",None)
                    if not isinstance(identity_value,_services.pikepdf.String):
                        raise ValueError("Message identity is not a PDF string")
                    identity[identity_name]=str(identity_value)
            except (UnicodeError,ValueError,_services.pikepdf.PdfError):
                identity={}  # Wrong/unreadable identity data cannot select a source owner.
        guid=identity.get("Mail_GUID","")
        message_id=identity.get("Mail_MessageID","")
        owners=[record for record in records if
                (guid and _services.identity_fields(record)["Mail_GUID"]==guid) or
                (not guid and message_id and _services.identity_fields(record)["Message-ID"]==message_id)]
        source=_services.source_email_for_record(owners[0],records,sources,getattr(report,"_email_source_summaries",None)) if len(owners)==1 else None
        if source is None:
            report.warn(code,"Attribute source comparison is unavailable",dict(detail,
                comparison="unavailable",reason="The enclosing message, its XMP identity or its original source is not unique.",
                Mail_GUID=guid))
            continue
        detail.update(Mail_GUID=guid,source=source["source_filename"],source_sha256=source["source_sha256"])
        if name=="Mail_NumberAttachments" and not source.get("attachment_inventory_available",True):
            report.warn(code,"Original Outlook attachment count is unavailable",dict(detail,assessment="unavailable"))
            continue
        if name in {"Mail_SizeInBytes","Mail_NumberAttachments"}:
            expected=source["size_in_bytes" if name=="Mail_SizeInBytes" else "mime_attachment_count"]
            if kind!="int" or value<0:
                report.na(code,"Invalid attribute type is checked separately",dict(detail,reason="USERPROPS_TYPE checks this nonnegative integer constraint."))
                continue
            equal=value==expected
        elif name=="Mail_Sent":
            expected=source.get("sent_norm","")
            if kind!="string" or not _services.looks_like_pdf_date(value):
                report.na(code,"Invalid attribute date is checked separately",dict(detail,reason="USERPROPS_TYPE checks the PDF-date constraint."))
                continue
            original=_services.parse_xmp_datetime(expected)
            actual=date_value(value)
            if original is None or actual is None:
                report.warn(code,"Attribute date comparison is unavailable",dict(detail,
                    comparison="unavailable",reason="The source date or PDF date cannot be converted to a comparable instant."))
                continue
            equal=actual is not None and actual==original
        else:
            header=header_names.get(name,name[5:].lower())
            original_values=source.get("header_values",{}).get(header,[])
            if "header_values" not in source or len(original_values)>1:
                report.warn(code,"Attribute header comparison is unavailable",dict(detail,
                    comparison="unavailable",reason="Original header values are unavailable or the header occurs more than once."))
                continue
            expected=unfolded(original_values[0]) if original_values else ""
            if not name.startswith("Mail_Raw_"):
                try:
                    expected=_services.decoded_source_header(expected)
                except (LookupError,UnicodeError,ValueError):
                    report.warn(code,"Attribute header decoding is unavailable",dict(detail,
                        comparison="unavailable",reason="The original header encoding cannot be decoded."))
                    continue
            if kind=="string":
                try:
                    actual=unfolded(value) if name.startswith("Mail_Raw_") else str(value)
                except (UnicodeError,ValueError,_services.pikepdf.PdfError):
                    report.warn(code,"Attribute source text comparison is unavailable",dict(detail,
                        comparison="unavailable",reason="The attribute string cannot be decoded for header comparison."))
                    continue
                equal=actual==expected
            elif name not in header_names and kind in {"int","real","bool"}:
                try:
                    equal=(expected.lower()==str(value).lower() if kind=="bool" else _services.Decimal(str(value))==_services.Decimal(expected))
                except _services.InvalidOperation:
                    equal=False
            else:
                report.na(code,"Invalid attribute type is checked separately",dict(detail,reason="USERPROPS_TYPE checks this header's value type."))
                continue
        detail.update(expected=expected,comparison="equal" if equal else "different")
        if equal:
            report.ok(code,"Attribute value agrees with its owning source email",detail)
        else:
            report.warn(code,"Attribute value SHOULD NOT be truncated or modified from its owning source email",detail)

def check_content_ownership_evidence(_services, pdf,xroot,report):
    evidence=_services.independent_content_ownership(pdf,xroot,report)
    nodes=evidence["nodes"];by_object={node["objgen"]:node for node in nodes}
    leaves=[node for node in nodes if not node["child_count"]]
    pages=_services.page_objgen_map(pdf);declared=set();complete=bool(leaves);unavailable_ancestry=False
    code="DPM_EMAIL_CONTENT_SET_OWNERSHIP"
    for node in leaves:
        if node["objgen"] in evidence["document_wide"]:
            continue
        owner,owner_state=_services.dpart_ancestor_guid(node,by_object)
        unavailable_ancestry=unavailable_ancestry or owner_state=="unavailable"
        independent=evidence["owners"].get(node["objgen"],set())
        bounds=_services.page_range_from_dpart(node,pages)
        kind=_services.safe_name(node["dpm"].get("Mail_ContentSetType"))
        details={"assertion":"DPM-EMAIL-OWNERSHIP","objgen":node["objgen"],"content_set_type":kind,
                 "dpm_owner":owner,"dpm_owner_state":owner_state,"independent_owners":sorted(independent)}
        if owner and bounds:
            declared.add(owner)
        leaf_guid=node["dpm"].get("Mail_GUID")
        try:
            conflict=bool(owner and isinstance(leaf_guid,_services.pikepdf.String) and str(leaf_guid)!=owner)
        except (ValueError,UnicodeError,_services.pikepdf.PdfError):
            conflict=False
        if ((owner_state in {"missing","conflicting"} and kind in {"BodyRendering","AttachmentRendering","EmailHeaderRendering","RawEmailRendering"})
                or conflict or owner and len(independent)==1 and owner not in independent):
            complete=False
            report.fail(code,"Content-set ownership contradicts its email DPart ancestry",details)
        elif not bounds or not owner or len(independent)!=1:
            complete=False
            _services.evidence_unavailable(report,code,"Content-set ownership cannot be proved from DPart identifiers alone",details)
        else:
            evidence["rendered_guids"].add(owner)
            report.ok(code,"Independently connected email content agrees with its DPart ancestor",details)
    for record in _services.flatten_email_records(xroot) if xroot is not None else []:
        guid=_services.guid_value(record.get("Mail_GUID",""))
        if not guid:
            complete=False
        elif guid not in declared and not unavailable_ancestry:
            complete=False
            report.fail(code,"An email XMP record has no reachable content set under its email DPart node",
                        {"assertion":"DPM-EMAIL-OWNERSHIP","Mail_GUID":guid})
    if not leaves:
        _services.evidence_unavailable(report,code,"No reachable content sets establish email ownership",{"assertion":"DPM-EMAIL-OWNERSHIP"})
    evidence["complete"]=complete
    return evidence

def check_tagging_recommendations(_services, pdf,dpart_nodes,elements,report,xroot=None,afiles=(),page_analysis=None):
    code="CUSTOM_CONTENTSETS_TAGGEDPDF_RECOMMENDED"
    pages=_services.page_objgen_map(pdf)
    scoped=set()
    content_sets=[]
    owner_ranges=[]
    by_node={node["objgen"]:node for node in dpart_nodes if node.get("objgen")}
    unresolved=[]
    for node in dpart_nodes:
        if node.get("start") is None and node.get("end") is None:
            continue
        span=_services.page_range_from_dpart(node,pages)
        content_sets.append({"object":node["objgen"],"type":_services.safe_name(node["dpm"].get("Mail_ContentSetType"))})
        if span is None:
            unresolved.append({"object":node["objgen"],"reason":"Content Set page range is unavailable"})
        else:
            scoped.update(range(span[0],span[1]+1))
            owner_ranges.append({"identity":_services.dpart_message_identity(node,by_node),"pages":list(range(span[0],span[1]+1))})
    details={"strength":"SHOULD","scope":"All declared DPart Content Sets, a conservative superset of custom Content Sets",
             "content_sets":content_sets,"pages":sorted(scoped),
             "limits":"Syntactic content/annotation connections only; semantic equivalence and identification of every custom Content Set are not certified."}
    object_references=set()
    for element in elements:
        children=_services.dict_get(element["elem"],"/K",[])
        for child in children if _services.is_pdf_array(children) else [children]:
            if _services.safe_name(_services.dict_get(child,"/Type",None))=="OBJR":
                target=_services.dict_get(child,"/Obj",None)
                if target is not None:
                    object_references.add(_services.pdf_object_identity(target))
    structure=_services.dict_get(pdf.Root,"/StructTreeRoot",None)
    marked=_services.dict_get(_services.dict_get(pdf.Root,"/MarkInfo",None),"/Marked",False) is True
    coverage=[]
    for number in sorted(scoped):
        paint=_services.analyze_page_content(pdf.pages[number-1])["paint_coverage"]
        coverage.append(dict(page=number,**paint))
        if paint["unmarked"] or not paint["structured"]:
            unresolved.append({"page":number,"reason":"Unmarked paint or no structurally marked non-artifact paint",**paint})
        for annotation in _services.dict_get(pdf.pages[number-1].obj,"/Annots",[]):
            if _services.pdf_object_identity(annotation) not in object_references:
                unresolved.append({"page":number,"object":_services.objgen(annotation),"reason":"Annotation has no structure object reference"})
    details["page_coverage"]=coverage
    correspondence=[item["level"] for item in report.items if item["code"]=="STRUCTURE_CORRESPONDENCE"]
    if structure is not None and not correspondence:
        evidence=_services.Report(report.path)
        _services.check_structure_correspondence(pdf,evidence)
        correspondence=[item["level"] for item in evidence.items if item["code"]=="STRUCTURE_CORRESPONDENCE"]
    connected=bool(correspondence) and set(correspondence)=={"PASS"}
    if content_sets and scoped and structure is not None and marked and connected and not unresolved:
        _services.emit_if_unseen(report,"ok",code,"Declared Content Set page streams have connected structure coverage and a Tagged PDF declaration",details)
    else:
        details.update(assessment="unavailable" if not content_sets else "partial",unresolved=unresolved,
                       structure_present=structure is not None,marked=marked,correspondence_verified=connected,
                       reason="A structure root alone does not prove tagged page content; the custom subset and annotation semantics still require review.")
        _services.emit_if_unseen(report,"warn",code,"Tagged content coverage could not be established for every declared Content Set page",details)

    code="HEADER_FIELDS_TAGGED_RECOMMENDED"
    custom=[element for element in elements if element["type"].startswith("Mail_")]
    if not custom:
        _services.emit_if_unseen(report,"na",code,"Header-tagging recommendation is conditional on custom EA-PDF tags",
                       {"reason":"No custom EA-PDF structure tags are used; the section 9.7 custom-tag premise is absent."})
        return
    field_tags=[element for element in custom if element["type"] in ("Mail_FieldName","Mail_FieldValue")]
    fields=[element for element in custom if element["type"]=="Mail_Field"]
    text={item["field_objgen"]:item for item in _services.structure_field_text_sequences(pdf)}
    verified=[]
    unresolved=[]
    field_ids={field["objgen"] for field in fields}
    for element in field_tags:
        if element["parent_objgen"] not in field_ids:
            unresolved.append({"object":element["objgen"],"reason":"Field markup has no immediate Mail_Field parent"})
    for field in fields:
        children=[element for element in field_tags if element["parent_objgen"]==field["objgen"]]
        names=[element for element in children if element["type"]=="Mail_FieldName"]
        missing=[element["objgen"] for element in children if not text.get(element["objgen"],{}).get("has_text_object")]
        if not names or missing:
            unresolved.append({"object":field["objgen"],"reason":"Missing rendered field-name or name/value text connection","unrendered":missing})
        else:
            verified.append({"object":field["objgen"],"names":len(names),
                             "values":sum(element["type"]=="Mail_FieldValue" for element in children)})
    # Observed field pairs cannot prove that no rendered header was omitted from
    # tagging. That requires a source/rendered-header inventory beyond this view.
    source_inventory=[]
    inventory=_services.observed_header_source_inventory(elements,page_analysis,xroot,afiles,owner_ranges,source_inventory,
                                              getattr(report,"_email_source_summaries",None))
    _services.emit_if_unseen(report,"warn",code,"Observed header markup does not establish complete tagging of all rendered headers",
                   {"strength":"SHOULD","assessment":"partial" if verified else "unavailable",
                    "verified_fields":verified,"unresolved_fields":unresolved,"rendered_header_inventory":inventory,
                    "source_header_inventory":source_inventory,
                    "source_inventory_scope":"Ordered parsed headers of uniquely selected retained sources for custom Mail_Message owners. Unobserved source occurrences do not establish missing tags or whether a header was rendered.",
                    "inventory_scope":"Observed custom Mail_Field elements only; standard-tagged, untagged and unrecognized rendered headers are not enumerated.",
                    "needed_evidence":"An inventory of all rendered header names and nonempty values, matched to their structure/content connections."})

def check_content_set_tag_coverage(_services, pdf,leaf_nodes,content_rows,report):
    """Prove each Content Set has a connected tag; counts do not establish counterparts."""
    code="CONTENT_SET_MAIL_CONTENTSET_TAGGED"
    details={"mail_contentset_tags":len(content_rows),"content_sets":len(leaf_nodes)}
    work=0
    def tick(items=1):
        nonlocal work
        work+=items
        if work>_services.MAX_TREE_ITEMS:
            raise ValueError("Content Set coverage exceeds configured traversal limits")
    try:
        if any(item["code"]=="STRUCTURE_CORRESPONDENCE" and item["level"]=="FAIL" for item in report.items):
            raise ValueError("Structure/content correspondence is invalid; tag counterparts cannot be verified")
        if not leaf_nodes:
            raise ValueError("DPart Content Set inventory is unavailable")
        tick(len(pdf.pages))
        page_map=_services.page_objgen_map(pdf)
        targets=set(range(1,len(leaf_nodes)+1))
        by_node={_services.pdf_object_identity(item["node"]):number for number,item in enumerate(leaf_nodes,1)}
        page_sets=_services.defaultdict(set)
        invalid=set()
        unavailable=[]
        for number,item in enumerate(leaf_nodes,1):
            tick()
            bounds=_services.page_range_from_dpart(item,page_map) if isinstance(item["node"],_services.pikepdf.Dictionary) else None
            if bounds is None:
                invalid.add(number)
                unavailable.append({"content_set":number,"reason":"Content Set dictionary or page range is unavailable"})
                continue
            for page in range(bounds[0],bounds[1]+1):
                tick()
                page_sets[page].add(number)
        covered=set()
        possible=set(invalid)
        page_links={}
        identities=[tuple(row.get("objgen") or ()) for row in content_rows]
        aliased=len(set(identities))!=len(identities)
        for row in content_rows:
            tick()
            known=set()
            uncertain=set()
            unknown=aliased or bool(row["unavailable"]) or not row["references"]
            for reference in row["references"]:
                tick()
                choices=set()
                reference_unknown=False
                pages=reference["pages"]
                if not pages:
                    unknown=True
                for page in pages:
                    tick()
                    if not isinstance(page,int) or isinstance(page,bool) or not 1<=page<=len(pdf.pages):
                        unknown=True
                        continue
                    if page not in page_links:
                        page_links[page]=by_node.get(_services.pdf_object_identity(_services.dict_get(pdf.pages[page-1].obj,"/DPart",None)))
                    direct=page_links[page]
                    ranges=page_sets.get(page,set())
                    tick(len(ranges))
                    if len(ranges)==1 and direct in ranges:
                        choices.add(direct)
                    else:
                        reference_unknown=True
                        choices.update(ranges)
                        if direct is not None:
                            choices.add(direct)
                        if not choices:
                            unknown=True
                if reference_unknown or len(choices)!=1:
                    uncertain.update(choices)
                else:
                    known.update(choices)
            if unknown:
                possible.update(targets)
                unavailable.append({"tag_objgen":row["objgen"],"reason":"Tag content connection is unavailable or its structure identity is not distinct"})
            elif uncertain or len(known)!=1:
                possible.update(known|uncertain)
                unavailable.append({"tag_objgen":row["objgen"],"content_set_candidates":sorted(known|uncertain),
                                    "reason":"Tag does not establish a single Content Set counterpart"})
            else:
                covered.update(known)
        missing=targets-covered
        unverified=missing&possible
        definite=missing-possible
        details.update(verified_content_sets=sorted(covered),untagged_content_sets=sorted(definite),
                       unverified_content_sets=sorted(unverified))
        if unverified:
            details["unavailable"]=unavailable[:25]
        if definite:
            report.fail(code,"Some DPart Content Sets have no corresponding Mail_ContentSet tag content",details)
        elif unverified:
            report.warn(code,"Content Set custom-tag coverage remains unavailable",dict(details,assessment="unavailable"))
        else:
            report.ok(code,"Every DPart Content Set has verified Mail_ContentSet tag content",details)
        if unverified:
            report.warn("VALIDATION_INCOMPLETE","Some Content Set tag counterparts remain unavailable",
                        {"rules":[code],"assessment":"unavailable","content_sets":sorted(unverified)})
    except (ValueError,TypeError,_services.pikepdf.PdfError) as error:
        report.warn(code,"Content Set custom-tag coverage remains unavailable",
                    dict(details,assessment="unavailable",reason=str(error)))
        report.warn("VALIDATION_INCOMPLETE","Content Set tag coverage could not be established",
                    {"rules":[code],"assessment":"unavailable"})

def check_content_set_art_usage(_services, pdf,leaf_nodes,content_rows,report):
    """Check each observed article's actual Content Set counterpart, not counts."""
    code="MAIL_CONTENTSET_ART_ONLY"
    details={"articles":len(content_rows),"content_sets":len(leaf_nodes)}
    work=0
    def tick(items=1):
        nonlocal work
        work+=items
        if work>_services.MAX_TREE_ITEMS:
            raise ValueError("Art Content Set assessment exceeds configured traversal limits")
    try:
        if not leaf_nodes:
            raise ValueError("DPart Content Set inventory is unavailable")
        if any(item["code"]=="STRUCTURE_CORRESPONDENCE" and item["level"]=="FAIL" for item in report.items):
            raise ValueError("Structure/content correspondence is invalid")
        tick(len(pdf.pages))
        page_map=_services.page_objgen_map(pdf)
        page_sets=_services.defaultdict(set)
        by_node={_services.pdf_object_identity(item["node"]):number for number,item in enumerate(leaf_nodes,1)}
        for number,item in enumerate(leaf_nodes,1):
            tick()
            bounds=_services.page_range_from_dpart(item,page_map) if isinstance(item["node"],_services.pikepdf.Dictionary) else None
            if bounds is None:
                raise ValueError("DPart Content Set page range is unavailable")
            for page in range(bounds[0],bounds[1]+1):
                tick()
                page_sets[page].add(number)
        verified=[]
        bad=[]
        unavailable=[]
        identities=[tuple(row.get("objgen") or ()) for row in content_rows]
        aliased=len(set(identities))!=len(identities)
        for row in content_rows:
            tick()
            known=set()
            unknown=aliased or bool(row["unavailable"]) or not row["references"]
            for reference in ([] if row.get("role_unavailable") else row["references"]):
                tick()
                pages=reference["pages"]
                choices=set()
                reference_known=bool(pages)
                for page in pages:
                    tick()
                    if not isinstance(page,int) or isinstance(page,bool) or not 1<=page<=len(pdf.pages):
                        reference_known=False
                        continue
                    candidates=page_sets.get(page,set())
                    tick(len(candidates))
                    direct=by_node.get(_services.pdf_object_identity(_services.dict_get(pdf.pages[page-1].obj,"/DPart",None)))
                    if len(candidates)!=1 or direct not in candidates:
                        reference_known=False
                    choices.update(candidates)
                if reference_known and len(choices)==1:
                    known.update(choices)
                else:
                    unknown=True
            item={"tag_objgen":row["objgen"],"content_sets":sorted(known)}
            if len(known)>1:
                bad.append(item)
            elif not unknown and len(known)==1:
                verified.append(item)
            if unknown:
                unavailable.append(item)
        details.update(verified_articles=verified[:25],cross_content_set_articles=bad[:25],
                       unavailable_articles=unavailable[:25])
        if bad:
            report.fail(code,"Art content crosses distinct DPart Content Sets",details)
        elif unavailable:
            report.warn(code,"Art Content Set correspondence remains unavailable",dict(details,assessment="unavailable"))
        else:
            report.ok(code,"Each observed Art has content connected to one DPart Content Set",details)
        if unavailable:
            report.warn("VALIDATION_INCOMPLETE","Some Art Content Set counterparts remain unavailable",
                        {"rules":[code],"assessment":"unavailable"})
    except (ValueError,TypeError,_services.pikepdf.PdfError) as error:
        report.warn(code,"Art Content Set correspondence remains unavailable",dict(details,assessment="unavailable",reason=str(error)))
        report.warn("VALIDATION_INCOMPLETE","Art Content Set counterparts could not be established",
                    {"rules":[code],"assessment":"unavailable"})

def check_dpart_object_structure(_services, dpart_root,root_node,nodes,report):
    errors=[]
    if not isinstance(dpart_root,_services.pikepdf.Dictionary):
        errors.append({"object":"DPartRoot","reason":"DPartRoot must be a PDF dictionary, not a stream or other object"})
    unavailable=[]
    dpm_unavailable=[]
    dpm_state={"seen":{},"items":0,"limited":False,"unavailable_values":False}
    root_type=_services.dict_get(dpart_root,"/Type",None)
    if root_type is not None and (not isinstance(root_type,_services.pikepdf.Name) or root_type!=_services.pikepdf.Name('/DPartRoot')):
        errors.append({"object":"DPartRoot","key":"Type","actual":_services.observed_pdf_value(root_type),"reason":"If supplied, Type must be the PDF name DPartRoot"})
    record_level=_services.dict_get(dpart_root,"/RecordLevel",None)
    if record_level is not None and (not isinstance(record_level,int) or isinstance(record_level,bool)):
        errors.append({"object":"DPartRoot","key":"RecordLevel","actual":_services.observed_pdf_value(record_level),"reason":"If supplied, RecordLevel must be a PDF integer"})
    depths={}
    for item in nodes:
        depths[item["objgen"]]=0 if item["parent"] is None else depths.get(_services.objgen(item["parent"]),-1)+1
    level_count=max(depths.values(),default=-1)+1
    if isinstance(record_level,int) and not isinstance(record_level,bool) and nodes and not 0<=record_level<level_count:
        errors.append({"object":"DPartRoot","key":"RecordLevel","actual":record_level,"levels":level_count,
                       "reason":"RecordLevel must identify an actual zero-based hierarchy level"})
    labels=_services.dict_get(dpart_root,"/NodeNameList",None)
    if labels is not None:
        if not _services.is_pdf_array(labels):
            errors.append({"object":"DPartRoot","key":"NodeNameList","reason":"If supplied, NodeNameList must be an array of PDF names"})
        elif len(labels)!=level_count:
            errors.append({"object":"DPartRoot","key":"NodeNameList","actual_entries":len(labels),"expected_levels":level_count,"reason":"NodeNameList must have one entry for each actual hierarchy level"})
        else:
            for index,label in enumerate(labels):
                state,reason=_services.dpart_level_name_assessment(label)
                detail={"key":"NodeNameList","index":index,"actual":_services.observed_pdf_value(label),"reason":reason}
                if state=="invalid":errors.append(detail)
                elif state=="unavailable":unavailable.append(detail)
    if not getattr(dpart_root,"is_indirect",False) or not getattr(root_node,"is_indirect",False):
        errors.append("DPartRoot and DPartRootNode must be indirect objects")
    for item in nodes:
        node=item["node"]
        if not isinstance(node,_services.pikepdf.Dictionary):
            errors.append({"objgen":item["objgen"],"reason":"DPart nodes must be PDF dictionaries, not streams or other objects"})
        node_type=_services.dict_get(node,"/Type",None)
        if node_type is not None and (not isinstance(node_type,_services.pikepdf.Name) or node_type!=_services.pikepdf.Name('/DPart')):
            errors.append({"objgen":item["objgen"],"key":"Type","actual":_services.observed_pdf_value(node_type),"reason":"If supplied, Type must be the PDF name DPart"})
        expected_parent=item["parent"] if item["parent"] is not None else dpart_root
        actual_parent=_services.dict_get(node,"/Parent",None)
        if actual_parent is None or _services.objgen(actual_parent)!=_services.objgen(expected_parent):
            errors.append({"objgen":item["objgen"],"reason":"Missing or incorrect DPart Parent"})
        if not getattr(node,"is_indirect",False):
            errors.append({"objgen":item["objgen"],"reason":"DPart must be indirect"})
        children=_services.dict_get(node,"/DParts",None)
        if children is not None:
            if not _services.is_pdf_array(children) or not len(children):
                errors.append({"objgen":item["objgen"],"reason":"DParts must be a nonempty array of arrays"})
            elif any(not _services.is_pdf_array(group) or not len(group)
                     or any(not isinstance(child,_services.pikepdf.Dictionary) or not child.is_indirect for child in group)
                     for group in children):
                errors.append({"objgen":item["objgen"],"reason":"DParts groups must contain indirect DPart dictionary references"})
            if item["start"] is not None or item["end"] is not None:
                errors.append({"objgen":item["objgen"],"reason":"Non-leaf DParts cannot specify Start or End"})
        elif item["start"] is None:
            errors.append({"objgen":item["objgen"],"reason":"Leaf DPart requires Start"})
        elif item["end"] is not None and _services.objgen(item["start"])==_services.objgen(item["end"]):
            errors.append({"objgen":item["objgen"],"reason":"Single-page DPart must omit End"})
        if _services.dict_get(node,"/Metadata",None) is not None:
            errors.append({"objgen":item["objgen"],"key":"Metadata",
                           "reason":"Published Table409 correction prohibits XMP metadata streams in DPart dictionaries"})
        dpm=item["dpm"]
        raw_dpm=_services.dict_get(node,"/DPM",None)
        if isinstance(raw_dpm,_services.pikepdf.Dictionary) and not dpm_state["limited"]:
            value_errors,name_errors,value_unavailable=_services.inspect_dpm_value_graph(raw_dpm,dpm_state)
            errors.extend(dict(problem,objgen=item["objgen"]) for problem in value_errors)
            dpm_unavailable.extend(dict(problem,objgen=item["objgen"]) for problem in value_unavailable)
            for problem in name_errors:
                report.fail("DPM_NAME_TYPES","DPM name value must contain valid decoded UTF-8",dict(problem,objgen=item["objgen"]))
        if raw_dpm is not None and not isinstance(raw_dpm,_services.pikepdf.Dictionary):
            errors.append({"objgen":item["objgen"],"reason":"DPM must be a dictionary"})
        for key in ("Mail_ContentSetType","Mail_Subtype"):
            if key not in dpm:
                continue
            valid=isinstance(dpm[key],_services.pikepdf.Name)
            if valid:
                try:
                    bytes(dpm[key])[1:].decode("utf-8")
                except UnicodeError:
                    valid=False
            if not valid:
                report.fail("DPM_NAME_TYPES","DPM content set type and subtype must be PDF names with valid decoded UTF-8",{"objgen":item["objgen"],"key":key,"value":_services.observed_pdf_value(dpm[key])})
    if "DPM_NAME_TYPES" not in report.seen_codes:
        if dpm_state["unavailable_values"]:
            report.warn("DPM_NAME_TYPES","Some DPM name values could not be inspected",{"state":"unverified","assessment":"unavailable"})
        else:
            report.ok("DPM_NAME_TYPES","Inspected DPM names contain valid UTF-8 and required type/subtype fields use names",{"nodes":len(nodes)})
    if errors:
        report.fail("DPART_OBJECT_STRUCTURE","DPart object structure does not satisfy ISO 32000-2",{"errors":errors,"unavailable_name_checks":unavailable,"unavailable_dpm_checks":dpm_unavailable})
    elif unavailable or dpm_unavailable:
        report.warn("DPART_OBJECT_STRUCTURE","DPart hierarchy/metadata conformance could not be fully assessed",{"state":"unverified","assessment":"unavailable","names":unavailable,"dpm":dpm_unavailable})
    else:
        report.ok("DPART_OBJECT_STRUCTURE","DPart parents, indirect references, page ranges and supplied optional entry types/counts are valid",{"DPartRoot":_services.objgen(dpart_root),"DPartRootNode":_services.objgen(root_node),"nodes":[{"objgen":item["objgen"],"parent":_services.objgen(_services.dict_get(item["node"],"/Parent",None)),"start":_services.objgen(item["start"]),"end":_services.objgen(item["end"]),"child_count":item["child_count"]} for item in nodes]})
    if unavailable or dpm_unavailable:
        report.warn("VALIDATION_INCOMPLETE","DPart hierarchy or DPM conformance remains unavailable",{"rules":["DPART_OBJECT_STRUCTURE"],"assessment":"unavailable"})
