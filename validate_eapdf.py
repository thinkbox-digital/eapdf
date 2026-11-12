#!/usr/bin/env python3

import sys, json, hashlib, argparse, re, os, subprocess, shutil
import uuid
from pdf_stream_limits import DecodeLimitError, bounded_validation, count_file, read_stream, setting as stream_setting
import tempfile
import base64
import quopri
from copy import deepcopy
from email.header import decode_header, make_header
from email.errors import (HeaderDefect, HeaderParseError, InvalidHeaderDefect, FirstHeaderLineIsContinuationDefect,
                          MisplacedEnvelopeHeaderDefect, MissingHeaderBodySeparatorDefect)
from datetime import datetime, timedelta, timezone
from calendar import monthrange
from decimal import Decimal, InvalidOperation
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor
from io import BytesIO
from collections import defaultdict
from itertools import chain
from functools import lru_cache
from xml.etree import ElementTree as ET
from pathlib import Path
from html import escape as html_escape
from eapdf_validation import (
    reporting as _reporting,
    engines as _engines,
    report_model as _report_model,
    pdf_rules as _pdf_rules,
    media_rules as _media_rules,
    message_rules as _message_rules,
    structure_rules as _structure_rules,
    embedding_rules as _embedding_rules,
    xmp_rules as _xmp_rules,
)
from rule_documentation import specification_context_html
from email import policy
from email.parser import BytesParser
from email.message import Message as MimeHeaders
from email.utils import parsedate_to_datetime, getaddresses, collapse_rfc2231_value
from urllib.parse import urlsplit, urlunsplit, parse_qs, unquote
import pikepdf
from pypdf import PdfReader, PageObject
from pypdf.generic import ContentStream, DecodedStreamObject, DictionaryObject, IndirectObject, NameObject

NS_RDF="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
NS_DC="http://purl.org/dc/elements/1.1/"
NS_PDF="http://ns.adobe.com/pdf/1.3/"
NS_XMP="http://ns.adobe.com/xap/1.0/"
NS_PDFAID="http://www.aiim.org/pdfa/ns/id/"
NS_FOAF="http://xmlns.com/foaf/0.1/"
NS_PDFMAIL="http://www.pdfa.org/eapdf/"
NS_PDFMAILID="http://www.pdfa.org/eapdf/ns/id/"
NS_PDFMAILMETA="http://www.pdfa.org/eapdf/ns/meta/"
NS_PDFD="http://pdfa.org/declarations/"
ISO32005_IDENTIFIER="https://pdfa.org/declarations#iso32005"

ALLOWED_EAPDF_CONFORMANCE={"s","si","m","mi","c","ci"}
VALIDATION_SCOPE="Automated checks, not independent EA-PDF certification"
ALLOWED_PDFA={(3,"A"),(3,"U"),(4,None),(4,"F"),(4,"E")}
GENERIC_BAD_MIME={"application/octet-stream","application/zip","text/plain","application/octetstream"}
MAIL_STRUCTURE_TYPES={"Mail_Message","Mail_ContentGroup","Mail_ContentSet",
                      "Mail_Field","Mail_FieldName","Mail_FieldValue"}
VERAPDF_DOCKER_IMAGE=os.environ.get("VERAPDF_DOCKER_IMAGE","ghcr.io/verapdf/cli:v1.31.18")
VERAPDF_TIMEOUT=int(os.environ.get("VERAPDF_TIMEOUT","0"))
VERAPDF_CONTAINER_NAME=os.environ.get("VERAPDF_CONTAINER_NAME","verapdf-daemon")
VERAPDF_MOUNT_ROOT=Path(os.environ.get("VERAPDF_MOUNT_ROOT", str(Path(__file__).resolve().parent))).resolve()
ALLOWED_VERAPDF_PROFILES={
    "PDF/A-3A validation profile",
    "PDF/A-3U validation profile",
    "PDF/A-4 validation profile",
    "PDF/A-4F validation profile",
    "PDF/A-4E validation profile",
}
ROOT=Path(__file__).resolve().parent
RULE_TEXT_PATH=ROOT/"validator_rule_text.json"
MAX_TREE_DEPTH=256
MAX_TREE_ITEMS=100000

def load_rule_text():
    try:
        global RULEBOOK_SHA256
        raw=RULE_TEXT_PATH.read_bytes()
        rules=json.loads(raw.decode("utf-8"))
        if not isinstance(rules,dict) or not rules:
            raise ValueError("Rule registry is empty or is not an object")
        if [r["rule_number"] for r in rules.values()] != list(range(1,len(rules)+1)):
            raise ValueError("Rule numbers must follow registry order")
        if any(r["validation_scope"]["decision"]=="delegate_to_verapdf" for r in rules.values()):
            raise ValueError("Upstream-only checks do not belong in the EA-PDF registry")
        RULEBOOK_SHA256=hashlib.sha256(raw).hexdigest()
        return rules
    except (OSError,ValueError,KeyError,TypeError) as error:
        raise RuntimeError("Cannot load the authoritative EA-PDF rule registry: "+str(error)) from error

RULE_TEXT=load_rule_text()

# Routing guards only: these underlying PDF/A checks are not EA-PDF rule rows.
# Keep this independent of the EA-PDF registry so removing a delegated entry
# never re-enables a duplicate local implementation. Mixed rules remain local.
PDFA_COVERED_RULES={
    "OUTPUT_INTENT", "FONTS_EMBEDDED", "FONTS_MISSING", "FONTS_ALL_EMBEDDED",
    "EMBEDDED_SUBTYPE_PRESENT", "EMBEDDED_SUBTYPE_NO_PARAMETERS", "XMP_PRESENT",
    "XMP_MISSING", "XMP_PARSE", "PDFA3_EAPDF_XMP_EXTENSION_SCHEMA", "XMP_UTF8",
}
ISO32005_PROFILE=json.loads((ROOT/"config/verapdf/ISO-32005.json").read_text(encoding="utf-8"))
SPEC_REFERENCES=[
    {
        "codes":{"XMP_MISSING","XMP_PARSE","XMP_PRESENT","XMP_NS_PREFIX","XMP_CREATOR","XMP_CREATOR_SEMANTICS","XMP_PRODUCER","XMP_PRODUCER_SEMANTICS","XMP_CREATEDATE","XMP_CREATEDATE_SEMANTICS","XMP_TITLE","PDFA_XMP_UNCHECKED","EAPDF_XMP_UNCHECKED"},
        "source":"Validation requirements.docx sections 1, 5, and 9",
        "spec":"EA-PDF 1.0 section 9.8",
        "excerpt":"Every EA-PDF file must include the custom XMP namespaces pdfmail, pdfmailid, and pdfmailmeta. Creation software must record dc:creator[0], pdf:Producer, and xmp:CreateDate. pdfmailid:version must be 1, pdfmailid:rev must be 2024, and pdfmailid:conformance must be one of s, si, m, mi, c, ci. Intermediate-node DPM / document-level XMP linkage uses Mail_GUID and optional messageid."
    },
    {
        "codes":{"EAPDF_VERSION","EAPDF_REV","EAPDF_CONFORMANCE_VALUE","EAPDF_CONFORMANCE_I_LAST"},
        "source":"Validation requirements.docx section 1",
        "spec":"EA-PDF 1.0 section 9.8.1",
        "excerpt":"The EA-PDF Identification schema SHALL use the namespace URI http://www.pdfa.org/eapdf/ns/id/ and SHALL use the schema namespace prefix pdfmailid. pdfmailid:version shall be 1, pdfmailid:rev shall be 2024, and pdfmailid:conformance shall be one of s, si, m, mi, c, ci."
    },
    {
        "codes":{"MESSAGE_ID_XMP","MAIL_GUID_XMP"},
        "source":"Validation requirements.docx sections 5 and 9",
        "spec":"EA-PDF 1.0 sections 9.6 and 9.8.3",
        "excerpt":"Every Mail_MessageID and Mail_GUID value SHALL match a corresponding property in the Document Catalog XMP metadata stream. Mail_GUID is also a required Email Headers Core Field, while Message-ID is optional."
    },
    {
        "codes":{"PDFA_PART","PDFA_CONFORMANCE","PDF_EFFECTIVE_VERSION","PDF_HEADER_VERSION_READ","PDF_CATALOG_VERSION"},
        "source":"Validation requirements.docx section 2",
        "spec":"EA-PDF 1.0 section 9.1",
        "excerpt":"The PDF header SHALL be either “%PDF-1.7” or “%PDF-2.0” and the declared PDF version number of an EA-PDF file SHALL be either PDF 1.7 or PDF 2.0. All EA-PDF files SHALL conform to either PDF/A-3a, PDF/A-3u, PDF/A-4, PDF/A-4f, or PDF/A-4e."
    },
    {
        "codes":{"OUTPUT_INTENT","FONTS_NONE","FONTS_MISSING","FONTS_EMBEDDED","FONTS_ALL_EMBEDDED"},
        "source":"Validation requirements.docx section 2",
        "spec":"EA-PDF 1.0 section 9.1 and underlying PDF/A rules",
        "excerpt":"All EA-PDF files SHALL conform to either PDF/A-3a, PDF/A-3u, PDF/A-4, PDF/A-4f, or PDF/A-4e. PDF/A conformance brings the underlying OutputIntent and embedded-font requirements."
    },
    {
        "codes":{"DOCINFO_PRESENT","DOCINFO_KEYWORDS","XMP_KEYWORDS_UNCHECKED","XMP_KEYWORDS","CATALOG_PIECEINFO","DISPLAY_DOCTITLE","PAGEMODE_PRESENT","NONFULLSCREENPAGEMODE_PRESENT","PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS","PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS","PROFILE_PAGEMODE_MULTI","PROFILE_PAGEMODE_CONTAINER","PROFILE_NFSM_SINGLE_NO_ATTACHMENTS","PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS","PROFILE_NFSM_MULTI","PROFILE_NFSM_CONTAINER","VIEWER_PREFS"},
        "source":"Validation requirements.docx sections 1 and 3",
        "spec":"EA-PDF 1.0 section 9.1",
        "excerpt":"All EA-PDF files SHALL NOT contain a PieceInfo entry in the document catalog dictionary. The XMP pdf:Keywords property and, for all PDF-A/3-based EA-PDF files, the PDF document information dictionary Keywords entry SHALL each include “EA-PDF”. For PDF/mail-1{s, si} with no email attachments, PageMode and NonFullScreenPageMode SHALL be UseOutlines; with email attachments, PageMode SHALL be UseAttachments and NonFullScreenPageMode SHALL be UseAttachments for PDF 2.0 or UseOutlines for PDF 1.7. All PDF/mail-1 files SHALL set the Viewer Preferences DisplayDocTitle entry to true."
    },
    {
        "codes":{"DOCINFO_TITLE_MATCH","DOCINFO_PRODUCER_MATCH","DOCINFO_AUTHOR_MATCH","DOCINFO_SUBJECT_MATCH","DOCINFO_CREATOR_MATCH","DOCINFO_CREATIONDATE_MATCH","DOCINFO_MODDATE_MATCH"},
        "source":"EA-PDF-v1.pdf page 61",
        "spec":"EA-PDF 1.0 section 9.1",
        "excerpt":"All EA-PDF files that also conform to PDF/A-3 SHALL have a Document Information dictionary referenced from the trailer Info entry. The information in this document information dictionary SHALL be consistent with the document catalog XMP Metadata entry as defined by Table 7 Crosswalk between document information dictionary and XMP properties in PDF/A-3."
    },
    {
        "codes":{"PAGE_COUNT","CONTENT_SET_COUNT","CONTENT_SET_NONBLANK","CONTENT_SET_OUTLINE_REF","PROFILE_EMAIL_COUNT_SINGLE","PROFILE_EMAIL_COUNT_MULTI"},
        "source":"Validation requirements.docx section 3",
        "spec":"EA-PDF 1.0 section 9.2",
        "excerpt":"All PDF/mail-1 files SHALL contain at least one Content Set. The first page in each Content Set SHALL be referenced from at least one Outline item. For validation, blank-page and per-profile Content Set requirements are checked against the Content Sets defined by the file."
    },
    {
        "codes":{"CORE_FIELDS_TEXT_OBJECTS","TEXT_CONTENT_PDF_TEXT_OBJECTS","PAGE_CONTEXT_PRESENT_RECOMMENDED","PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED"},
        "source":"EA-PDF-v1.pdf page 64",
        "spec":"EA-PDF 1.0 section 9.3",
        "excerpt":"All Core Fields and their values (when present) SHALL use text objects. More generally, email content that is text should use PDF text objects, and page-identifying context should be present and should use PDF text objects."
    },
    {
        "codes":{"PROFILE_COLLECTION"},
        "source":"Validation requirements.docx section 4",
        "spec":"EA-PDF 1.0 section 9.5",
        "excerpt":"For PDF/mail-1{c,ci}, Collection-specific constraints apply: if PDF version is 1.7 there must be an Extensions dictionary with BaseVersion /1.7 and ExtensionLevel 5; Collection.D must not be present; Collection.View must not be H; and Collection schema custom fields must exist with the required names and subtypes."
    },
    {
        "codes":{"AF_RULE","SOURCE_IN_ISOLATED","SOURCE_AF"},
        "source":"Validation requirements.docx sections 3 and 8",
        "spec":"EA-PDF 1.0 sections 9.1 and 9.4",
        "excerpt":"For non-isolated PDF/mail-1{s, m, c}, the Document Catalog dictionary SHALL have an AF entry with at least one file specification dictionary whose AFRelationship is Source. Additional source-email file-set members may be represented using RF or Supplement. All embedded source email assets need to be listed in the Document Catalog Names name-tree EmbeddedFiles entry."
    },
    {
        "codes":{"EMBEDDEDFILES_PRESENT","AF_IN_NAME_TREE","EMBEDDED_FILESPEC_NO_PATH_COMPONENTS","EMBEDDED_SUBTYPE_PRESENT","EMBEDDED_SUBTYPE_NO_PARAMETERS","EMBEDDED_MEDIA_TYPE_MATCH_XMP","SOURCE_MEDIATYPE_PARAMETERS_MATCH","EMBEDDED_STREAM","EMBEDDED_SIZE_MATCH","EMBEDDED_MD5_MATCH"},
        "source":"Validation requirements.docx sections 3 and 8",
        "spec":"EA-PDF 1.0 section 9.4",
        "excerpt":"Embedded file streams included as Associated Files (referenced from an AF array or AF marked-content tag) SHALL be listed in the Document Catalog Names name-tree EmbeddedFiles entry. File specification strings shall not include path components. Embedded file streams shall carry a bare IANA media type in /Subtype, that subtype shall match equivalent document-level XMP metadata where available, and source-email media-type parameters shall be mirrored in Mail_MediaTypeParameters."
    },
    {
        "codes":{"ASSET_XMP","ASSET_XMP_FIELDS","ASSET_SIZE_TYPE","ASSET_NUMBERMESSAGES_TYPE","ASSET_CHECKSUM_FORM","ASSET_CONTENTTYPE_GENERIC","ASSET_CONTENTTYPE","ASSET_MATCH","ASSET_MD5_MATCH","ASSET_SIZE_MATCH","ASSET_CONTENTTYPE_MATCH","SOURCE_WITHOUT_XMP","SOURCE_MIME_NONE","SOURCE_MIME_GENERIC","SOURCE_MIME_TYPE","ASSET_XMP_SKIPPED"},
        "source":"Validation requirements.docx sections 8 and 9",
        "spec":"EA-PDF 1.0 section 9.8.2",
        "excerpt":"Each source-email asset description must include Filename, SizeInBytes, ContentType, and Checksum, where Checksum is an MD5 as 32 hexadecimal ASCII characters. Common source email content types include message/rfc822 and application/vnd.ms-outlook. Generic media types such as application/octet-stream, application/zip, and text/plain are discouraged."
    },
    {
        "codes":{"ATTACHMENT_XMP_CONTAINER","ATTACHMENT_XMP_FILENAME","ATTACHMENT_XMP_SIZE_PRESENT","ATTACHMENT_XMP_SIZE_TYPE","ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","ATTACHMENT_XMP_CONTENTTYPE","ATTACHMENT_XMP_CHECKSUM_FORM","ATTACHMENT_XMP_SIZE_MATCH","ATTACHMENT_XMP_CONTENTTYPE_MATCH"},
        "source":"Validation requirements.docx section 10 and EA-PDF-v1.pdf pages 89-90",
        "spec":"EA-PDF 1.0 section 9.8.4",
        "excerpt":"If pdfmailmeta:attachments exists, each attachment description must carry the Table 19 fields atomically: Filename, SizeInBytes, and Content-Type are required, Mail_GUID is required when Message-ID is absent, and CheckSum is optional but must be valid when present."
    },
    {
        "codes":{"MESSAGE_METADATA_SKIPPED","MESSAGE_SENT_XMP","MESSAGE_SENT_FORMAT","MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","MESSAGE_RAW_SENT_OMIT_IF_VALID","MESSAGE_TO_XMP","MESSAGE_FROM_XMP","MESSAGE_CC_XMP","MESSAGE_BCC_XMP","MESSAGE_TO_FOAF","MESSAGE_FROM_FOAF","MESSAGE_CC_FOAF","MESSAGE_BCC_FOAF","MESSAGE_RAW_TO_XMP","MESSAGE_RAW_FROM_XMP","MESSAGE_RAW_CC_XMP","MESSAGE_RAW_BCC_XMP","MESSAGE_SUBJECT_XMP","MESSAGE_INREPLYTO_XMP","MESSAGE_SIZE_XMP","MESSAGE_ATTACHMENT_COUNT_XMP"},
        "source":"Validation requirements.docx section 9",
        "spec":"EA-PDF 1.0 section 9.8.3",
        "excerpt":"For PDF/mail-1{s,si,m,mi}, all non-empty Core Fields for each email should be recorded as structured document-level XMP metadata. The summary notes these cannot always be strict SHALL checks without reprocessing the original source email, but they can be validated for consistency with the PDF's internal structures."
    },
    {
        "codes":{"OUTLINES_PRESENT","OUTLINE_ITEMS_PRESENT","DPARTROOT_PRESENT"},
        "source":"Validation requirements.docx sections 3 and 5",
        "spec":"EA-PDF 1.0 section 9.1",
        "excerpt":"All PDF/mail-1 files SHALL have an Outlines and DPartRoot entries in the Document Catalog."
    },
    {
        "codes":{"PAGE_DPART_PRESENT","DPM_LEAF_REQUIRED"},
        "source":"Validation requirements.docx sections 3 and 5",
        "spec":"EA-PDF 1.0 section 9.6",
        "excerpt":"All EA-PDF files SHALL define document part metadata for all pages via the Document Catalog DPartRoot dictionary entry. Consequently, every page in all EA-PDF files SHALL have a DPart entry. Leaf DPart nodes define Content Sets using Start and End page references."
    },
    {
        "codes":{"DPM_KEY_PREFIX","DPM_MAIL_GUID_PRESENT","DPM_MAIL_GUID_MATCH","DPM_MAIL_MESSAGEID_MATCH","DPM_CONTENTSETTYPE_PRESENT","DPM_SUBTYPE_REQUIRED","DPM_DESC_REQUIRED","DPM_ATTACHMENTLIST_FORBIDDEN","DPM_INTERMEDIATE_COUNT_SINGLE","DPM_INTERMEDIATE_COUNT_MULTI","DPM_INTERMEDIATE_COUNT_CONTAINER","DPM_ROOTNODE_PRESENT"},
        "source":"Validation requirements.docx section 5",
        "spec":"EA-PDF 1.0 section 9.6",
        "excerpt":"All custom EA-PDF keys used in DPM shall use the registered prefix Mail_. Intermediate nodes shall use Mail_GUID and optionally Mail_MessageID. Leaf nodes shall contain Mail_ContentSetType and Mail_Desc, and Mail_Subtype is required for BodyRendering or AttachmentRendering. AttachmentList shall not occur in PDF/mail-1{c,ci} files."
    },
    {
        "codes":{"COLLECTION_REQUIRED","COLLECTION_FORBIDDEN","COLLECTION_EXTENSIONS","COLLECTION_D_FORBIDDEN","COLLECTION_VIEW_FORBIDDEN","COLLECTION_NAVIGATOR_FORBIDDEN","COLLECTION_SCHEMA_PRESENT","COLLECTION_SCHEMA_FIELD","COLLECTION_SCHEMA_E_FALSE"},
        "source":"Validation requirements.docx section 4",
        "spec":"EA-PDF 1.0 section 9.5",
        "excerpt":"PDF/mail-1{c,ci} files shall have a Collection entry and non-container profiles shall not. PDF 1.7 containers shall have Adobe Extension Level 5 metadata. Collection.D shall not be present, Collection.View shall not be H, Navigator shall not contain SWF or APIVersion, and required Collection Schema custom fields shall exist with the specified subtypes. If E is present in any CustomField dictionary it shall be false."
    },
    {
        "codes":{"STRUCTTREE_PRESENT","STRUCTTREE_ROOT_DOCUMENT","ROLEMAP_MAIL_TAGS","MAIL_MESSAGE_COUNT_SINGLE","MAIL_MESSAGE_COUNT_MULTI","MAIL_MESSAGE_COUNT_CONTAINER","MAIL_CONTENTSET_COUNT","MAIL_MESSAGE_CHILDREN","MAIL_CONTENTGROUP_CHILDREN","MAIL_FIELD_CHILDREN","MAIL_FIELDVALUE_WITH_NAME","MAIL_FIELD_PROFILE_USE","MAIL_FIELDNAME_PROFILE_USE","MAIL_FIELDVALUE_PROFILE_USE","MAIL_MESSAGE_GUID_MATCH_DPM_XMP","MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT","MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT","ROLEMAPNS_PRESENT"},
        "source":"Validation requirements.docx section 6",
        "spec":"EA-PDF 1.0 section 9.7",
        "excerpt":"For files that contain logical structure, the structure tree root K shall resolve to a single Document structure element. If custom EA-PDF tags are used, required role mappings shall be present and parent-child occurrence rules shall be satisfied. Mail_Message cardinality depends on profile, and Mail_ContentSet shall occur at least once in all PDF/mail files."
    },
    {
        "codes":{"USERPROPS_NAME_ASCII","USERPROPS_TYPE","USERPROPS_TAG_SCOPE"},
        "source":"Validation requirements.docx section 7",
        "spec":"EA-PDF 1.0 section 9.7 / Table 12",
        "excerpt":"If a structure element uses UserProperties, the N entry shall be a PDF ASCII string and the V entry type shall match the allowed type for that property. Certain properties are constrained to specific custom EA-PDF tags."
    },
    {
        "codes":{"OPEN","PROFILE_RULES_SKIPPED"},
        "source":"Validator operational diagnostics",
        "spec":"Implementation support checks",
        "excerpt":"These findings document whether the validator was able to open the file and inspect the PDF and XMP structures needed to run the normative EA-PDF checks."
    }
]

RULE_EXCERPT_OVERRIDES = {}

def _set_rule_excerpt(codes, excerpt):
    for code in codes:
        RULE_EXCERPT_OVERRIDES[code] = excerpt

_set_rule_excerpt(
    {"XMP_NS_PREFIX"},
    "**Every EA-PDF file must include the custom XMP namespaces pdfmail, pdfmailid, and pdfmailmeta.**"
)
_set_rule_excerpt(
    {"XMP_CREATOR"},
    "**Creation software must record dc:creator[0]**, pdf:Producer, and xmp:CreateDate."
)
_set_rule_excerpt(
    {"XMP_CREATOR_SEMANTICS"},
    "**EA-PDF Creation Software SHALL also record the dc:creator[0]** ... in the standard XMP metadata fields."
)
_set_rule_excerpt(
    {"XMP_PRODUCER"},
    "Creation software must record dc:creator[0], **pdf:Producer**, and xmp:CreateDate."
)
_set_rule_excerpt(
    {"XMP_PRODUCER_SEMANTICS"},
    "EA-PDF Creation Software SHALL also record the dc:creator[0], **pdf:Producer** and the date of the EA-PDF file creation date (xmp:CreateDate) in the standard XMP metadata fields."
)
_set_rule_excerpt(
    {"XMP_CREATEDATE"},
    "Creation software must record dc:creator[0], pdf:Producer, and **xmp:CreateDate**."
)
_set_rule_excerpt(
    {"XMP_CREATEDATE_SEMANTICS"},
    "EA-PDF Creation Software SHALL also record the dc:creator[0], pdf:Producer and **the date of the EA-PDF file creation date (xmp:CreateDate)** in the standard XMP metadata fields."
)
_set_rule_excerpt(
    {"XMP_TITLE"},
    "**The dc:title[“x-default”] property SHOULD also be set.**"
)
_set_rule_excerpt(
    {"EAPDF_VERSION"},
    "**pdfmailid:version must be 1.**"
)
_set_rule_excerpt(
    {"EAPDF_REV"},
    "**pdfmailid:rev must be 2024.**"
)
_set_rule_excerpt(
    {"EAPDF_CONFORMANCE_VALUE"},
    "**pdfmailid:conformance must be one of s, si, m, mi, c, ci.**"
)
_set_rule_excerpt(
    {"EAPDF_CONFORMANCE_I_LAST"},
    "**The i designator in pdfmailid:conformance must be last if present.**"
)
_set_rule_excerpt(
    {"MESSAGE_ID_XMP","MAIL_GUID_XMP"},
    "**Intermediate-node DPM / document-level XMP linkage uses Mail_GUID and optional messageid.**"
)
_set_rule_excerpt(
    {"PAGEMODE_PRESENT","PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS","PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS","PROFILE_PAGEMODE_MULTI","PROFILE_PAGEMODE_CONTAINER"},
    "**For PDF/mail-1{s,si}, PageMode is UseOutlines or, if the email has attachments, UseAttachments.** DisplayDocTitle shall be true."
)
_set_rule_excerpt(
    {"NONFULLSCREENPAGEMODE_PRESENT","PROFILE_NFSM_SINGLE_NO_ATTACHMENTS","PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS","PROFILE_NFSM_MULTI","PROFILE_NFSM_CONTAINER"},
    "For PDF/mail-1{s,si} with no email attachments, the Viewer Preferences NonFullScreenPageMode entry SHALL be UseOutlines. **For PDF/mail-1{s,si} with email attachments, NonFullScreenPageMode SHALL be UseAttachments for PDF 2.0 or UseOutlines for PDF 1.7.**"
)
_set_rule_excerpt(
    {"DISPLAY_DOCTITLE"},
    "PageMode and NonFullScreenPageMode depend on profile; for PDF/mail-1{s,si}, PageMode is UseOutlines or, if the email has attachments, UseAttachments. **DisplayDocTitle shall be true.**"
)
_set_rule_excerpt(
    {"VIEWER_PREFS"},
    "**All PDF/mail-1 files SHALL set the Viewer Preferences DisplayDocTitle entry to true.**"
)
_set_rule_excerpt(
    {"DOCINFO_KEYWORDS"},
    "**For all PDF-A/3-based EA-PDF files, the PDF document information dictionary Keywords entry SHALL include “EA-PDF”.**"
)
_set_rule_excerpt(
    {"XMP_KEYWORDS"},
    "**The XMP pdf:Keywords property SHALL include “EA-PDF”.**"
)
_set_rule_excerpt(
    {"OUTLINES_PRESENT","OUTLINE_ITEMS_PRESENT","CONTENT_SET_OUTLINE_REF","CONTENT_SET_COUNT","DPM_LEAF_REQUIRED","CONTENT_SET_NONBLANK","PROFILE_EMAIL_COUNT_SINGLE","PROFILE_EMAIL_COUNT_MULTI"},
    "**All PDF/mail-1 files shall have Outlines and DPartRoot entries in the Document Catalog. All PDF/mail-1 files shall contain at least one Content Set** of at least 1 page each that shall not be blank, and the first page in each Content Set shall be referenced from at least one Outline item."
)
_set_rule_excerpt(
    {"DPARTROOT_PRESENT"},
    "**All PDF/mail-1 files SHALL have a DPartRoot entry in the Document Catalog.**"
)
_set_rule_excerpt(
    {"PAGE_DPART_PRESENT"},
    "All EA-PDF files SHALL define document part metadata for all pages via the Document Catalog DPartRoot dictionary entry. **Consequently, all pages in all EA-PDF files will have a DPart entry.**"
)
_set_rule_excerpt(
    {"DPM_KEY_PREFIX"},
    "**All custom EA-PDF keys used in DPM shall use the registered prefix Mail_.**"
)
_set_rule_excerpt(
    {"DPM_ROOTNODE_PRESENT"},
    "**The DPartRoot dictionary SHALL contain a DPartRootNode entry** that roots the document part metadata tree."
)
_set_rule_excerpt(
    {"DPM_MAIL_GUID_PRESENT","DPM_MAIL_GUID_MATCH"},
    "**Intermediate nodes shall use Mail_GUID** and optionally Mail_MessageID."
)
_set_rule_excerpt(
    {"DPM_MAIL_MESSAGEID_MATCH"},
    "Intermediate nodes shall use Mail_GUID and **optionally Mail_MessageID.**"
)
_set_rule_excerpt(
    {"DPM_CONTENTSETTYPE_PRESENT"},
    "**Leaf nodes shall contain Mail_ContentSetType and Mail_Desc**, and Mail_Subtype is required for BodyRendering or AttachmentRendering."
)
_set_rule_excerpt(
    {"DPM_DESC_REQUIRED"},
    "Leaf nodes shall contain **Mail_ContentSetType and Mail_Desc**, and Mail_Subtype is required for BodyRendering or AttachmentRendering."
)
_set_rule_excerpt(
    {"DPM_SUBTYPE_REQUIRED"},
    "Leaf nodes shall contain Mail_ContentSetType and Mail_Desc, and **Mail_Subtype is required for BodyRendering or AttachmentRendering.**"
)
_set_rule_excerpt(
    {"EMBEDDEDFILES_PRESENT","AF_RULE","AF_IN_NAME_TREE","SOURCE_IN_ISOLATED","SOURCE_AF"},
    "**For non-isolated EA-PDF files that include source email assets, document-level XMP must include pdfmailmeta:assets** as an unordered set of one or more asset descriptions. Example structures show the source email referenced as an Associated File and also listed in the EmbeddedFiles name tree."
)
_set_rule_excerpt(
    {"EMBEDDED_STREAM"},
    "**Embedded files referenced by EA-PDF SHALL be listed in the Document Catalog Names name-tree EmbeddedFiles entry.**"
)
_set_rule_excerpt(
    {"EMBEDDED_SIZE_MATCH"},
    "**Embedded file stream metadata such as size** are checked against the embedded-file objects and their parameter dictionaries."
)
_set_rule_excerpt(
    {"EMBEDDED_MD5_MATCH"},
    "**Embedded file stream metadata such as checksum** are checked against the embedded-file objects and their parameter dictionaries."
)
_set_rule_excerpt(
    {"EMBEDDED_STREAM","EMBEDDED_SIZE_MATCH","EMBEDDED_MD5_MATCH","ASSET_XMP","ASSET_XMP_FIELDS","ASSET_SIZE_TYPE","ASSET_NUMBERMESSAGES_TYPE","ASSET_CHECKSUM_FORM","ASSET_CONTENTTYPE","ASSET_MATCH","ASSET_MD5_MATCH","ASSET_SIZE_MATCH","ASSET_CONTENTTYPE_MATCH","SOURCE_WITHOUT_XMP","SOURCE_MIME_TYPE"},
    "**Each source-email asset description must include Filename, SizeInBytes, ContentType, and Checksum**, where Checksum is an MD5 as 32 hexadecimal ASCII characters."
)
_set_rule_excerpt(
    {"ASSET_SIZE_TYPE","ASSET_SIZE_MATCH"},
    "Each source-email asset description must include Filename, **SizeInBytes**, ContentType, and Checksum."
)
_set_rule_excerpt(
    {"ASSET_NUMBERMESSAGES_TYPE"},
    "**NumberMessages** is an optional non-negative integer for source email asset metadata when declared."
)
_set_rule_excerpt(
    {"ASSET_CHECKSUM_FORM","ASSET_MD5_MATCH"},
    "Each source-email asset description must include Filename, SizeInBytes, ContentType, and **Checksum**, where Checksum is an MD5 as 32 hexadecimal ASCII characters."
)
_set_rule_excerpt(
    {"ASSET_CONTENTTYPE","ASSET_CONTENTTYPE_MATCH","SOURCE_MIME_TYPE"},
    "Each source-email asset description must include Filename, SizeInBytes, **ContentType**, and Checksum."
)
_set_rule_excerpt(
    {"ATTACHMENT_XMP_CONTAINER","ATTACHMENT_XMP_FILENAME","ATTACHMENT_XMP_SIZE_PRESENT","ATTACHMENT_XMP_SIZE_TYPE","ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","ATTACHMENT_XMP_CONTENTTYPE","ATTACHMENT_XMP_CHECKSUM_FORM","ATTACHMENT_XMP_SIZE_MATCH","ATTACHMENT_XMP_CONTENTTYPE_MATCH"},
    "**If pdfmailmeta:attachments exists, each attachment description uses Table 19 fields atomically: Filename, SizeInBytes, and Content-Type are required, Mail_GUID is required when Message-ID is absent, and CheckSum is optional.**"
)
_set_rule_excerpt(
    {"ATTACHMENT_XMP_FILENAME"},
    "**Filename** is required for every XMP attachment record."
)
_set_rule_excerpt(
    {"ATTACHMENT_XMP_SIZE_PRESENT","ATTACHMENT_XMP_SIZE_TYPE"},
    "**SizeInBytes** is required for every XMP attachment record and must be a non-negative integer."
)
_set_rule_excerpt(
    {"ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID"},
    "**Mail_GUID** is required when the attachment XMP record does not include **Message-ID**."
)
_set_rule_excerpt(
    {"ATTACHMENT_XMP_CONTENTTYPE","ATTACHMENT_XMP_CONTENTTYPE_MATCH"},
    "**Content-Type** is required for every XMP attachment record."
)
_set_rule_excerpt(
    {"ATTACHMENT_XMP_CHECKSUM_FORM"},
    "**CheckSum** is an optional 32 character ASCII text string representing the MD5 checksum of the attachment."
)
_set_rule_excerpt(
    {"MESSAGE_SENT_XMP","MESSAGE_SENT_FORMAT","MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","MESSAGE_RAW_SENT_OMIT_IF_VALID","MESSAGE_TO_XMP","MESSAGE_FROM_XMP","MESSAGE_CC_XMP","MESSAGE_BCC_XMP","MESSAGE_TO_FOAF","MESSAGE_FROM_FOAF","MESSAGE_CC_FOAF","MESSAGE_BCC_FOAF","MESSAGE_RAW_TO_XMP","MESSAGE_RAW_FROM_XMP","MESSAGE_RAW_CC_XMP","MESSAGE_RAW_BCC_XMP","MESSAGE_SUBJECT_XMP","MESSAGE_INREPLYTO_XMP","MESSAGE_SIZE_XMP","MESSAGE_ATTACHMENT_COUNT_XMP"},
    "**For PDF/mail-1{s,si,m,mi}, all non-empty Core Fields for each email SHALL be recorded as structured document-level XMP metadata.**"
)
_set_rule_excerpt(
    {"MESSAGE_TO_XMP","MESSAGE_TO_FOAF"},
    "**To** is represented as an ordered sequence of FOAF Agent containers, each with required foaf:mbox and optional foaf:name."
)
_set_rule_excerpt(
    {"MESSAGE_RAW_TO_XMP"},
    "**Raw-To** is a free text string, directly reflecting the email header To: field value."
)
_set_rule_excerpt(
    {"MESSAGE_FROM_XMP","MESSAGE_FROM_FOAF"},
    "**From** is represented as an ordered sequence of FOAF Agent containers, each with required foaf:mbox and optional foaf:name."
)
_set_rule_excerpt(
    {"MESSAGE_RAW_FROM_XMP"},
    "**Raw-From** is a free text string, directly reflecting the email header From: field value."
)
_set_rule_excerpt(
    {"MESSAGE_SENT_XMP","MESSAGE_SENT_FORMAT"},
    "**Sent** is required and is represented as a xsd:dateTime."
)
_set_rule_excerpt(
    {"MESSAGE_RAW_SENT_REQUIRED_IF_INVALID"},
    "**Raw-Sent** is required when the email header Sent value has an error that would result in an invalid XMP dateTime."
)
_set_rule_excerpt(
    {"MESSAGE_RAW_SENT_OMIT_IF_VALID"},
    "**Raw-Sent** should not be present when the email header Sent value is already a valid XMP dateTime."
)
_set_rule_excerpt(
    {"MESSAGE_SUBJECT_XMP"},
    "**Subject** is an optional free text string."
)
_set_rule_excerpt(
    {"MESSAGE_ID_XMP"},
    "**Message-ID** is an optional free text string."
)
_set_rule_excerpt(
    {"MAIL_GUID_XMP"},
    "**Mail_GUID** is required and is an XMP GUID that uniquely identifies an email."
)
_set_rule_excerpt(
    {"MESSAGE_SIZE_XMP"},
    "**SizeInBytes** is an optional non-negative integer representing the size of the original source of only this email message."
)
_set_rule_excerpt(
    {"MESSAGE_ATTACHMENT_COUNT_XMP"},
    "**NumberAttachments** is required if 1 or more attachments are present and has default value 0."
)
_set_rule_excerpt(
    {"MESSAGE_CC_XMP","MESSAGE_CC_FOAF"},
    "**Cc** is represented as an ordered sequence of FOAF Agent containers, each with required foaf:mbox and optional foaf:name."
)
_set_rule_excerpt(
    {"MESSAGE_RAW_CC_XMP"},
    "**Raw-Cc** is a free text string, directly reflecting the email header cc: field value."
)
_set_rule_excerpt(
    {"MESSAGE_BCC_XMP","MESSAGE_BCC_FOAF"},
    "**Bcc** is represented as an ordered sequence of FOAF Agent containers, each with required foaf:mbox and optional foaf:name."
)
_set_rule_excerpt(
    {"MESSAGE_RAW_BCC_XMP"},
    "**Raw-Bcc** is a free text string, directly reflecting the email header bcc: field value."
)
_set_rule_excerpt(
    {"MESSAGE_INREPLYTO_XMP"},
    "**In-Reply-To** is an unordered sequence of free text strings, directly reflecting the email header In-Reply-To field value(s)."
)
_set_rule_excerpt(
    {"STRUCTTREE_PRESENT","STRUCTTREE_ROOT_DOCUMENT","ROLEMAP_MAIL_TAGS","MAIL_MESSAGE_COUNT_SINGLE","MAIL_MESSAGE_COUNT_MULTI","MAIL_MESSAGE_COUNT_CONTAINER","MAIL_CONTENTSET_COUNT","MAIL_MESSAGE_CHILDREN","MAIL_CONTENTGROUP_CHILDREN","MAIL_FIELD_CHILDREN","MAIL_FIELDVALUE_WITH_NAME","MAIL_FIELD_PROFILE_USE","MAIL_FIELDNAME_PROFILE_USE","MAIL_FIELDVALUE_PROFILE_USE","MAIL_MESSAGE_GUID_MATCH_DPM_XMP","MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT","MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT","ROLEMAPNS_PRESENT"},
    "**For files that contain logical structure, the structure tree root K shall resolve to a single Document structure element.** If custom EA-PDF tags are used, required role mappings shall be present and parent-child occurrence rules shall be satisfied."
)
_set_rule_excerpt(
    {"STRUCTTREE_PRESENT"},
    "**For all PDF/mail-1 files that contain Logical Structure, a StructTreeRoot SHALL be present.**"
)
_set_rule_excerpt(
    {"ROLEMAPNS_PRESENT"},
    "**In all PDF/A-4 files with logical structure, the custom EA-PDF-defined semantics SHALL be included** in the namespace and role-mapping structures."
)
_set_rule_excerpt(
    {"USERPROPS_NAME_ASCII","USERPROPS_TYPE","USERPROPS_TAG_SCOPE"},
    "**If a structure element uses UserProperties, the N entry shall be a PDF ASCII string and the V entry type shall match the allowed type for that property.**"
)
_set_rule_excerpt(
    {"EMBEDDED_STREAM"},
    "**Embedded file specifications SHALL contain an embedded file stream** for the referenced file."
)
_set_rule_excerpt(
    {"EMBEDDED_SIZE_MATCH"},
    "**The embedded file stream Size metadata** shall match the embedded file bytes."
)
_set_rule_excerpt(
    {"EMBEDDED_MD5_MATCH"},
    "**The embedded file stream CheckSum metadata** shall match the MD5 of the embedded file bytes when present."
)

RULE_DISPLAY_ORDER = [
    # Operational diagnostics
    "OPEN",

    # 9.1 EA-PDF file / PDF version / PDF-A baseline
    "PDF_HEADER_VERSION_READ",
    "PDF_CATALOG_VERSION",
    "PDF_EFFECTIVE_VERSION",
    "PDFA_PART",
    "PDFA_CONFORMANCE",
    "OUTPUT_INTENT",
    "FONTS_NONE",
    "FONTS_EMBEDDED",
    "FONTS_MISSING",
    "FONTS_ALL_EMBEDDED",
    "DOCINFO_PRESENT",
    "DOCINFO_KEYWORDS",
    "DOCINFO_TITLE_MATCH",
    "DOCINFO_PRODUCER_MATCH",
    "DOCINFO_AUTHOR_MATCH",
    "DOCINFO_SUBJECT_MATCH",
    "DOCINFO_CREATOR_MATCH",
    "DOCINFO_CREATIONDATE_MATCH",
    "DOCINFO_MODDATE_MATCH",
    "CATALOG_PIECEINFO",
    "XMP_KEYWORDS",
    "XMP_KEYWORDS_UNCHECKED",
    "PAGEMODE_PRESENT",
    "NONFULLSCREENPAGEMODE_PRESENT",
    "PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS",
    "PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS",
    "PROFILE_PAGEMODE_MULTI",
    "PROFILE_PAGEMODE_CONTAINER",
    "PROFILE_NFSM_SINGLE_NO_ATTACHMENTS",
    "PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS",
    "PROFILE_NFSM_MULTI",
    "PROFILE_NFSM_CONTAINER",
    "VIEWER_PREFS",
    "DISPLAY_DOCTITLE",
    "AF_RULE",
    "SOURCE_AF",
    "SOURCE_IN_ISOLATED",
    "OUTLINES_PRESENT",
    "OUTLINE_ITEMS_PRESENT",
    "DPARTROOT_PRESENT",
    "PAGE_DPART_PRESENT",
    "PROFILE_RULES_SKIPPED",

    # 9.2 Content sets
    "CONTENT_SET_COUNT",
    "CONTENT_SET_NONBLANK",
    "CONTENT_SET_OUTLINE_REF",
    "PROFILE_EMAIL_COUNT_SINGLE",
    "PROFILE_EMAIL_COUNT_MULTI",

    # 9.3 Page content
    "CORE_FIELDS_TEXT_OBJECTS",

    # 9.4 Embedded files
    "EMBEDDEDFILES_PRESENT",
    "AF_IN_NAME_TREE",
    "EMBEDDED_FILESPEC_NO_PATH_COMPONENTS",
    "EMBEDDED_SUBTYPE_PRESENT",
    "EMBEDDED_SUBTYPE_NO_PARAMETERS",
    "EMBEDDED_MEDIA_TYPE_MATCH_XMP",
    "SOURCE_MEDIATYPE_PARAMETERS_MATCH",
    "EMBEDDED_STREAM",
    "EMBEDDED_SIZE_MATCH",
    "EMBEDDED_MD5_MATCH",
    "SOURCE_WITHOUT_XMP",
    "SOURCE_MIME_NONE",
    "SOURCE_MIME_TYPE",
    "SOURCE_MIME_GENERIC",

    # 9.5 Collections
    "PROFILE_COLLECTION",
    "COLLECTION_REQUIRED",
    "COLLECTION_FORBIDDEN",
    "COLLECTION_EXTENSIONS",
    "COLLECTION_D_FORBIDDEN",
    "COLLECTION_VIEW_FORBIDDEN",
    "COLLECTION_NAVIGATOR_FORBIDDEN",
    "COLLECTION_SCHEMA_PRESENT",
    "COLLECTION_SCHEMA_FIELD",
    "COLLECTION_SCHEMA_E_FALSE",

    # 9.6 DPM
    "DPM_ROOTNODE_PRESENT",
    "DPM_KEY_PREFIX",
    "DPM_INTERMEDIATE_COUNT_SINGLE",
    "DPM_INTERMEDIATE_COUNT_MULTI",
    "DPM_INTERMEDIATE_COUNT_CONTAINER",
    "DPM_MAIL_GUID_PRESENT",
    "DPM_MAIL_GUID_MATCH",
    "DPM_MAIL_MESSAGEID_MATCH",
    "DPM_LEAF_REQUIRED",
    "DPM_CONTENTSETTYPE_PRESENT",
    "DPM_DESC_REQUIRED",
    "DPM_SUBTYPE_REQUIRED",
    "DPM_ATTACHMENTLIST_FORBIDDEN",

    # 9.7 Logical structure / UserProperties
    "STRUCTTREE_PRESENT",
    "STRUCTTREE_ROOT_DOCUMENT",
    "ROLEMAPNS_PRESENT",
    "ROLEMAP_MAIL_TAGS",
    "MAIL_MESSAGE_COUNT_SINGLE",
    "MAIL_MESSAGE_COUNT_MULTI",
    "MAIL_MESSAGE_COUNT_CONTAINER",
    "MAIL_MESSAGE_CHILDREN",
    "MAIL_CONTENTSET_COUNT",
    "MAIL_CONTENTGROUP_CHILDREN",
    "MAIL_CONTENTSET_CHILDREN",
    "MAIL_FIELD_CHILDREN",
    "MAIL_FIELDVALUE_WITH_NAME",
    "MAIL_FIELD_PROFILE_USE",
    "MAIL_FIELDNAME_PROFILE_USE",
    "MAIL_FIELDVALUE_PROFILE_USE",
    "MAIL_MESSAGE_GUID_MATCH_DPM_XMP",
    "MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT",
    "MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT",
    "USERPROPS_NAME_ASCII",
    "USERPROPS_TYPE",
    "USERPROPS_TAG_SCOPE",

    # 9.8 XMP metadata
    "XMP_PRESENT",
    "XMP_MISSING",
    "XMP_PARSE",
    "PDFA_XMP_UNCHECKED",
    "EAPDF_XMP_UNCHECKED",
    "XMP_NS_PREFIX",
    "XMP_CREATOR",
    "XMP_CREATOR_SEMANTICS",
    "XMP_PRODUCER",
    "XMP_PRODUCER_SEMANTICS",
    "XMP_CREATEDATE",
    "XMP_CREATEDATE_SEMANTICS",
    "XMP_TITLE",

    # 9.8.1 Conformance and identification
    "EAPDF_VERSION",
    "EAPDF_REV",
    "EAPDF_CONFORMANCE_VALUE",
    "EAPDF_CONFORMANCE_I_LAST",

    # 9.8.2 Source email assets
    "ASSET_XMP",
    "ASSET_XMP_SKIPPED",
    "ASSET_XMP_FIELDS",
    "ASSET_SIZE_TYPE",
    "ASSET_NUMBERMESSAGES_TYPE",
    "ASSET_CHECKSUM_FORM",
    "ASSET_CONTENTTYPE",
    "ASSET_CONTENTTYPE_GENERIC",
    "ASSET_MATCH",
    "ASSET_MD5_MATCH",
    "ASSET_SIZE_MATCH",
    "ASSET_CONTENTTYPE_MATCH",

    # 9.8.3 Email headers (Table 18 order)
    "MESSAGE_TO_XMP",
    "MESSAGE_TO_FOAF",
    "MESSAGE_RAW_TO_XMP",
    "MESSAGE_FROM_XMP",
    "MESSAGE_FROM_FOAF",
    "MESSAGE_RAW_FROM_XMP",
    "MESSAGE_SENT_XMP",
    "MESSAGE_SENT_FORMAT",
    "MESSAGE_RAW_SENT_REQUIRED_IF_INVALID",
    "MESSAGE_RAW_SENT_OMIT_IF_VALID",
    "MESSAGE_SUBJECT_XMP",
    "MESSAGE_ID_XMP",
    "MAIL_GUID_XMP",
    "MESSAGE_SIZE_XMP",
    "MESSAGE_ATTACHMENT_COUNT_XMP",
    "MESSAGE_CC_XMP",
    "MESSAGE_CC_FOAF",
    "MESSAGE_RAW_CC_XMP",
    "MESSAGE_BCC_XMP",
    "MESSAGE_BCC_FOAF",
    "MESSAGE_RAW_BCC_XMP",
    "MESSAGE_INREPLYTO_XMP",
    "MESSAGE_METADATA_SKIPPED",

    # 9.8.4 Email attachments
    "ATTACHMENT_XMP_CONTAINER",
    "ATTACHMENT_XMP_FILENAME",
    "ATTACHMENT_XMP_SIZE_PRESENT",
    "ATTACHMENT_XMP_SIZE_TYPE",
    "ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID",
    "ATTACHMENT_XMP_CONTENTTYPE",
    "ATTACHMENT_XMP_CONTENTTYPE_MATCH",
    "ATTACHMENT_XMP_CHECKSUM_FORM",
    "ATTACHMENT_XMP_SIZE_MATCH",
]

SPEC_REFERENCES.extend([
    {"codes":{"SOURCE_FILE_SET"},
     "source":"docs/reference/EA-PDF-v1.pdf; ISO 32000-2 Table 43 and section 7.11.4.2",
     "spec":"EA-PDF 1.0 sections 9.1 and 9.4",
     "excerpt":"A source email file set requires Related Files on its primary Source file specification or catalog Supplement associated files. Independent Source assets are not a file set."},
    {"codes":{"COLLECTION_CONTENT_TYPES"},
     "source":"docs/reference/EA-PDF-v1.pdf",
     "spec":"EA-PDF 1.0 section 7.6",
     "excerpt":"Besides original raw source email assets, contained embedded files in collection profiles must be other EA-PDF files."},
    {"codes":{"XMP_UTF8"},
     "source":"docs/reference/EA-PDF-v1.pdf",
     "spec":"EA-PDF 1.0 section 7.1.1",
     "excerpt":"All XMP metadata stored in EA-PDF files must be encoded as UTF-8, including PDF/A-3-based files."},
    {"codes":{"EMBEDDED_MD5_BINARY_FORMAT"},
     "source":"ISO 32000-2 Table 45; EA-PDF section 9.4",
     "spec":"EA-PDF 1.0 section 9.4",
     "excerpt":"When present, the embedded-file CheckSum parameter is a PDF string containing the 16 MD5 bytes. XMP checksums use hexadecimal text instead."},
    {"codes":{"DPM_BODY_MEDIA_TYPE_MATCH_SOURCE"},
     "source":"EA-PDF section 9.6 Table 8",
     "spec":"EA-PDF 1.0 section 9.6",
     "excerpt":"BodyRendering Mail_Subtype identifies the source email body representation. An inspectable source must support the declared media type."},
    {"codes":{"DPART_OBJECT_STRUCTURE","DPM_NAME_TYPES"},
     "source":"docs/reference/EA-PDF-v1.pdf; ISO 32000-2:2020 Table 409",
     "spec":"EA-PDF 1.0 section 9.6",
     "excerpt":"DPart structures follow ISO 32000-2. Mail_ContentSetType and Mail_Subtype are PDF name objects."},
    {"codes":{"EMAIL_XMP_CONTAINER","MESSAGE_SENT_XMP","MESSAGE_SENT_FORMAT","MAIL_GUID_UNIQUE"},
     "source":"docs/reference/EA-PDF-v1.pdf, Table 18",
     "spec":"EA-PDF 1.0 section 9.8.3",
     "excerpt":"Email Core Fields are an unordered set of email containers. Each email requires Sent as xsd:dateTime and a unique Mail_GUID."},
    {"codes":{"VALIDATION_INCOMPLETE"},
     "source":"Validator coverage limitation",
     "spec":"EA-PDF validation scope",
     "excerpt":"Untested applicable requirements do not establish conformance and must not produce an overall PASS."},
    {"codes":{"MESSAGE_SOURCE_COVERAGE"},
     "source":"docs/reference/EA-PDF-v1.pdf, Table 18",
     "spec":"EA-PDF 1.0 section 9.8.3",
     "excerpt":"Email Core Fields describe each email. Comparisons must use the matching source email, not another message in the archive."},
    {"codes":{"STRUCTURE_CORRESPONDENCE"},
     "source":"docs/reference/EA-PDF-v1.pdf; ISO 32000-2 logical structure",
     "spec":"EA-PDF 1.0 section 9.7",
     "excerpt":"Marked content, structure content references, and ParentTree entries must consistently identify the same content and owning structure element."},
    {"codes":{"ANNOTATION_CONTENTS_REQUIRED"},
     "source":"docs/reference/EA-PDF-v1.pdf, section 7.5 printed p44",
     "spec":"EA-PDF 1.0 section 7.5",
     "excerpt":"Each FileAttachment annotation must contain Contents text; filespec Desc is recommended separately."},
    {"codes":{"COLLECTION_CHILD_VALIDATION"},
     "source":"docs/reference/EA-PDF-v1.pdf",
     "spec":"EA-PDF 1.0 collection profiles",
     "excerpt":"Contained email documents are EA-PDF files and require both PDF/A and EA-PDF validation independently of their container."},
])
_ALL_SPEC_CODES = {code for ref in SPEC_REFERENCES for code in ref["codes"]}
_RULE_TEXT_CODES = list(RULE_TEXT.keys())
# The authoritative registry defines both membership and specification order.
ALL_RULE_CODES = list(RULE_TEXT)

def qn(ns,local): return "{%s}%s"%(ns,local)
def local(tag): return tag.split("}",1)[1] if tag.startswith("{") else tag
def norm_text(x): return "" if x is None else " ".join(str(x).split())
def md5hex(b): return hashlib.md5(b).hexdigest()
def sha256hex(b): return hashlib.sha256(b).hexdigest()

def contains_surrogate(value):
    return isinstance(value,str) and any(0xD800<=ord(char)<=0xDFFF for char in value)

def diagnostic_value(value):
    """Escape undecoded octets for reports without changing source evidence."""
    if isinstance(value,str):
        return value.encode("utf-8","backslashreplace").decode("utf-8") if contains_surrogate(value) else value
    if isinstance(value,dict):
        return {key:diagnostic_value(member) for key,member in value.items()}
    if isinstance(value,list):
        return [diagnostic_value(member) for member in value]
    if isinstance(value,tuple):
        return tuple(diagnostic_value(member) for member in value)
    return value


class Report(_report_model.ReportModel):
    @staticmethod
    def _rulebook_hash():
        return RULEBOOK_SHA256

    @staticmethod
    def _covered_rules():
        return PDFA_COVERED_RULES

    @staticmethod
    def _rule_codes():
        return ALL_RULE_CODES

    @staticmethod
    def _diagnostic_value(value):
        return diagnostic_value(value)

def combined_validation_status(summary, pdfa):
    return _reporting.combined_validation_status(summary, pdfa)

def batch_validation_status(results):
    return _reporting.batch_validation_status(results)

def record_verapdf_delegations(report,pdfa=None):
    # The real first pass remains mandatory, including for collection children.
    # Mark guards for local call sites without producing duplicate rule rows.
    report.delegated_codes.update(PDFA_COVERED_RULES)

def spec_references_for(code):
    rule_text=RULE_TEXT.get(code,{})
    if rule_text.get("spec_section") or rule_text.get("spec_excerpt"):
        return [{
            "codes": {code},
            "source": RULE_TEXT_PATH.name,
            "spec": rule_text.get("spec_section","EA-PDF 1.0"),
            "excerpt": rule_text.get("spec_excerpt",""),
        }]
    refs=[]
    for ref in SPEC_REFERENCES:
        if code in ref["codes"]:
            item=dict(ref)
            if code in RULE_EXCERPT_OVERRIDES:
                item["excerpt"]=RULE_EXCERPT_OVERRIDES[code]
            refs.append(item)
    return refs

def format_details(details):
    return _reporting.format_details(details)

def derive_html_path(pdf_path, html_path):
    if html_path:
        return Path(html_path)
    out_dir=Path("validation")
    out_dir.mkdir(parents=True, exist_ok=True)
    p=Path(pdf_path)
    return out_dir / (p.name + "." + uuid.uuid4().hex + ".validation.html")

def _engine_settings():
    return _engines.EngineSettings(VERAPDF_DOCKER_IMAGE, VERAPDF_CONTAINER_NAME, VERAPDF_MOUNT_ROOT)


def _verapdf_default_result(path, status="SKIP", message="veraPDF validation was not run", extra=None):
    return _engines._verapdf_default_result(path, status, message, extra, image=VERAPDF_DOCKER_IMAGE)

def _verapdf_stderr_severity(stderr):
    return _engines._verapdf_stderr_severity(stderr)

def _docker_work_path(pdf_path):
    return _engines._docker_work_path(pdf_path, mount_root=VERAPDF_MOUNT_ROOT)

def ensure_verapdf_daemon():
    return _engines.ensure_verapdf_daemon(settings=_engine_settings(), executables=shutil, processes=subprocess)

def _verapdf_timeout(pdf_path):
    return _engines._verapdf_timeout(pdf_path, configured_timeout=VERAPDF_TIMEOUT)


def _verapdf_heap_mb():
    return _engines._verapdf_heap_mb()


def _verapdf_command(pdf_path, output_format, profile_path=None):
    return _engines._verapdf_command(pdf_path, output_format, profile_path,
        settings=_engine_settings(), work_path=_docker_work_path, heap_for=_verapdf_heap_mb,
        timeout_for=_verapdf_timeout, ensure_daemon=ensure_verapdf_daemon, executables=shutil)

def run_verapdf(path, profile_path=None, expected_profile=None):
    return _engines.run_verapdf(path, profile_path, expected_profile,
        allowed_profiles=ALLOWED_VERAPDF_PROFILES, command_for=_verapdf_command,
        default_result=_verapdf_default_result, severity_for=_verapdf_stderr_severity,
        timeout_for=_verapdf_timeout, executables=shutil, processes=subprocess)

def run_iso32005(path):
    return _engines.run_iso32005(path, root=ROOT, pin_config=ISO32005_PROFILE,
        run_pdfa=run_verapdf, default_result=_verapdf_default_result)

def check_iso32005_upstream(pdf,report,pdfa):
    applicable=((str(pdf.pdf_version)=="2.0" or safe_name(dict_get(pdf.Root,"/Version",None))=="2.0")
                and dict_get(pdf.Root,"/StructTreeRoot",None) is not None)
    if not applicable:
        report.na("ISO32005_UPSTREAM","No PDF 2.0 logical structure requiring the upstream ISO 32005 pass")
    elif pdfa is None:
        report.na("ISO32005_UPSTREAM","Upstream execution belongs to the complete validation pipeline",{
            "state":"not_run","reason":"This call performs custom EA-PDF checks only; it is not an overall conformance result."})
    elif not (pdfa.get("status")=="PASS" and pdfa.get("ran") is True
              and pdfa.get("compliant") is True and pdfa.get("profile_allowed") is True):
        report.na("ISO32005_UPSTREAM","Upstream ISO 32005 pass awaits a successful PDF/A first pass",{
            "state":"not_run","pdfa_status":pdfa.get("status")})
    else:
        result=run_iso32005(report.path)
        if result.get("profile_verified") is True and result.get("status") in {"PASS","FAIL"}:
            report.add(result["status"],"ISO32005_UPSTREAM","Upstream ISO 32005 structure validation "+result["status"],result)
        else:
            report.warn("ISO32005_UPSTREAM","Required upstream ISO 32005 execution is unavailable or unverified",result)
            report.warn("VALIDATION_INCOMPLETE","Required upstream ISO 32005 validation did not complete",{
                "rule":"ISO32005_UPSTREAM","upstream_status":result.get("status")})

def render_html(report, pdfa=None):
    context = _reporting.HtmlContext(
        rule_text=RULE_TEXT, scope=VALIDATION_SCOPE, default_pdfa=_verapdf_default_result,
        combined_status=combined_validation_status, format_details=format_details,
        escape=html_escape, spec_references=spec_references_for,
        specification_context=specification_context_html)
    return _reporting.render_html(report, pdfa, context=context)

def write_html_report(report, pdf_path, html_path=None, pdfa=None):
    return _reporting.write_html_report(report, pdf_path, html_path, pdfa,
        derive_path=derive_html_path, render=render_html)

def objgen(x):
    try: return tuple(x.objgen)
    except Exception: return None

def safe_name(x):
    if x is None: return ""
    try:
        s=str(x)
        if s=="None": return ""
        if s.startswith("/"): s=s[1:]
        return s
    except Exception:
        return ""

def pdf_name_eq(x,val):
    return safe_name(x)==val

def dict_get(d,key,default=None):
    try:
        return d.get(key,default)
    except Exception:
        return default

def observed_pdf_value(value,depth=0):
    if value is None or isinstance(value,(str,bool,int,float)):
        return value
    if isinstance(value,(dict,pikepdf.Dictionary,pikepdf.Stream)):
        if depth>=2:
            return {"object":objgen(value),"keys":sorted(value.keys())}
        return {key:observed_pdf_value(child,depth+1) for key,child in value.items()}
    if is_pdf_array(value) or isinstance(value,(list,tuple)):
        if depth>=3:
            return {"object":objgen(value),"length":len(value)}
        return [observed_pdf_value(child,depth+1) for child in value]
    try:
        return str(value)
    except UnicodeError:
        return {"pdf_type":"name" if isinstance(value,pikepdf.Name) else "string",
                "raw_hex":bytes(value).hex(),"decoding_error":"Invalid Unicode encoding"}

def is_pdf_array(value):
    try:
        return isinstance(value, pikepdf.Array)
    except Exception:
        return False

def get_stream_bytes(obj):
    try:
        return read_stream(obj, stream_setting("VALIDATOR_MAX_STREAM_BYTES", 64 * 1024**2))
    except DecodeLimitError:
        raise
    except Exception:
        return b""

def normalize_pdf_checksum(value):
    if value is None:
        return ""
    try:
        raw=bytes(value)
        if len(raw)==16:
            return raw.hex()
    except Exception:
        pass
    return norm_text(str(value))

def embedded_file_candidates(pdf,efiles,afiles):
    candidates=[]
    seen=set()
    annotations=[annotation.get("file") for annotation in collect_file_attachment_annotations(pdf)]
    for fileinfo in list(efiles)+list(afiles)+collect_object_af_specs(pdf)+collect_marked_content_af_specs(pdf)+annotations:
        if not fileinfo:
            continue
        identity=fileinfo.get("objgen")
        if identity not in (None,(0,0)):
            if identity in seen:
                continue
            seen.add(identity)
        candidates.append(fileinfo)
    return candidates

def embedded_file_observations(files):
    fields=("tree_name","filename","ufilename","raw_filename","raw_ufilename","afrelationship","subtype","size","size_entry","checksum","md5","media_type_parameters")
    return [{field:fileinfo.get(field) for field in fields} for fileinfo in files]

def parse_xmp(xmp_bytes,report):
    if not xmp_bytes:
        if not report.delegates("XMP_MISSING"):
            report.fail("XMP_MISSING","Document catalog Metadata XMP stream missing",{"metadata_bytes":len(xmp_bytes or b"")})
        return None,{},{}
    try:
        if re.search(br"<!\s*(?:DOCTYPE|ENTITY)\b", xmp_bytes.replace(b"\0", b""), re.I):
            raise DecodeLimitError("DTD/entity declarations are not supported in XMP")
        nsmap={}
        depth = count = 0
        parser = ET.iterparse(BytesIO(xmp_bytes), events=("start-ns", "start", "end"))
        for event,item in parser:
            if event == "start-ns":
                pfx,uri=item
                nsmap[pfx]=uri
            elif event == "start":
                depth += 1; count += 1
                if depth > 64 or count > 100000:
                    raise DecodeLimitError("XMP exceeds its depth/node inspection limit")
            else:
                depth -= 1
        root=parser.root
        if not report.delegates("XMP_PRESENT"):
            report.ok("XMP_PRESENT","Document catalog Metadata XMP stream present",{"bytes":len(xmp_bytes)})
        return root,nsmap,extract_xmp_model(root)
    except DecodeLimitError as e:
        report.warn("VALIDATION_INCOMPLETE", str(e), {"assessment": "unavailable"})
        return None,{},{}
    except Exception as e:
        if not report.delegates("XMP_PARSE"):
            report.fail("XMP_PARSE","Unable to parse XMP metadata",{"error":str(e)})
        return None,{},{}

def utf8_xmp_error(data):
    try:
        text=data.decode("utf-8-sig",errors="strict")
    except UnicodeError:
        return "Metadata bytes are not UTF-8"
    if "\x00" in text:
        return "Metadata contains NUL bytes (including UTF-16/32 encodings)"
    declaration=re.match(r"\s*<\?xml\s+[^?]*\?>",text)
    encoding=re.search(r"\bencoding\s*=\s*(['\"])(.*?)\1",declaration.group(0),re.I) if declaration else None
    if encoding and encoding.group(2).lower() not in ("utf-8","utf8"):
        return "XML declaration specifies a non-UTF-8 encoding"
    return ""

def _xmp_rules_dependencies():
    return _xmp_rules.Dependencies(
        ALLOWED_EAPDF_CONFORMANCE=ALLOWED_EAPDF_CONFORMANCE,
        ALLOWED_PDFA=ALLOWED_PDFA,
        NS_DC=NS_DC,
        NS_PDF=NS_PDF,
        NS_PDFAID=NS_PDFAID,
        NS_PDFMAIL=NS_PDFMAIL,
        NS_PDFMAILID=NS_PDFMAILID,
        NS_PDFMAILMETA=NS_PDFMAILMETA,
        NS_XMP=NS_XMP,
        XmpArray=XmpArray,
        XmpUri=XmpUri,
        chain=chain,
        check_single_source_xmp_semantics=check_single_source_xmp_semantics,
        datetime_values_match=datetime_values_match,
        dict_get=dict_get,
        evidence_unavailable=evidence_unavailable,
        find_xmp_values=find_xmp_values,
        first_message_xmp_value=first_message_xmp_value,
        first_present=first_present,
        first_xmp_value=first_xmp_value,
        flatten_attachments=flatten_attachments,
        flatten_email_records=flatten_email_records,
        guid_value=guid_value,
        is_nonneg_int_str=is_nonneg_int_str,
        is_xmp_text=is_xmp_text,
        local=local,
        looks_like_xmp_date=looks_like_xmp_date,
        looks_like_xmp_datetime=looks_like_xmp_datetime,
        nonnegative_integer_equals=nonnegative_integer_equals,
        norm_text=norm_text,
        observed_pdf_value=observed_pdf_value,
        pdf_object_identity=pdf_object_identity,
        pikepdf=pikepdf,
        qn=qn,
        read_docinfo=read_docinfo,
        read_stream=read_stream,
        safe_name=safe_name,
        stream_setting=stream_setting,
        summarize_source_email=summarize_source_email,
        summarize_source_emails=summarize_source_emails,
        text_values_match=text_values_match,
        utf8_xmp_error=utf8_xmp_error,
        xmp_default_language_values=xmp_default_language_values,
        xmp_descriptions=xmp_descriptions,
        xmp_document_property_values=xmp_document_property_values,
        xmp_property_nodes=xmp_property_nodes,
        xmp_semantic_type=xmp_semantic_type,
        xmp_semantic_value=xmp_semantic_value,
    )

def _embedding_rules_dependencies():
    return _embedding_rules.Dependencies(
        ALLOWED_EAPDF_CONFORMANCE=ALLOWED_EAPDF_CONFORMANCE,
        BytesIO=BytesIO,
        ET=ET,
        GENERIC_BAD_MIME=GENERIC_BAD_MIME,
        MAX_TREE_ITEMS=MAX_TREE_ITEMS,
        NS_PDFMAILID=NS_PDFMAILID,
        attachment_candidates_for_embedded=attachment_candidates_for_embedded,
        attachment_comparison_signature=attachment_comparison_signature,
        attachment_email_owners=attachment_email_owners,
        attachment_file_names=attachment_file_names,
        attachment_source_checksum=attachment_source_checksum,
        check_ambiguous_source_asset_values=check_ambiguous_source_asset_values,
        check_xmp_record_array=check_xmp_record_array,
        collect_file_attachment_annotations=collect_file_attachment_annotations,
        collect_marked_content_af_specs=collect_marked_content_af_specs,
        collect_object_af_specs=collect_object_af_specs,
        defaultdict=defaultdict,
        dict_get=dict_get,
        effective_pdf_version=effective_pdf_version,
        embedded_file_candidates=embedded_file_candidates,
        embedded_file_observations=embedded_file_observations,
        evidence_unavailable=evidence_unavailable,
        first_xmp_value=first_xmp_value,
        flatten_assets=flatten_assets,
        flatten_attachments=flatten_attachments,
        get_af_specs=get_af_specs,
        get_declared_pdf_version=get_declared_pdf_version,
        get_embedded_files=get_embedded_files,
        identity_fields=identity_fields,
        is_nonneg_int_str=is_nonneg_int_str,
        is_pdf_array=is_pdf_array,
        is_xmp_text=is_xmp_text,
        looks_like_pdf_date=looks_like_pdf_date,
        map_embedded_by_objgen=map_embedded_by_objgen,
        map_stream_by_objgen=map_stream_by_objgen,
        media_type_signature=media_type_signature,
        nonnegative_integer_equals=nonnegative_integer_equals,
        norm_text=norm_text,
        normalize_path_tail=normalize_path_tail,
        observed_pdf_value=observed_pdf_value,
        parse_filespec=parse_filespec,
        parse_nonneg_int_value=parse_nonneg_int_value,
        pdf_object_identity=pdf_object_identity,
        pikepdf=pikepdf,
        re=re,
        read_metadata_stream=read_metadata_stream,
        reconstruct_embedded_media_type=reconstruct_embedded_media_type,
        represented_email_attachment_presence=represented_email_attachment_presence,
        safe_name=safe_name,
        source_file_name=source_file_name,
        source_set_supplements=source_set_supplements,
        xmp_semantic_type=xmp_semantic_type,
    )

def _structure_rules_dependencies():
    return _structure_rules.Dependencies(
        Decimal=Decimal,
        InvalidOperation=InvalidOperation,
        MAIL_STRUCTURE_TYPES=MAIL_STRUCTURE_TYPES,
        MAX_TREE_ITEMS=MAX_TREE_ITEMS,
        Report=Report,
        StructureRoles=StructureRoles,
        analyze_page_content=analyze_page_content,
        check_dpart_object_structure=check_dpart_object_structure,
        check_page_dpart_links=check_page_dpart_links,
        check_structure_correspondence=check_structure_correspondence,
        check_user_properties=check_user_properties,
        datetime=datetime,
        decoded_source_header=decoded_source_header,
        defaultdict=defaultdict,
        dict_get=dict_get,
        dpart_ancestor_guid=dpart_ancestor_guid,
        dpart_level_name_assessment=dpart_level_name_assessment,
        dpart_message_identity=dpart_message_identity,
        emit_if_unseen=emit_if_unseen,
        evidence_unavailable=evidence_unavailable,
        flatten_email_records=flatten_email_records,
        get_dpart_nodes=get_dpart_nodes,
        get_outline_items=get_outline_items,
        get_outline_page_objgens=get_outline_page_objgens,
        guid_value=guid_value,
        identity_fields=identity_fields,
        independent_content_ownership=independent_content_ownership,
        inspect_dpm_value_graph=inspect_dpm_value_graph,
        is_pdf_array=is_pdf_array,
        is_pdf_ascii_string=is_pdf_ascii_string,
        looks_like_pdf_date=looks_like_pdf_date,
        norm_text=norm_text,
        normalize_role_map=normalize_role_map,
        objgen=objgen,
        observed_header_source_inventory=observed_header_source_inventory,
        observed_pdf_value=observed_pdf_value,
        page_has_visibleish_content=page_has_visibleish_content,
        page_objgen_map=page_objgen_map,
        page_range_from_dpart=page_range_from_dpart,
        parse_userproperties=parse_userproperties,
        parse_xmp_datetime=parse_xmp_datetime,
        pdf_object_identity=pdf_object_identity,
        pikepdf=pikepdf,
        re=re,
        safe_name=safe_name,
        source_email_for_record=source_email_for_record,
        struct_children=struct_children,
        structure_field_text_sequences=structure_field_text_sequences,
        summarize_source_emails=summarize_source_emails,
        timedelta=timedelta,
        timezone=timezone,
        userprop_name=userprop_name,
        userprop_value_kind=userprop_value_kind,
        visit_tree_node=visit_tree_node,
        walk_struct_elems=walk_struct_elems,
        xmp_value_set=xmp_value_set,
    )

def _message_rules_dependencies():
    return _message_rules.Dependencies(
        Decimal=Decimal,
        ET=ET,
        MAX_TREE_ITEMS=MAX_TREE_ITEMS,
        NS_PDFMAILMETA=NS_PDFMAILMETA,
        NS_RDF=NS_RDF,
        XmpArray=XmpArray,
        attachment_email_owners=attachment_email_owners,
        check_ambiguous_source_header_values=check_ambiguous_source_header_values,
        check_native_outlook_core=check_native_outlook_core,
        check_single_message_metadata=check_single_message_metadata,
        check_source_field_values=check_source_field_values,
        check_xmp_record_array=check_xmp_record_array,
        clean_header_text=clean_header_text,
        compare_agent_property=compare_agent_property,
        compare_native_outlook_raw=compare_native_outlook_raw,
        contains_surrogate=contains_surrogate,
        decoded_header_text=decoded_header_text,
        decoded_source_header=decoded_source_header,
        deepcopy=deepcopy,
        diagnostic_value=diagnostic_value,
        evidence_unavailable=evidence_unavailable,
        find_xmp_values=find_xmp_values,
        first_message_xmp_value=first_message_xmp_value,
        first_present=first_present,
        flatten_attachments=flatten_attachments,
        flatten_email_records=flatten_email_records,
        guid_value=guid_value,
        inspect_outlook_source=inspect_outlook_source,
        is_nonneg_int_str=is_nonneg_int_str,
        is_xmp_text=is_xmp_text,
        looks_like_xmp_datetime=looks_like_xmp_datetime,
        mailbox_comparison_key=mailbox_comparison_key,
        md5hex=md5hex,
        message_xmp_property_present=message_xmp_property_present,
        nonnegative_integer_equals=nonnegative_integer_equals,
        norm_text=norm_text,
        normalize_foaf_agents=normalize_foaf_agents,
        parse_nonneg_int_value=parse_nonneg_int_value,
        parse_xmp_datetime=parse_xmp_datetime,
        qn=qn,
        raw_address_mailbox_keys=raw_address_mailbox_keys,
        re=re,
        select_source_email=select_source_email,
        sha256hex=sha256hex,
        source_assertion_key=source_assertion_key,
        source_date_present=source_date_present,
        source_file_name=source_file_name,
        source_header_candidate_failures=source_header_candidate_failures,
        source_metadata_result=source_metadata_result,
        summarize_source_email=summarize_source_email,
        summarize_source_emails=summarize_source_emails,
        valid_smtp_mailbox=valid_smtp_mailbox,
        validate_attachment_records=validate_attachment_records,
        xmp_array_members=xmp_array_members,
    )

def _media_rules_dependencies():
    return _media_rules.Dependencies(
        GENERIC_BAD_MIME=GENERIC_BAD_MIME,
        MAX_TREE_ITEMS=MAX_TREE_ITEMS,
        NS_PDFMAILID=NS_PDFMAILID,
        check_attachment_rendering_media=check_attachment_rendering_media,
        collect_body_content_sets=collect_body_content_sets,
        declared_content_sets_are_nonbody=declared_content_sets_are_nonbody,
        defaultdict=defaultdict,
        dict_get=dict_get,
        embedded_file_candidates=embedded_file_candidates,
        embedded_file_observations=embedded_file_observations,
        evidence_unavailable=evidence_unavailable,
        first_xmp_value=first_xmp_value,
        flatten_assets=flatten_assets,
        flatten_email_records=flatten_email_records,
        get_dpart_nodes=get_dpart_nodes,
        get_embedded_files=get_embedded_files,
        guid_value=guid_value,
        identities_match=identities_match,
        identity_fields=identity_fields,
        independent_content_ownership=independent_content_ownership,
        is_nonneg_int_str=is_nonneg_int_str,
        is_pdf_array=is_pdf_array,
        is_xmp_text=is_xmp_text,
        media_type_signature=media_type_signature,
        nonnegative_integer_equals=nonnegative_integer_equals,
        norm_text=norm_text,
        objgen=objgen,
        observed_pdf_value=observed_pdf_value,
        original_attachment_media_types=original_attachment_media_types,
        page_objgen_map=page_objgen_map,
        page_range_from_dpart=page_range_from_dpart,
        pikepdf=pikepdf,
        re=re,
        safe_name=safe_name,
        sha256hex=sha256hex,
        source_email_for_record=source_email_for_record,
        source_file_name=source_file_name,
        summarize_source_emails=summarize_source_emails,
    )


def check_xmp_utf8(pdf,report):
    return _xmp_rules.check_xmp_utf8(_xmp_rules_dependencies(), pdf, report)

def extract_xmp_model(root):
    model={"descriptions":[]}
    for desc in root.findall(".//"+qn(NS_RDF,"Description")):
        item={"attrs":[],"children":[]}
        for k,v in desc.attrib.items():
            if k.startswith("{"):
                ns,ln=k[1:].split("}",1)
            else:
                ns,ln="",k
            item["attrs"].append({"ns":ns,"name":ln,"value":norm_text(v)})
        for ch in list(desc):
            item["children"].append(parse_xmp_node(ch))
        model["descriptions"].append(item)
    return model

def parse_xmp_node(node):
    ns=""
    nm=node.tag
    if node.tag.startswith("{"):
        ns,nm=node.tag[1:].split("}",1)
    out={"ns":ns,"name":nm,"attrs":[],"value":norm_text(node.text),"children":[]}
    for k,v in node.attrib.items():
        if k.startswith("{"):
            kns,kln=k[1:].split("}",1)
        else:
            kns,kln="",k
        out["attrs"].append({"ns":kns,"name":kln,"value":norm_text(v)})
    if len(node):
        if len(node)==1 and node[0].tag in (qn(NS_RDF,"Bag"),qn(NS_RDF,"Seq"),qn(NS_RDF,"Alt")):
            arr=[]
            for li in node[0]:
                arr.append(parse_rdf_li(li))
            out["value"]=arr
        else:
            out["children"]=[parse_xmp_node(ch) for ch in list(node)]
    return out

class XmpUri(str):
    """Keep RDF URI values distinct from XMP normal strings."""

def is_xmp_text(value):
    return isinstance(value,str) and not isinstance(value,XmpUri)

class XmpArray(list):
    def __init__(self,values,kind):
        super().__init__(values)
        self.kind=kind

def xmp_semantic_type(value):
    if isinstance(value,XmpUri):
        return "URI"
    if isinstance(value,XmpArray):
        return "rdf:"+value.kind+" array"
    if isinstance(value,dict):
        return "structure"
    return "normal simple string" if is_xmp_text(value) else type(value).__name__

def guid_value(value):
    # A GUID is a normal string, not a UUID grammar, URI, container or number.
    if isinstance(value,(str,pikepdf.String)) and not isinstance(value,XmpUri):
        return str(value)
    return ""

def xmp_descriptions(root):
    if root is None:
        return []
    rdf=root if root.tag==qn(NS_RDF,"RDF") else root.find(".//"+qn(NS_RDF,"RDF"))
    if rdf is not None:
        return rdf.findall(qn(NS_RDF,"Description"))
    return [root] if root.tag==qn(NS_RDF,"Description") else []

def xmp_property_nodes(root,ns,prop):
    return [node for desc in xmp_descriptions(root) for node in desc.findall(qn(ns,prop))]

def xmp_document_property_values(root,ns,prop):
    """Read values without flattening arrays or normalizing normal strings."""
    values=[]
    for description in xmp_descriptions(root):
        if qn(ns,prop) in description.attrib:
            values.append(description.get(qn(ns,prop)))
        values.extend(xmp_semantic_value(node) for node in description.findall(qn(ns,prop)))
    return values

def xmp_array_members(root,prop):
    members=[]
    for node in xmp_property_nodes(root,NS_PDFMAILMETA,prop):
        while True:
            if len(node)==1 and node[0].tag==qn(NS_RDF,"Description"):
                node=node[0]
            elif node.find(qn(NS_RDF,"value")) is not None:
                node=node.find(qn(NS_RDF,"value"))
            else:
                break
        if len(node)==1 and node[0].tag in {qn(NS_RDF,k) for k in ("Bag","Seq","Alt")}:
            members.extend(node[0])
    return members

def check_xmp_record_array(root,prop,report,code,required=False):
    return _xmp_rules.check_xmp_record_array(_xmp_rules_dependencies(), root, prop, report, code, required)

def xmp_semantic_value(node):
    resource=node.get(qn(NS_RDF,"resource"))
    if resource is not None:
        return XmpUri(resource)
    if len(node)==1 and node[0].tag in {qn(NS_RDF,k) for k in ("Bag","Seq","Alt")}:
        return XmpArray([xmp_semantic_value(li) for li in node[0]],local(node[0].tag))
    value_node=node[0] if len(node)==1 and node[0].tag==qn(NS_RDF,"Description") else node
    qualified=value_node.find(qn(NS_RDF,"value"))
    if qualified is not None:
        return xmp_semantic_value(qualified)
    if qn(NS_RDF,"value") in value_node.attrib:
        return value_node.get(qn(NS_RDF,"value"))
    # XMP permits typed inner resource nodes as well as rdf:Description.
    if (node.tag==qn(NS_RDF,"li") and len(node)==1
            and node.get(qn(NS_RDF,"parseType"))!="Resource" and len(node[0])
            and node[0].tag!=qn(NS_RDF,"Description")):
        value_node=node[0]
    fields=rdf_description_to_dict(value_node)
    if fields or value_node is not node or node.get(qn(NS_RDF,"parseType"))=="Resource":
        return fields
    return node.text or ""

def parse_rdf_li(li):
    return xmp_semantic_value(li)

def rdf_description_to_dict(desc):
    d={}
    for k,v in desc.attrib.items():
        if k.startswith("{"+NS_RDF+"}") or k.startswith("{http://www.w3.org/XML/1998/namespace}"):
            continue
        if k.startswith("{"):
            ns,ln=k[1:].split("}",1)
            d["%s:%s"%(ns,ln)]=v
        else:
            d[k]=v
    for ch in list(desc):
        ns=""
        nm=ch.tag
        if ch.tag.startswith("{"):
            ns,nm=ch.tag[1:].split("}",1)
        key="%s:%s"%(ns,nm)
        d[key]=xmp_semantic_value(ch)
    return d

def find_xmp_values(root,ns,prop):
    if root is None: return []
    vals=[]
    for desc in xmp_descriptions(root):
        av=desc.attrib.get(qn(ns,prop))
        if av is not None: vals.append(av)
        for node in desc.findall(qn(ns,prop)):
            vals.extend(extract_property_values(node))
    # Keep the existing text-comparison API for other properties. Identity
    # strings bypass it; the semantic extractor itself retains raw values.
    preserve_identity=ns==NS_PDFMAILMETA and prop=="Mail_GUID"
    return [norm_text(v) if isinstance(v,str) and not isinstance(v,XmpUri) and not preserve_identity else v
            for v in vals if v not in (None,"") and
            (preserve_identity or not isinstance(v,str) or isinstance(v,XmpUri) or norm_text(v))]

def extract_property_values(node):
    value=xmp_semantic_value(node)
    return list(value) if isinstance(value,XmpArray) else [value]

def first_xmp_value(root,ns,prop):
    vals=find_xmp_values(root,ns,prop)
    return vals[0] if vals else ""

def collect_first_text_property(root,ns,prop):
    vals=find_xmp_values(root,ns,prop)
    for v in vals:
        if isinstance(v,str):
            t=norm_text(v)
            if t:
                return t
            continue
        if isinstance(v,dict):
            t=norm_text(v.get("text",""))
            if t:
                return t
            continue
        if isinstance(v,list):
            for item in v:
                if isinstance(item,str):
                    t=norm_text(item)
                    if t:
                        return t
                elif isinstance(item,dict):
                    t=norm_text(item.get("text",""))
                    if t:
                        return t
    return ""

def xmp_default_language_values(root,ns,prop):
    """Read explicit x-default Text items without certifying XMP serialization."""
    if root is None:
        return []
    languages={}
    pending=[(root,"")]
    while pending:
        node,inherited=pending.pop()
        language=node.get("{http://www.w3.org/XML/1998/namespace}lang",inherited)
        languages[node]=language
        pending.extend((child,language) for child in node)

    def value_node(node):
        while True:
            if len(node)==1 and node[0].tag==qn(NS_RDF,"Description"):
                node=node[0]
            elif node.find(qn(NS_RDF,"value")) is not None:
                node=node.find(qn(NS_RDF,"value"))
            else:
                return node

    values=[]
    for property_node in xmp_property_nodes(root,ns,prop):
        node=value_node(property_node)
        if len(node)!=1 or node[0].tag!=qn(NS_RDF,"Alt"):
            continue
        for item in node[0].findall(qn(NS_RDF,"li")):
            value=xmp_semantic_value(item)
            if languages.get(value_node(item))=="x-default" and is_xmp_text(value):
                values.append(value)
    return values

def collect_alt_xdefault(root):
    return collect_first_text_property(root,NS_DC,"title")

def flatten_assets(root):
    vals=find_xmp_values(root,NS_PDFMAILMETA,"assets")
    out=[]
    for v in vals:
        if isinstance(v,list):
            for item in v:
                if isinstance(item,dict): out.append(normalize_asset_record(item))
        elif isinstance(v,dict):
            out.append(normalize_asset_record(v))
    return out

def flatten_attachments(root):
    vals=find_xmp_values(root,NS_PDFMAILMETA,"attachments")
    out=[]
    for v in vals:
        if isinstance(v,list):
            for item in v:
                if isinstance(item,dict): out.append(normalize_attachment_record(item))
        elif isinstance(v,dict):
            out.append(normalize_attachment_record(v))
    return out

def flatten_email_records(root):
    vals=find_xmp_values(root,NS_PDFMAILMETA,"email")
    out=[]
    for v in vals:
        if isinstance(v,list):
            for item in v:
                if isinstance(item,dict):
                    out.append(normalize_email_record(item))
        elif isinstance(v,dict):
            out.append(normalize_email_record(v))
    return out

def extract_foaf_agents(root,prop):
    vals=find_xmp_values(root,NS_PDFMAILMETA,prop)
    agents=[]
    for v in vals:
        items=v if isinstance(v,list) else [v]
        for item in items:
            agents.extend(normalize_foaf_agents(item))
    return agents

def normalize_asset_record(d):
    if "children" in d:
        for child in d.get("children", []):
            if isinstance(child, dict) and child.get("name") == "Asset":
                d = child
                break
    out={}
    for k,v in d.items():
        if k == "children" and isinstance(v, list):
            for child in v:
                if not isinstance(child, dict):
                    continue
                name = norm_text(child.get("name", ""))
                value = child.get("value", "")
                if name:
                    out[name] = value
            continue
        lk=k[len(NS_PDFMAILMETA)+1:] if k.startswith(NS_PDFMAILMETA+":") else k
        out[lk]=v
    return out

def normalize_attachment_record(d):
    if "children" in d:
        for child in d.get("children", []):
            if isinstance(child, dict) and child.get("name") == "Attachment":
                d = child
                break
    out={}
    for k,v in d.items():
        if k == "children" and isinstance(v, list):
            for child in v:
                if not isinstance(child, dict):
                    continue
                name=norm_text(child.get("name",""))
                value=child.get("value","")
                if name:
                    out[name]=value
            continue
        lk=k[len(NS_PDFMAILMETA)+1:] if k.startswith(NS_PDFMAILMETA+":") else k
        out[lk]=v
    return out

def normalize_email_record(d):
    if "children" in d:
        for child in d.get("children", []):
            if isinstance(child, dict) and child.get("name") == "Email":
                d = child
                break
    out={}
    for k,v in d.items():
        if k == "children" and isinstance(v, list):
            for child in v:
                if not isinstance(child, dict):
                    continue
                name=norm_text(child.get("name",""))
                value=child.get("value","")
                if name:
                    if isinstance(value, list):
                        out[name]=value
                    elif child.get("children"):
                        out[name]={"children":child.get("children",[]),"text":child.get("text","")}
                    else:
                        out[name]=value
            continue
        lk=k[len(NS_PDFMAILMETA)+1:] if k.startswith(NS_PDFMAILMETA+":") else k
        out[lk]=v
    if "Message-ID" in out and "messageid" not in out:
        out["messageid"]=out["Message-ID"]
    if "Format" in out and "ContentType" not in out:
        out["ContentType"]=out["Format"]
    return out

def normalize_foaf_agents(item):
    if not isinstance(item,dict):
        return []
    base=item
    children=item.get("children",[])
    for child in children:
        if isinstance(child,dict) and child.get("name")=="Agent":
            base=child
            break
    d={}
    if isinstance(base,dict):
        for k,v in base.items():
            lk=k[len(NS_FOAF)+1:] if k.startswith(NS_FOAF+":") else k
            d[lk]=v
        for child in base.get("children",[]):
            if not isinstance(child,dict):
                continue
            name=norm_text(child.get("name",""))
            if name:
                d[name]=child.get("value","")
    mbox=norm_text(first_present(d,f"{NS_FOAF}:mbox","mbox"))
    original_uri=None
    uri_error=None
    if mbox.lower().startswith("mailto:"):
        original_uri=mbox
        try:
            if re.search(r"%(?![0-9A-Fa-f]{2})",mbox):
                raise ValueError("Invalid percent escape")
            # RFC6068: decode once, without treating '+' as form whitespace.
            mbox="mailto:"+unquote(mbox[7:],encoding="utf-8",errors="strict")
        except (UnicodeError,ValueError) as error:
            uri_error=str(error)
    elif mbox:
        # Retain the established bare addr-spec representation as literal text.
        mbox="mailto:"+mbox
    agent={
        "name":norm_text(first_present(d,f"{NS_FOAF}:name","name")),
        "mbox":mbox,
    }
    if original_uri is not None and original_uri!=mbox:
        agent["mbox_uri"]=original_uri
    if uri_error:
        agent["mbox_uri_error"]=uri_error
    return [agent] if agent["name"] or agent["mbox"] else []

def first_message_xmp_value(root, prop, *aliases):
    val=first_xmp_value(root,NS_PDFMAILMETA,prop)
    if val:
        return val
    for alias in aliases:
        val=first_xmp_value(root,NS_PDFMAILMETA,alias)
        if val:
            return val
    emails=flatten_email_records(root)
    for email in emails:
        val=first_present(email, prop, *aliases)
        if val not in ("",None):
            return val
    return ""

def message_xmp_property_present(root,prop):
    return bool(xmp_document_property_values(root,NS_PDFMAILMETA,prop)) or any(
        prop in record for record in flatten_email_records(root))

def extract_message_foaf_agents(root, prop):
    agents=extract_foaf_agents(root,prop)
    if agents:
        return agents
    emails=flatten_email_records(root)
    for email in emails:
        value=email.get(prop)
        if value is None:
            continue
        items=value if isinstance(value,list) else [value]
        out=[]
        for item in items:
            out.extend(normalize_foaf_agents(item))
        if out:
            return out
    return []

def normalize_path_tail(s):
    # Source metadata may contain paths, but whitespace is filename data.
    # URI/array values cannot provide normal-string filename evidence.
    s=(s if is_xmp_text(s) else "").replace("\\","/")
    return s.split("/")[-1] if "/" in s else s

def source_file_name(fileinfo):
    for key in ("raw_ufilename","raw_filename","ufilename","filename","tree_name"):
        value=fileinfo.get(key)
        if is_xmp_text(value) and value:
            return value
    return ""

def clean_header_text(s):
    return norm_text(str(s).replace("\x00",""))

def first_present(d,*names):
    for name in names:
        if name in d and d[name] not in (None,""):
            return d[name]
    return ""

def first_scalar(value):
    if isinstance(value, list):
        for item in value:
            if isinstance(item, str) and norm_text(item):
                return norm_text(item)
            if item not in (None, "", [], {}):
                return norm_text(str(item))
        return ""
    return norm_text(value)

def nonnegative_integer_digits(value):
    if isinstance(value,(XmpUri,bool)) or not isinstance(value,(str,int,pikepdf.String)):
        return None
    text=str(value).strip(" \t\r\n")
    if not re.fullmatch(r"[+-]?[0-9]+",text):
        return None
    digits=text.lstrip("+-").lstrip("0") or "0"
    return None if text.startswith("-") and digits!="0" else digits

def is_nonneg_int_str(v):
    return nonnegative_integer_digits(v) is not None

def nonnegative_integer_equals(value,expected):
    digits=nonnegative_integer_digits(value)
    return digits is not None and digits==nonnegative_integer_digits(expected)

def looks_like_xmp_datetime(v):
    if not is_xmp_text(v):
        return False
    # Table 18 explicitly uses xsd:dateTime, not XMP's reduced-precision Date.
    # Validate its lexical/value space without Python datetime's year limit or
    # inventing a timezone for a value whose offset is unknown.
    match=re.fullmatch(r"(-?)([0-9]{4,})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.([0-9]+))?(?:Z|[+-]([0-9]{2}):([0-9]{2}))?",v.strip(" \t\r\n"))
    if match is None:
        return False
    _,year,month,day,hour,minute,second,fraction,zone_hour,zone_minute=match.groups()
    if not year.strip("0") or (len(year)>4 and year.startswith("0")):
        return False
    month,day,hour,minute,second=map(int,(month,day,hour,minute,second))
    # The Gregorian leap cycle is 400 years, also for legal negative years.
    cycle_year=int(year[-4:])%400 or 400
    if not (1<=month<=12 and 1<=day<=monthrange(cycle_year,month)[1]
            and hour<=24 and minute<=59 and second<=59):
        return False
    if hour==24 and (minute or second or (fraction and fraction.strip("0"))):
        return False
    if zone_hour is not None:
        hours,minutes=int(zone_hour),int(zone_minute)
        if hours>14 or minutes>59 or (hours==14 and minutes):
            return False
    return True

def parse_xmp_datetime(v):
    s=norm_text(v)
    if not s:
        return None
    try:
        if re.search(r"[+-]\d{2}:\d{2}$",s) and int(s[-2:])>59:
            return None
        if s.endswith("Z"):
            s=s[:-1] + "+00:00"
        if re.search(r"T24:00:00(?:\.0+)?[+-]",s):
            return datetime.fromisoformat(s.replace("T24:","T00:",1))+timedelta(days=1)
        return parsedate_to_datetime(s) if "," in s else datetime.fromisoformat(s)
    except Exception:
        return None

def looks_like_pdf_date(value):
    # ISO 32000 7.9.4: test decoded text without trimming or normalizing it.
    # Local fields can have reduced precision; the example includes an offset
    # after minutes. Numeric offset fields can also be omitted (default zero).
    try:
        text=str(value)
    except (UnicodeError,ValueError,pikepdf.PdfError):
        return False  # Unreadable text cannot satisfy the prescribed date grammar.
    match=re.fullmatch(r"D:([0-9]{4}(?:[0-9]{2}){0,5})(?:([Z+-])(?:([0-9]{2})(?:'(?:([0-9]{2})'?)?)?)?)?",text)
    if match is None:
        return False
    local,zone,offset_hour,offset_minute=match.groups()
    year=int(local[:4])
    fields=[int(local[index:index+2]) if len(local)>index else default
            for index,default in ((4,1),(6,1),(8,0),(10,0),(12,0))]
    month,day,hour,minute,second=fields
    if not (1<=month<=12 and 1<=day<=monthrange(year,month)[1]
            and hour<=23 and minute<=59 and second<=59):
        return False
    hours,minutes=int(offset_hour or 0),int(offset_minute or 0)
    # PDF 2.0 notes 2/3 permit the old trailing apostrophe and Z zero offsets.
    return hours<=23 and minutes<=59 and (zone!="Z" or hours==minutes==0)

def parse_pdf_datetime(v):
    s=norm_text(v)
    if not s:
        return None
    if s.startswith("D:"):
        s=s[2:]
    m=re.fullmatch(r"(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?(?:([Zz])|([+-])(\d{2})'?(\d{2})'?)?", s)
    if not m:
        return None
    year=int(m.group(1))
    month=int(m.group(2) or "1")
    day=int(m.group(3) or "1")
    hour=int(m.group(4) or "0")
    minute=int(m.group(5) or "0")
    second=int(m.group(6) or "0")
    try:
        tzinfo=None
        if m.group(7):
            tzinfo=timezone.utc
        elif m.group(8):
            sign=1 if m.group(8)=="+" else -1
            offset_hours=int(m.group(9) or "0")
            offset_minutes=int(m.group(10) or "0")
            if offset_hours>23 or offset_minutes>59:
                return None
            tzinfo=timezone(sign * timedelta(hours=offset_hours, minutes=offset_minutes))
        return datetime(year, month, day, hour, minute, second, tzinfo=tzinfo)
    except Exception:
        return None

def text_values_match(a, b):
    return norm_text(a)==norm_text(b)

def datetime_values_match(docinfo_value, xmp_value):
    doc_dt=parse_pdf_datetime(docinfo_value)
    xmp_dt=parse_xmp_datetime(xmp_value)
    if doc_dt is not None and xmp_dt is not None:
        doc_cmp=doc_dt.replace(microsecond=0)
        xmp_cmp=xmp_dt.replace(microsecond=0)
        if doc_cmp.tzinfo is not None and xmp_cmp.tzinfo is not None:
            return doc_cmp.astimezone(timezone.utc)==xmp_cmp.astimezone(timezone.utc)
        return doc_cmp.replace(tzinfo=None)==xmp_cmp.replace(tzinfo=None)
    return norm_text(docinfo_value)==norm_text(xmp_value)

def norm_compare_text(s):
    t=norm_text(s).lower()
    t=t.replace('"',"").replace("'","").replace("<"," ").replace(">"," ")
    return " ".join(t.split())

def value_looks_like_mailbox_identity(s):
    t=norm_text(s)
    if not t:
        return False
    return ("@" in t) or ("<" in t and ">" in t) or t.lower().startswith("mailto:")

def value_matches_source_sender(s, summary):
    t=norm_compare_text(s)
    if not t or not summary:
        return False
    candidates=set()
    candidates.add(norm_compare_text(summary.get("raw_from","")))
    for agent in summary.get("from_agents",[]):
        candidates.add(norm_compare_text(agent.get("name","")))
        candidates.add(norm_compare_text(agent.get("mbox","")))
    candidates={c for c in candidates if c}
    if t in candidates:
        return True
    for c in candidates:
        if c and c in t:
            return True
    return False

def read_metadata_stream(pdf):
    root=pdf.Root
    meta=dict_get(root,"/Metadata",None)
    if meta is None: return b""
    try: return read_stream(meta, stream_setting("VALIDATOR_MAX_METADATA_BYTES", 16 * 1024**2))
    except DecodeLimitError: raise
    except Exception: return b""

def get_declared_pdf_version(pdf):
    hdr=str(pdf.pdf_version)
    root_ver=dict_get(pdf.Root,"/Version",None)
    rv=safe_name(root_ver) if isinstance(root_ver,pikepdf.Name) else ""
    return hdr,rv

def effective_pdf_version(header,catalog):
    if not catalog:
        return header
    if re.fullmatch(r"[0-9]\.[0-9]",header) and re.fullmatch(r"[0-9]\.[0-9]",catalog):
        return max(header,catalog)
    return catalog

def read_docinfo(pdf):
    try:
        info=pdf.docinfo
    except Exception:
        info=None
    out={}
    if not info: return out
    for k,v in info.items():
        try: out[safe_name(k)]=str(v)
        except Exception:
            try: out[safe_name(k)]=safe_name(v)
            except Exception: out[safe_name(k)]=""
    return out

def get_embedded_files(pdf):
    root=pdf.Root
    files=[]
    names=dict_get(root,"/Names",None)
    if names is not None:
        ef=dict_get(names,"/EmbeddedFiles",None)
        if ef is not None:
            pending=[(ef,0)]
            seen=set()
            while pending:
                node,depth=pending.pop()
                visit_tree_node(node,seen,len(seen),depth,"EmbeddedFiles name")
                arr=dict_get(node,"/Names",None)
                if arr is not None:
                    if not is_pdf_array(arr) or len(arr)%2:
                        raise ValueError("Malformed EmbeddedFiles name-tree entries")
                    for index in range(0,len(arr),2):
                        fileinfo=parse_filespec(str(arr[index]),arr[index+1],source="EmbeddedFiles")
                        fileinfo["tree_name_is_text"]=isinstance(arr[index],pikepdf.String)
                        files.append(fileinfo)
                pending.extend((child,depth+1) for child in (dict_get(node,"/Kids",[]) or []))
    return files

def reconstruct_embedded_media_type(stream):
    """EA-PDF 9.4: decoded UTF-8 name plus the decoded PDF parameter string.

    pikepdf already removes name # escapes. bytes(name) preserves the decoded
    bytes, allowing strict UTF-8 decoding without replacing invalid sequences.
    This does not duplicate veraPDF's bare MIME syntax/presence predicates.
    """
    subtype=dict_get(stream,"/Subtype",None)
    if not isinstance(subtype,pikepdf.Name):
        raise ValueError("Reconstruction requires a Subtype PDF name")
    bare=bytes(subtype)[1:].decode("utf-8")
    parameters=dict_get(stream,"/Mail_MediaTypeParameters",None)
    if parameters is None:
        return bare+";"
    if not isinstance(parameters,pikepdf.String):
        raise ValueError("Mail_MediaTypeParameters must be a PDF text string")
    return bare+";"+str(parameters)

def parse_filespec(tree_name,spec,source="unknown"):
    count_file()
    info={"tree_name":tree_name,"source":source,"objgen":objgen(spec),"filename":"","ufilename":"","afrelationship":"","desc":"","stream":None,"subtype":"","media_type_parameters":"","size":None,"size_entry":None,"checksum":"","bytes":b"","sha256":"","md5":"","ef_objgen":None}
    info["filename"]=norm_text(str(dict_get(spec,"/F",""))) if dict_get(spec,"/F",None) is not None else ""
    info["ufilename"]=norm_text(str(dict_get(spec,"/UF",""))) if dict_get(spec,"/UF",None) is not None else ""
    # Preserve decoded filename text for attachment identity. Display cleanup
    # is many-to-one and must not establish a payload association.
    info["raw_filename"]=str(dict_get(spec,"/F","")) if dict_get(spec,"/F",None) is not None else ""
    info["raw_ufilename"]=str(dict_get(spec,"/UF","")) if dict_get(spec,"/UF",None) is not None else ""
    info["desc"]=norm_text(str(dict_get(spec,"/Desc",""))) if dict_get(spec,"/Desc",None) is not None else ""
    info["collection_item"]=dict_get(spec,"/CI",None) is not None
    rel=dict_get(spec,"/AFRelationship",None)
    if rel is not None: info["afrelationship"]=safe_name(rel)
    ef=dict_get(spec,"/EF",None)
    stream=None
    if ef is not None:
        for key in ("/UF","/F","/Unix","/DOS","/Mac"):
            if dict_get(ef,key,None) is not None:
                stream=dict_get(ef,key,None)
                break
        if stream is None:
            try:
                ks=list(ef.keys())
                if ks: stream=ef[ks[0]]
            except Exception:
                stream=None
    info["stream"]=stream
    embedded_streams=[]
    seen_streams=set()
    for key in ("/UF","/F","/Unix","/DOS","/Mac"):
        candidate=dict_get(ef,key,None)
        if candidate is None:
            continue
        identity=pdf_object_identity(candidate)
        if identity not in seen_streams:
            seen_streams.add(identity)
            embedded_streams.append((key,candidate))
    if not embedded_streams and stream is not None:
        embedded_streams.append(("selected",stream))
    info["embedded_streams"]=embedded_streams
    info["ef_objgen"]=objgen(stream) if stream is not None else None
    if stream is not None:
        st=dict_get(stream,"/Subtype",None)
        if st is not None: info["subtype"]=safe_name(st)
        sz=dict_get(stream,"/Size",None)
        params=dict_get(stream,"/Params",None)
        checksum_parameter=dict_get(params,"/CheckSum",None)
        info["checksum_parameter_present"]=checksum_parameter is not None
        info["checksum_parameter_valid"]=isinstance(checksum_parameter,pikepdf.String) and len(bytes(checksum_parameter))==16
        mtp=dict_get(stream,"/Mail_MediaTypeParameters",None)
        if isinstance(mtp,pikepdf.String):
            info["media_type_parameters"]=str(mtp)
        try:
            info["reconstructed_media_type"]=reconstruct_embedded_media_type(stream)
            info["media_type_reconstruction_error"]=None
        except (ValueError,UnicodeError) as error:
            info["reconstructed_media_type"]=None
            info["media_type_reconstruction_error"]=str(error)
        if sz is None and params is not None:
            sz=dict_get(params,"/Size",None)
        if sz is not None:
            try:
                info["size_entry"]=int(sz)
                info["size"]=int(sz)
            except Exception:
                info["size_entry"]=None
                info["size"]=None
        cs=checksum_parameter
        if cs is None:
            cs=dict_get(stream,"/CheckSum",None)
        if cs is None:
            cs=dict_get(stream,"/Checksum",None)
        if cs is None and params is not None:
            cs=dict_get(params,"/CheckSum",None)
        if cs is None and params is not None:
            cs=dict_get(params,"/Checksum",None)
        if cs is not None: info["checksum"]=normalize_pdf_checksum(cs)
        b=get_stream_bytes(stream)
        info["bytes"]=b
        info["md5"]=md5hex(b)
        info["sha256"]=sha256hex(b)
        info["size"]=len(b)
    return info

def page_objgen_map(pdf):
    out={}
    for i,page in enumerate(pdf.pages):
        try:
            og=objgen(page.obj)
            if og is not None:
                out[og]=i+1
        except Exception:
            pass
    return out

def pdf_object_identity(value):
    identity=objgen(value)
    return identity if identity and identity!=(0,0) else ("direct",id(value))

def resolved_content_properties(operands,resources):
    if len(operands)<2:
        return None
    properties=operands[1]
    if isinstance(properties,pikepdf.Name):
        properties=dict_get(dict_get(resources,"/Properties",None),str(properties),None)
    return properties

def iter_content_instructions(page):
    active_forms=set()
    count=0
    def walk(stream,resources,depth):
        nonlocal count
        if depth>MAX_TREE_DEPTH:
            raise ValueError("Excessively deep Form XObject content")
        for instruction_index,instruction in enumerate(pikepdf.parse_content_stream(stream)):
            count+=1
            if count>MAX_TREE_ITEMS:
                raise ValueError("Excessively large PDF content stream")
            operator=str(instruction.operator)
            operands=list(instruction.operands) if hasattr(instruction,"operands") else []
            event={"operator":operator,"operands":operands,"resources":resources,"instruction_index":instruction_index,
                   "scope":page.obj if isinstance(stream,pikepdf.Page) else stream,"page":page.obj}
            yield event
            if operator=="Do" and operands:
                form=dict_get(dict_get(resources,"/XObject",None),str(operands[0]),None)
                if safe_name(dict_get(form,"/Subtype",None))!="Form":
                    continue
                identity=pdf_object_identity(form)
                if identity in active_forms:
                    raise ValueError("Cyclic Form XObject content")
                active_forms.add(identity)
                yield dict(event,operator="FORM_BEGIN",scope=form)
                yield from walk(form,dict_get(form,"/Resources",resources),depth+1)
                yield dict(event,operator="FORM_END",scope=form)
                active_forms.remove(identity)
    yield from walk(page,dict_get(page.obj,"/Resources",None),0)

def text_operands_have_content(operands):
    for operand in operands:
        if isinstance(operand,pikepdf.String) and str(operand).strip():
            return True
        if is_pdf_array(operand) and text_operands_have_content(list(operand)):
            return True
    return False

def analyze_page_content(page):
    stack=[]
    forms=[]
    text_open=False
    sequences=[]
    points=[]
    visible=False
    has_text=False
    form_objects={}
    paint_coverage={"structured":0,"artifact":0,"unmarked":0}
    def record_paint():
        if any(sequence["tag"]=="Artifact" for sequence in stack):
            kind="artifact"
        elif any(isinstance(sequence["mcid"],int) and not isinstance(sequence["mcid"],bool)
                 and sequence["mcid"]>=0 for sequence in stack):
            kind="structured"
        else:
            kind="unmarked"
        paint_coverage[kind]+=1
    for event in iter_content_instructions(page):
        operator,operands=event["operator"],event["operands"]
        if operator=="FORM_BEGIN":
            form_objects[pdf_object_identity(event["scope"])]=event["scope"]
            forms.append((len(stack),text_open))
            text_open=False
        elif operator=="FORM_END":
            expected_depth,previous_text=forms.pop()
            if len(stack)!=expected_depth or text_open:
                raise ValueError("Unbalanced marked content or text in Form XObject")
            text_open=previous_text
        elif operator in ("BMC","BDC"):
            if len(operands)!=(1 if operator=="BMC" else 2) or not isinstance(operands[0],pikepdf.Name):
                raise ValueError("Marked-content sequence lacks a tag name")
            properties=resolved_content_properties(operands,event["resources"])
            if operator=="BDC" and not isinstance(properties,pikepdf.Dictionary):
                raise ValueError("Marked-content properties cannot be resolved")
            sequence=dict(event,tag=safe_name(operands[0]),properties=properties,
                          mcid=dict_get(properties,"/MCID",None),has_text_object=False)
            sequences.append(sequence)
            if len(stack)>=MAX_TREE_DEPTH:
                raise ValueError("Excessively deep marked-content nesting")
            stack.append(sequence)
        elif operator=="EMC":
            minimum=forms[-1][0] if forms else 0
            if len(stack)<=minimum:
                raise ValueError("Marked-content end has no matching sequence")
            stack.pop()
        elif operator in ("MP","DP"):
            properties=resolved_content_properties(operands,event["resources"])
            if len(operands)!=(1 if operator=="MP" else 2) or not isinstance(operands[0],pikepdf.Name) or (operator=="DP" and not isinstance(properties,pikepdf.Dictionary)):
                raise ValueError("Marked-content point has invalid operands or unresolved properties")
            points.append(dict(event,tag=safe_name(operands[0]),properties=properties))
        elif operator=="BT":
            if text_open:
                raise ValueError("Nested PDF text object")
            text_open=True
        elif operator=="ET":
            if not text_open:
                raise ValueError("PDF text end has no matching start")
            text_open=False
        elif operator in ("Tj","TJ","'",'"') and text_open and text_operands_have_content(operands):
            visible=True
            has_text=True
            record_paint()
            for sequence in stack:
                sequence["has_text_object"]=True
        elif operator in ("Do","INLINE IMAGE","BI","sh","S","s","f","F","f*","B","B*","b","b*"):
            visible=True
            target=dict_get(dict_get(event["resources"],"/XObject",None),str(operands[0]),None) if operator=="Do" and operands else None
            # Form paint is assessed inside the form; an unmarked invocation
            # can legitimately invoke content with its own structure MCIDs.
            if safe_name(dict_get(target,"/Subtype",None))!="Form":
                record_paint()
    if stack or forms or text_open:
        raise ValueError("Unclosed marked-content sequence or PDF text object")
    return {"sequences":sequences,"points":points,"visible":visible,"has_text_object":has_text,"forms":form_objects,"paint_coverage":paint_coverage}

def page_contents_nonempty(page):
    try:
        contents=dict_get(page.obj,"/Contents",None)
        if contents is None:
            return False
        if isinstance(contents,pikepdf.Stream):
            return len(get_stream_bytes(contents).strip())>0
        if is_pdf_array(contents) or isinstance(contents,list):
            total=0
            for item in contents:
                if isinstance(item,pikepdf.Stream):
                    total+=len(get_stream_bytes(item).strip())
            return total>0
    except Exception:
        return False
    return False

def page_has_visibleish_content(page):
    if analyze_page_content(page)["visible"]:
        return True
    annots=dict_get(page.obj,"/Annots",None)
    if annots is not None and len(annots)>0:
        return True
    return False

def emit_if_unseen(report, level, code, message, details=None):
    if code in report.seen_codes:
        return
    getattr(report, level)(code, message, details)

def iter_page_content_bytes(page):
    try:
        contents=dict_get(page.obj,"/Contents",None)
        if contents is None:
            return []
        if isinstance(contents,pikepdf.Stream):
            return [get_stream_bytes(contents)]
        if is_pdf_array(contents) or isinstance(contents,list):
            out=[]
            for item in contents:
                if isinstance(item,pikepdf.Stream):
                    out.append(get_stream_bytes(item))
            return out
    except Exception:
        pass
    return []

def page_has_text_objects(page):
    return analyze_page_content(page)["has_text_object"]

def page_custom_marked_content(page):
    content=analyze_page_content(page)
    point_tags=[item["tag"] for item in content["points"] if item["tag"].startswith("Mail_")]
    sequence_tags=[item["tag"] for item in content["sequences"] if item["tag"].startswith("Mail_")]
    return {
        "all_tags":sequence_tags+point_tags,
        "point_tags":point_tags,
        "sequence_tags":sequence_tags,
    }

def page_marked_content_sequences(page, wanted_tags):
    return [{"tag":item["tag"],"has_text_object":item["has_text_object"]}
            for item in analyze_page_content(page)["sequences"] if item["tag"] in wanted_tags]

def check_custom_tag_operands(pdf,report):
    return _structure_rules.check_custom_tag_operands(_structure_rules_dependencies(), pdf, report)

def parse_rect_coords(value):
    if value is None:
        return None
    try:
        vals=[float(value[i]) for i in range(4)]
    except Exception:
        try:
            vals=[float(v) for v in value[:4]]
        except Exception:
            return None
    if len(vals)!=4:
        return None
    left=min(vals[0],vals[2])
    right=max(vals[0],vals[2])
    bottom=min(vals[1],vals[3])
    top=max(vals[1],vals[3])
    return [left,bottom,right,top]

def page_effective_box(page):
    try:
        crop=page.cropbox
        rect=[float(crop.left),float(crop.bottom),float(crop.right),float(crop.top)]
        return parse_rect_coords(rect)
    except Exception:
        pass
    try:
        media=page.mediabox
        rect=[float(media.left),float(media.bottom),float(media.right),float(media.top)]
        return parse_rect_coords(rect)
    except Exception:
        pass
    crop=dict_get(page.obj,"/CropBox",None)
    if crop is not None:
        rect=parse_rect_coords(crop)
        if rect is not None:
            return rect
    media=dict_get(page.obj,"/MediaBox",None)
    return parse_rect_coords(media)

def rect_is_within_box(rect, box, eps=0.01):
    if rect is None or box is None:
        return False
    if rect[2] <= rect[0] or rect[3] <= rect[1]:
        return False
    return (
        rect[0] >= box[0]-eps and
        rect[1] >= box[1]-eps and
        rect[2] <= box[2]+eps and
        rect[3] <= box[3]+eps
    )

def annotation_visibility_ok(annot):
    try:
        flags=int(dict_get(annot,"/F",0) or 0)
    except Exception:
        flags=0
    invisible=bool(flags & 1)
    hidden=bool(flags & 2)
    no_view=bool(flags & 32)
    return not (invisible or hidden or no_view), flags

def collect_file_attachment_annotations(pdf):
    out=[]
    for idx,page in enumerate(pdf.pages, start=1):
        annots=dict_get(page.obj,"/Annots",None)
        if annots is None:
            continue
        for annot in annots:
            subtype=safe_name(dict_get(annot,"/Subtype",None))
            if subtype!="FileAttachment":
                continue
            fs=dict_get(annot,"/FS",None)
            fs_info=parse_filespec("",fs,source="Annotation") if fs is not None else None
            rect=parse_rect_coords(dict_get(annot,"/Rect",None))
            page_box=page_effective_box(page)
            visible_ok, flags=annotation_visibility_ok(annot)
            contents_value=dict_get(annot,"/Contents",None)
            contents_unavailable=False
            try:
                contents=str(contents_value) if isinstance(contents_value,pikepdf.String) else ""
            except (UnicodeError,ValueError,pikepdf.PdfError):
                contents=None
                contents_unavailable=True
            out.append({
                "page":idx,
                "objgen":objgen(annot),
                "filename":norm_text(str(dict_get(fs,"/UF",dict_get(fs,"/F","")))) if fs is not None else "",
                "contents":norm_text(contents),
                "contents_is_text":isinstance(contents_value,pikepdf.String),
                "contents_unavailable":contents_unavailable,
                "rect":rect,
                "page_box":page_box,
                "within_page_box":rect_is_within_box(rect,page_box),
                "visible_ok":visible_ok,
                "flags":flags,
                "fs_objgen":objgen(fs) if fs is not None else None,
                "file":fs_info,
            })
    return out

def check_file_attachment_contents(pdf,report):
    return _embedding_rules.check_file_attachment_contents(_embedding_rules_dependencies(), pdf, report)

def extract_actionable_urls(text):
    if not text:
        return []
    pattern=re.compile(r"\b(?:https?://|mailto:|www\.)[^\s<>()\[\]{}]+", re.I)
    out=[]
    seen=set()
    for m in pattern.finditer(text):
        url=m.group(0).strip()
        url=url.rstrip(".,;:!?)]}>'\"")
        url=url.lstrip("(<[{\"'")
        if not url:
            continue
        key=normalize_url_for_compare(url)
        if key and key not in seen:
            seen.add(key)
            out.append(url)
    return out

def normalize_url_for_compare(url):
    t=norm_text(url).replace(" ", "")
    if not t:
        return ""
    if t.lower().startswith("www."):
        t="http://"+t
    try:
        parsed=urlsplit(t)
    except Exception:
        return t.lower()
    if not parsed.scheme:
        return t.lower()
    netloc=parsed.netloc.lower()
    path=parsed.path or ""
    if path == "/":
        path=""
    elif path.endswith("/"):
        path=path.rstrip("/")
    return urlunsplit((parsed.scheme.lower(), netloc, path, parsed.query, ""))

def url_has_matching_annotation(url, candidates, text):
    normalized=normalize_url_for_compare(url)
    if normalized in candidates:
        return True
    for match in re.finditer(re.escape(url)+r"[ \t]*\r?\n",text):
        continuation="".join(text[match.end():].splitlines()[:2])
        continuation=re.sub(r"\s+","",continuation)
        for candidate in candidates:
            if candidate.startswith(normalized) and candidate!=normalized:
                remainder=candidate[len(normalized):]
                if remainder and remainder in continuation:
                    return True
    return False

def annotation_uri_candidates(uri):
    candidates=set()
    norm=normalize_url_for_compare(uri)
    if norm:
        candidates.add(norm)
    try:
        parsed=urlsplit(norm_text(uri))
        for key,values in parse_qs(parsed.query, keep_blank_values=True).items():
            if key.lower() not in {"url","u","target","redirect","redirect_uri"}:
                continue
            for value in values:
                extra=normalize_url_for_compare(unquote(value))
                if extra:
                    candidates.add(extra)
    except Exception:
        pass
    return candidates

def collect_link_annotations_pypdf(page):
    out=[]
    annots=page.get("/Annots")
    if not annots:
        return out
    for annot_ref in annots:
        try:
            annot=annot_ref.get_object()
        except Exception:
            annot=annot_ref
        subtype=str(annot.get("/Subtype",""))
        if subtype != "/Link":
            continue
        action=annot.get("/A")
        uri=""
        action_type=""
        if action is not None:
            action_type=str(action.get("/S",""))
            if action_type=="/URI":
                uri=str(action.get("/URI",""))
        rect=parse_rect_coords(annot.get("/Rect"))
        out.append({
            "uri":norm_text(uri),
            "uri_norm":normalize_url_for_compare(uri),
            "uri_candidates":sorted(annotation_uri_candidates(uri)),
            "rect":rect,
            "action_type":action_type,
        })
    return out

def extract_scoped_field_text(reader,reference):
    if reference.get("reason"):
        raise ValueError(reference["reason"])
    number=reference.get("page")
    identity=reference.get("scope")
    mcid=reference.get("mcid")
    if not isinstance(mcid,int) or isinstance(mcid,bool) or mcid<0:
        raise ValueError("Field MCID is not a nonnegative integer")
    if not isinstance(number,int) or not 1<=number<=len(reader.pages) or not identity or not isinstance(identity[0],int) or identity[0]<=0:
        raise ValueError("Field content scope/page is unavailable")
    page=reader.pages[number-1]
    scope=reader.get_object(IndirectObject(identity[0],identity[1],reader))
    if str(scope.get("/Type"))=="/Page":
        page_ref=page.indirect_reference
        if (page_ref.idnum,page_ref.generation)!=tuple(identity):
            raise ValueError("Field scope belongs to a different page")
        stream=page.get_contents()
        resources=page.get("/Resources")
    elif str(scope.get("/Subtype"))=="/Form":
        stream=scope
        resources=scope.get("/Resources")
        if resources is None:
            raise ValueError("Form field has no explicit resources; inherited font mapping is not assessed")
    else:
        raise ValueError("Unsupported field content scope")
    resources=resources.get_object() if hasattr(resources,"get_object") else resources
    if not isinstance(resources,DictionaryObject):
        raise ValueError("Field font resources are unavailable")
    content=ContentStream(stream,reader)
    if len(content.operations)>MAX_TREE_ITEMS:
        raise ValueError("Excessively large field content scope")
    stack=[]
    selected=[]
    for operands,operator in content.operations:
        if operator in (b"BMC",b"BDC"):
            properties=operands[1] if operator==b"BDC" and len(operands)>1 else None
            if isinstance(properties,NameObject):
                mapping=resources.get("/Properties",{})
                mapping=mapping.get_object() if hasattr(mapping,"get_object") else mapping
                properties=mapping.get(properties)
            properties=properties.get_object() if hasattr(properties,"get_object") else properties
            marked_id=properties.get("/MCID") if isinstance(properties,DictionaryObject) else None
            stack.append(marked_id if isinstance(marked_id,int) and not isinstance(marked_id,bool) and marked_id>=0 else None)
        elif operator==b"EMC":
            if not stack:
                raise ValueError("Unbalanced field marked content")
            stack.pop()
        if operator in (b"Tj",b"TJ",b"'",b'"',b"Do") and mcid not in stack:
            continue
        selected.append((operands,operator))
    if stack:
        raise ValueError("Unclosed field marked content")
    projected=ContentStream(None,reader);projected.operations=selected
    stream=DecodedStreamObject();stream.set_data(projected.get_data())
    isolated=PageObject();isolated.update(page)
    isolated[NameObject("/Resources")]=resources
    isolated[NameObject("/Contents")]=stream
    text=isolated.extract_text() or ""
    if not text.strip() or "\ufffd" in text:
        raise ValueError("No reliable decoded text was extracted for the field reference")
    return text

def collect_page_text_analysis(path,field_sequences=()):
    reader=PdfReader(path)
    pages=[]
    for idx,page in enumerate(reader.pages, start=1):
        text=page.extract_text() or ""
        lines=[norm_text(line) for line in re.split(r"[\r\n]+", text) if norm_text(line)]
        candidates=[]
        for line in lines[:3]+lines[-3:]:
            if line and line not in candidates:
                candidates.append(line)
        pages.append({
            "page":idx,
            "text":text,
            "lines":lines,
            "candidate_lines":candidates,
            "urls":extract_actionable_urls(text),
            "links":collect_link_annotations_pypdf(page),
        })
    cache={}
    for field in field_sequences:
        references=field.get("content_refs",[])
        row={"field_objgen":field["field_objgen"],"tag":field["tag"],"content_refs":references,"text":None}
        try:
            if not references:
                raise ValueError("Field has no supported content references")
            parts=[]
            for reference in references:
                key=(reference.get("page"),tuple(reference.get("scope",())),reference.get("mcid"))
                if reference.get("reason"):
                    raise ValueError(reference["reason"])
                if key not in cache:
                    cache[key]=extract_scoped_field_text(reader,reference)
                parts.append(cache[key])
            row["text"]=" ".join(parts)
        except ValueError as error:
            row["reason"]=str(error)
        if pages:
            number=next((ref.get("page") for ref in references if isinstance(ref.get("page"),int) and 1<=ref["page"]<=len(pages)),1)
            pages[number-1].setdefault("rendered_fields",[]).append(row)
    return pages

def line_has_page_marker(line, page_num, total_pages):
    t=norm_text(line).lower()
    if not t:
        return False
    patterns=[
        rf"\bpage\s*[:#]?\s*{page_num}\b",
        rf"\bp\.?\s*{page_num}\b",
        rf"\b{page_num}\s*/\s*{total_pages}\b",
        rf"\b{page_num}\s+of\s+{total_pages}\b",
        rf"\bpage\s*{page_num}\s+of\s+{total_pages}\b",
    ]
    return any(re.search(p,t) for p in patterns)

def identity_fields(record):
    if not isinstance(record,dict):
        return {"Mail_GUID":"","Message-ID":""}
    return {
        "Mail_GUID":guid_value(record.get("Mail_GUID","")),
        "Message-ID":norm_text(first_present(record,"Message-ID","MessageID","messageid","Mail_MessageID")),
    }

def identities_match(a,b):
    first=identity_fields(a)
    second=identity_fields(b)
    for field in ("Mail_GUID","Message-ID"):
        if first[field] and second[field]:
            if field=="Mail_GUID":
                return first[field]==second[field]
            return norm_compare_text(first[field])==norm_compare_text(second[field])
    return False

def filename_key(s):
    t=str(s or "").replace("\0", "").replace("\\","/")
    if "/" in t:
        t=t.rsplit("/",1)[-1]
    t=re.sub(r"^[A-Za-z]:", "", t.replace("..", "_"))
    t=re.sub(r"^~", "_", t)
    return re.sub(r"[\x00-\x1f\x7f]", "_", t).strip()

def parse_nonneg_int_value(v):
    try:
        digits=nonnegative_integer_digits(v)
        return int(digits) if digits is not None else None
    except Exception:
        return None

def media_type_signature(value):
    raw=str(value or "")
    headers=MimeHeaders()
    # Header folding is handled by the MIME parser. Whitespace inside a quoted
    # parameter is data and must not be collapsed (nor may its case change).
    headers["Content-Type"]=raw
    bare=raw.split(";",1)[0].strip().lower()
    if not valid_body_media_type(bare):
        # The email parser defaults malformed values to text/plain. A preserved
        # malformed source field must not compare equal through that default.
        return ("unparsed",raw)
    try:
        parsed=policy.default.header_factory("Content-Type",re.sub(r"\r?\n[ \t]+"," ",raw))
        if any(isinstance(defect,InvalidHeaderDefect) for defect in parsed.defects):
            # Recovery may discard malformed parameters or repair their syntax.
            # Such source text is permitted, but equality needs the actual text.
            return ("unparsed",raw)
    except (HeaderParseError,ValueError,IndexError):
        return ("unparsed",raw)
    parameters=[]
    for name,item in (headers.get_params() or [])[1:]:
        if not name:
            continue  # No parameter after an optional trailing semicolon.
        if isinstance(item,tuple):
            item=collapse_rfc2231_value(item)
        if name.lower()=="charset":
            item=item.lower()
        parameters.append((name.lower(),item))
    return headers.get_content_type().lower(),tuple(sorted(parameters))

def valid_body_media_type(value):
    token = r"[!#$%&'*+.^_`|~{}0-9A-Za-z-]+"
    value = re.sub(r"\r?\n[ \t]+", " ", value)
    quoted = r'"(?:[^"\\\r\n\x00-\x1f]|\\[^\r\n])*"'
    return bool(re.fullmatch(token + "/" + token + r"(?:[ \t]*;[ \t]*" + token
                             + r"[ \t]*=[ \t]*(?:" + token + "|" + quoted + r"))*[ \t]*", value))

def attachment_file_names(fileinfo):
    """Actual PDF names, preserving whitespace and other filename characters."""
    for raw,display in (("raw_ufilename","ufilename"),("raw_filename","filename")):
        name=fileinfo.get(raw,fileinfo.get(display,""))
        if isinstance(name,str) and name:
            return {name}
    name=fileinfo.get("tree_name","")
    return {name} if isinstance(name,str) and name else set()

def source_attachment_filename(item):
    return item.get("original_filename",item.get("filename",""))

def source_attachment_filenames(item):
    values=item.get("original_filenames",[source_attachment_filename(item)])
    return {value for value in values if isinstance(value,str) and value}

def attachment_record_matches_annotation(att, annot, association_checksum=None):
    fileinfo=annot.get("file") or {}
    if fileinfo.get("afrelationship") in ("Source","Alternative"):
        return False
    att_name=att.get("Filename","") if is_xmp_text(att.get("Filename")) else ""
    annot_names=attachment_file_names(fileinfo)
    if not annot_names and not any(key in fileinfo for key in ("raw_filename","raw_ufilename","filename","ufilename")):
        # Legacy direct callers can supply the file-spec name on the annotation.
        annot_names={annot.get("filename","")} - {""}
    att_checksum=norm_text(att.get("CheckSum","")).lower()
    att_size_value=first_present(att,"SizeInBytes")
    att_ctype=str(att.get("Content-Type",""))
    file_checksum=norm_text(fileinfo.get("md5","")).lower()
    file_size=fileinfo.get("size")
    declared_size=fileinfo.get("size_entry")
    if declared_size is not None and parse_nonneg_int_value(declared_size)!=file_size:
        return False
    if fileinfo.get("checksum") and norm_text(fileinfo["checksum"]).lower()!=file_checksum:
        return False
    file_ctype=fileinfo.get("reconstructed_media_type")
    if "reconstructed_media_type" not in fileinfo:
        file_ctype=str(fileinfo.get("subtype",""))
        if fileinfo.get("media_type_parameters"):
            file_ctype+=";"+fileinfo["media_type_parameters"]

    name_match=bool(att_name and att_name in annot_names)
    checksum_match=bool(att_checksum and file_checksum and att_checksum==file_checksum)
    source_checksum_match=bool(association_checksum and file_checksum
                               and association_checksum.lower()==file_checksum)
    size_match=(file_size is not None and nonnegative_integer_equals(att_size_value,file_size))
    ctype_match=bool(att_ctype and file_ctype and media_type_signature(att_ctype)==media_type_signature(file_ctype))
    empty_placeholder=(nonnegative_integer_digits(att_size_value)=="0" and file_size==0 and fileinfo.get("bytes")==b"")

    # Table 19 retains the original MIME filename; PDF file-spec names can differ.
    # A stored-payload checksum can establish the association independently.
    if att_name and not (name_match or checksum_match or source_checksum_match or empty_placeholder):
        return False
    if att_checksum and not checksum_match:
        return False
    if "SizeInBytes" in att and not size_match:
        return False
    if att_ctype and not ctype_match:
        return False
    return name_match or checksum_match or source_checksum_match or empty_placeholder or (size_match and (ctype_match or not att_ctype))

def attachment_email_owners(attachment,email_records):
    """Resolve the XMP owner before body routing; keep GUID priority and ID case."""
    guid=guid_value(attachment.get("Mail_GUID",""))
    if guid:
        return [record for record in email_records if guid_value(record.get("Mail_GUID",""))==guid]
    message_id=attachment.get("Message-ID","")
    if is_xmp_text(message_id) and norm_text(message_id):
        return [record for record in email_records
                if is_xmp_text(first_present(record,"messageid","Message-ID"))
                and norm_text(first_present(record,"messageid","Message-ID"))==norm_text(message_id)]
    return list(email_records) if len(email_records)==1 and not any(identity_fields(attachment).values()) else []

def attachment_source_checksum(att,body,email_records,source_summaries,source_bindings=None):
    """Reuse a uniquely selected owner's original payload for alias association.

    This is bounded source-assisted evidence, not original-source completeness
    or occurrence identity. Optional CheckSum absence remains permitted.
    """
    if "CheckSum" in att:
        return None
    owners=attachment_email_owners(body["identity"],email_records)
    if len(owners)!=1 or (any(identity_fields(att).values()) and not identities_match(att,owners[0])):
        return None
    source=source_email_for_record(owners[0],email_records,source_summaries,source_bindings)
    if source is None:
        return None
    filename=att.get("Filename","") if is_xmp_text(att.get("Filename")) else ""
    size=parse_nonneg_int_value(att.get("SizeInBytes"))
    candidates=[item for item in source.get("mime_attachments",source.get("attachments",[]))
                if filename and filename in source_attachment_filenames(item) and size==item.get("size_in_bytes")]
    checksums=set()
    for item in candidates:
        value=item.get("checksum")
        if not isinstance(value,str) or not re.fullmatch(r"[0-9A-Fa-f]{32}",value):
            return None
        checksums.add(value.lower())
    return next(iter(checksums)) if len(checksums)==1 else None

def source_attachment_reference(att,annot,body,source_files,email_records,source_summaries,source_bindings=None):
    fileinfo=annot.get("file") or {}
    if fileinfo.get("afrelationship")!="Source":
        return None
    result={"source_email":fileinfo.get("ufilename") or fileinfo.get("filename"),
            "contained_attachment":first_present(att,"Filename"),"status":"mismatch"}
    source=next((item for item in source_files if item.get("afrelationship")=="Source"
                 and item.get("objgen") not in (None,(0,0)) and item.get("objgen")==fileinfo.get("objgen")),None)
    if source is None or source.get("sha256")!=fileinfo.get("sha256"):
        return dict(result,reason="Annotation does not reference the catalog's embedded source file")
    summaries=summarize_source_emails([source])
    if not summaries:
        return dict(result,status="unverified",reason="Embedded source format cannot be inspected for contained attachments")
    summary=summaries[0]
    filename=att.get("Filename","") if is_xmp_text(att.get("Filename")) else ""
    size=parse_nonneg_int_value(first_present(att,"SizeInBytes"))
    checksum=norm_text(att.get("CheckSum","")).lower()
    content_type=norm_text(att.get("Content-Type","")).split(";",1)[0].lower()
    candidates=[item for item in summary["attachments"]
                if (not source_attachment_filenames(item) or filename in source_attachment_filenames(item))
                and item["size_in_bytes"]==size and (not checksum or item["checksum"]==checksum)
                and item["content_type"].lower()==content_type]
    if not candidates:
        return dict(result,reason="Referenced source email does not contain an attachment matching the declared filename, size, type, and checksum")
    owners=attachment_email_owners(body["identity"],email_records)
    if len(owners)!=1:
        return dict(result,status="unverified",reason="The attachment's owning email cannot be identified unambiguously")
    selected=source_email_for_record(owners[0],email_records,source_summaries,source_bindings)
    if selected is None:
        return dict(result,status="unverified",reason="The owning email cannot be matched unambiguously to an embedded source")
    if selected["source_sha256"]!=summary["source_sha256"]:
        return dict(result,reason="Annotation references another email's embedded source")
    return dict(result,status="verified",size_in_bytes=candidates[0]["size_in_bytes"],
                checksum=candidates[0]["checksum"],reason="Original attachment verified inside the referenced source email; no separate copy is embedded")

def attachment_candidates_for_embedded(f, attachments, source_checksums=None):
    names=attachment_file_names(f)
    md5=norm_text(f.get("md5","")).lower()
    # Payload evidence supports permitted native filename differences. If it
    # cannot identify a record, retain a unique name association even when the
    # supplied size/checksum is wrong: those are values to check, not filters.
    candidates=[att for att in attachments if md5 and md5==norm_text(
        att.get("CheckSum") or (source_checksums or {}).get(id(att),"")).lower()]
    if len(candidates)>1:
        named=[att for att in candidates if is_xmp_text(att.get("Filename")) and att["Filename"] in names]
        if len(named)==1:
            candidates=named
    if not candidates:
        candidates=[att for att in attachments if is_xmp_text(att.get("Filename")) and att["Filename"] in names]
    if not candidates and f.get("size")==0 and f.get("bytes")==b"" and md5==md5hex(b""):
        candidates=[att for att in attachments if "CheckSum" not in att
                    and nonnegative_integer_digits(att.get("SizeInBytes"))=="0"]
    return candidates

def matching_attachment_for_embedded(f, attachments, ambiguities=None, source_checksums=None):
    candidates=attachment_candidates_for_embedded(f,attachments,source_checksums)
    if len(candidates)>1 and ambiguities is not None:
        names=attachment_file_names(f)
        ambiguities.append({"filenames":sorted(names),"candidate_count":len(candidates)})
    return candidates[0] if len(candidates)==1 else None

def attachment_comparison_signature(attachment):
    """Comparable metadata only; owner/occurrence identity is not inferred."""
    size=nonnegative_integer_digits(attachment.get("SizeInBytes"))
    content_type=attachment.get("Content-Type")
    if size is None or not is_xmp_text(content_type) or not norm_text(content_type):
        return None
    checksum=attachment.get("CheckSum")
    if "CheckSum" in attachment:
        if not is_xmp_text(checksum) or not re.fullmatch(r"[0-9A-Fa-f]{32}",checksum):
            return None
        checksum=checksum.lower()
    return size,checksum,media_type_signature(content_type)

def check_attachment_stream_consistency(attachments, files, report, email_records=(), source_summaries=(), ambiguities=None, owner_scopes=None):
    return _embedding_rules.check_attachment_stream_consistency(_embedding_rules_dependencies(), attachments, files, report, email_records, source_summaries, ambiguities, owner_scopes)

def dpart_message_identity(item, by_objgen):
    cur=item
    while cur is not None:
        dpm=cur.get("dpm",{})
        try:
            guid=guid_value(dpm.get("Mail_GUID",""))
        except (UnicodeError,ValueError,pikepdf.PdfError):
            return {"Mail_GUID":"","Message-ID":""}
        ident={"Mail_GUID":guid,"Message-ID":""}
        try:
            ident["Message-ID"]=norm_text(first_present(dpm,"Mail_MessageID"))
        except (UnicodeError,ValueError,pikepdf.PdfError):
            # A readable GUID retains precedence. An unreadable local identity
            # cannot justify falling back to an enclosing email identity.
            return ident
        if ident["Mail_GUID"] or ident["Message-ID"]:
            return ident
        cur=by_objgen.get(cur.get("parent_objgen"))
    return {"Mail_GUID":"","Message-ID":""}

def collect_body_content_sets(pdf, dpart_nodes):
    page_map=page_objgen_map(pdf)
    by_objgen={item["objgen"]:item for item in dpart_nodes if item.get("objgen") is not None}
    out=[]
    for item in dpart_nodes:
        if item.get("start") is None and item.get("end") is None:
            continue
        ctype=safe_name(item.get("dpm",{}).get("Mail_ContentSetType",None))
        if ctype!="BodyRendering":
            continue
        rng=page_range_from_dpart(item,page_map)
        if rng is None:
            continue
        start_idx,end_idx,_s,_e=rng
        out.append({
            "objgen":item.get("objgen"),
            "identity":dpart_message_identity(item,by_objgen),
            "start_page":start_idx,
            "end_page":end_idx,
            "pages":list(range(start_idx,end_idx+1)),
            "subtype":safe_name(item.get("dpm",{}).get("Mail_Subtype",None)),
        })
    return out

def native_attachment_owner_scopes(pdf,email_records):
    """Scope a file only when every annotation context has one verified page owner.

    Names, sizes, checksums and media types play no part in this evidence.
    Unknown or conflicting contexts retain the original global candidate set.
    """
    try:
        _root,_node,nodes=get_dpart_nodes(pdf)
        bodies=collect_body_content_sets(pdf,nodes)
        page_owners=defaultdict(set)
        unknown_pages=set()
        work=0
        for body in bodies:
            owners=attachment_email_owners(body["identity"],email_records)
            for number in body["pages"]:
                work+=1
                if work>MAX_TREE_ITEMS:
                    return {}
                page=pdf.pages[number-1]
                if (len(owners)!=1 or not any(identity_fields(body["identity"]).values())
                        or body.get("objgen") in (None,(0,0))
                        or objgen(dict_get(page.obj,"/DPart",None))!=body["objgen"]):
                    unknown_pages.add(number)
                else:
                    page_owners[number].add(id(owners[0]))
        scopes=defaultdict(set)
        unknown_files=set()
        for number,page in enumerate(pdf.pages,1):
            for annotation in dict_get(page.obj,"/Annots",[]) or []:
                work+=1
                if work>MAX_TREE_ITEMS:
                    return {}
                if safe_name(dict_get(annotation,"/Subtype",None))!="FileAttachment":
                    continue
                key=objgen(dict_get(annotation,"/FS",None))
                if key in (None,(0,0)):
                    continue
                if number in unknown_pages or len(page_owners[number])!=1:
                    unknown_files.add(key)
                else:
                    scopes[key].update(page_owners[number])
        return {key:owners for key,owners in scopes.items() if key not in unknown_files}
    except (ValueError,TypeError,UnicodeError,pikepdf.PdfError):
        return {}

def get_page_ref_objgen(x):
    try:
        if x is None:
            return None
        if safe_name(dict_get(x,"/Type",None))=="Page":
            return objgen(x)
    except Exception:
        pass
    return None

def resolve_outline_dest_page_objgen(pdf,dest):
    try:
        if dest is None:
            return None
        if (is_pdf_array(dest) or isinstance(dest,(list,tuple))) and len(dest)>0:
            return get_page_ref_objgen(dest[0])
        d=dict_get(dest,"/D",None)
        if d is not None:
            return resolve_outline_dest_page_objgen(pdf,d)
    except Exception:
        pass
    return get_page_ref_objgen(dest)

def visit_tree_node(node,seen,count,depth,tree_name):
    identity=objgen(node)
    if not identity or identity==(0,0):
        identity=id(node)
    if depth>MAX_TREE_DEPTH or count>=MAX_TREE_ITEMS or identity in seen:
        raise ValueError(f"Cyclic, repeated, or excessively large {tree_name} tree")
    seen.add(identity)

def walk_outline_items(node,out,seen=None,depth=0):
    if seen is None:
        seen=set()
    cur=node
    while cur is not None:
        visit_tree_node(cur,seen,len(out),depth,"outline")
        out.append(cur)
        first=dict_get(cur,"/First",None)
        if first is not None:
            walk_outline_items(first,out,seen,depth+1)
        cur=dict_get(cur,"/Next",None)

def get_outline_items(pdf):
    outlines=dict_get(pdf.Root,"/Outlines",None)
    if outlines is None:
        return []
    first=dict_get(outlines,"/First",None)
    if first is None:
        return []
    out=[]
    walk_outline_items(first,out)
    return out

def get_outline_page_objgens(pdf):
    out=[]
    for item in get_outline_items(pdf):
        dest=dict_get(item,"/Dest",None)
        if dest is None:
            action=dict_get(item,"/A",None)
            if action is not None:
                dest=dict_get(action,"/D",None)
        pg=resolve_outline_dest_page_objgen(pdf,dest)
        if pg is not None:
            out.append(pg)
    return out

def flatten_dparts_entry(entry,depth=0):
    out=[]
    if entry is None:
        return out
    if is_pdf_array(entry) or isinstance(entry,list):
        if depth>MAX_TREE_DEPTH:
            raise ValueError("Excessively deep DParts arrays")
        for item in entry:
            out.extend(flatten_dparts_entry(item,depth+1))
            if len(out)>MAX_TREE_ITEMS:
                raise ValueError("Excessively large DParts arrays")
        return out
    out.append(entry)
    return out

def parse_dpm_dict(dpm):
    out={}
    if dpm is None:
        return out
    try:
        for k,v in dpm.items():
            out[safe_name(k)]=v
    except Exception:
        pass
    return out

def walk_dpart_nodes(node,out,parent=None,seen=None,depth=0):
    if node is None:
        return
    if seen is None:
        seen=set()
    identity=objgen(node)
    if not identity or identity == (0,0):
        identity=id(node)
    if depth>MAX_TREE_DEPTH or len(out)>=MAX_TREE_ITEMS or identity in seen:
        raise ValueError("Cyclic, repeated, or excessively large DPart tree")
    seen.add(identity)
    kids=flatten_dparts_entry(dict_get(node,"/DParts",None))
    item={
        "node":node,
        "objgen":objgen(node),
        "parent":parent,
        "parent_objgen":objgen(parent) if parent is not None else None,
        "dpm":parse_dpm_dict(dict_get(node,"/DPM",None)),
        "start":dict_get(node,"/Start",None),
        "end":dict_get(node,"/End",None),
        "child_count":len(kids),
    }
    out.append(item)
    for child in kids:
        walk_dpart_nodes(child,out,node,seen,depth+1)

def get_dpart_nodes(pdf):
    root=dict_get(pdf.Root,"/DPartRoot",None)
    if root is None:
        return None,None,[]
    rootnode=dict_get(root,"/DPartRootNode",None)
    out=[]
    if rootnode is not None:
        walk_dpart_nodes(rootnode,out,None)
    return root,rootnode,out

def xmp_value_set(root_xml,*props):
    vals=[]
    if root_xml is not None:
        for prop in props:
            vals.extend(find_xmp_values(root_xml,NS_PDFMAILMETA,prop))
        for email in flatten_email_records(root_xml):
            for prop in props:
                val = first_present(email, prop)
                if val not in ("", None):
                    if isinstance(val, list):
                        vals.extend(v for v in val if v not in ("", None))
                    else:
                        vals.append(val)
    out=set()
    for v in vals:
        if isinstance(v,str):
            t=guid_value(v) if all(prop in {"Mail_GUID","GUID"} for prop in props) else norm_text(v)
            if t:
                out.add(t)
    return out

def page_range_from_dpart(item,page_map):
    s=get_page_ref_objgen(item["start"])
    e=get_page_ref_objgen(item["end"]) if item["end"] is not None else s
    if s not in page_map or e not in page_map:
        return None
    a=page_map[s]
    b=page_map[e]
    if a>b:
        return None
    return a,b,s,e

def is_pdf_ascii_string(value):
    # ASCII string specifies encoded bytes, not merely decoded characters.
    return isinstance(value,pikepdf.String) and all(byte<128 for byte in bytes(value))

def struct_children(elem):
    kids=[]
    k=dict_get(elem,"/K",None)
    if k is None:
        return kids
    entries=k if is_pdf_array(k) or isinstance(k,list) else [k]
    for item in entries:
        try:
            if safe_name(dict_get(item,"/Type",None))=="StructElem" or dict_get(item,"/S",None) is not None:
                kids.append(item)
        except Exception:
            pass
    return kids

def walk_struct_elems(elem,out,parent=None,seen=None,depth=0,class_map=None):
    if seen is None:
        seen=set()
    visit_tree_node(elem,seen,len(out),depth,"logical structure")
    attributes,attribute_errors=structure_attribute_objects(elem,class_map)
    item={
        "elem":elem,
        "objgen":objgen(elem),
        "parent":parent,
        "parent_objgen":objgen(parent) if parent is not None else None,
        "type":safe_name(dict_get(elem,"/S",None)),
        "attrs":attributes,
        "attribute_errors":attribute_errors,
    }
    out.append(item)
    for child in struct_children(elem):
        walk_struct_elems(child,out,elem,seen,depth+1,class_map)

class StructureRoles:
    """ISO 32000-2 Tables 354/356 and 14.8.6.2; no namespace-name guessing."""
    PDF17 = "http://iso.org/pdf/ssn"
    PDF20 = "http://iso.org/pdf2/ssn"
    COMMON = frozenset("Document Part Sect Div NonStruct P H H1 H2 H3 H4 H5 H6 L LI Lbl LBody Table TR TH TD THead TBody TFoot Caption Span Link Annot Ruby RB RT RP Warichu WT WP Figure Formula Form".split())
    LEGACY = frozenset("Art BlockQuote TOC TOCI Index Private Quote Note Reference BibEntry Code".split())
    MODERN = frozenset("DocumentFragment Aside Title FENote Sub Em Strong Artifact".split())
    LIMIT = "Role mapping inspection limit reached"

    def __init__(self, root, pdf20):
        self.root, self.pdf20, self.cache = root, pdf20, {}
        namespaces = dict_get(root,"/Namespaces",None)
        self.registered = {ns.objgen for ns in namespaces if isinstance(ns,pikepdf.Dictionary) and ns.is_indirect} if is_pdf_array(namespaces) else set()

    def element(self, element):
        ns = dict_get(element,"/NS",None) if self.pdf20 else None
        if ns is not None and (not isinstance(ns,pikepdf.Dictionary) or not ns.is_indirect):
            return "", "Invalid structure namespace reference"
        tag=dict_get(element,"/S",None)
        return self.role(safe_name(tag) if isinstance(tag,pikepdf.Name) else "",ns)

    def role(self, role, namespace=None):
        initial = (namespace.objgen if namespace is not None else None,role)
        if initial in self.cache:
            return self.cache[initial]
        seen = set()
        while True:
            if len(seen)>=4096:
                result = ("",self.LIMIT); break
            key = (namespace.objgen if namespace is not None else None,role)
            if not role or key in seen:
                result = ("","Missing or cyclic role mapping"); break
            seen.add(key)
            uri = self.PDF17 if namespace is None else norm_text(dict_get(namespace,"/NS",""))
            if namespace is not None and (namespace.objgen not in self.registered or not uri or not isinstance(dict_get(namespace,"/NS",None),pikepdf.String)):
                result = ("","Unregistered or unnamed namespace"); break
            mapping_object = dict_get(self.root if namespace is None else namespace,"/RoleMap" if namespace is None else "/RoleMapNS",None)
            if mapping_object is not None and not isinstance(mapping_object,pikepdf.Dictionary):
                result = ("","Invalid role-map dictionary"); break
            mapping = dict_get(mapping_object,"/"+role,None)
            if mapping is None:
                standard = (uri==self.PDF17 and role in self.COMMON|self.LEGACY) or (uri==self.PDF20 and (role in self.COMMON|self.MODERN or re.fullmatch(r"H[1-9][0-9]*",role)))
                result = (role,"") if standard else ("","Unresolved standard role"); break
            if isinstance(mapping,pikepdf.Name):
                role,namespace = safe_name(mapping),None; continue
            if namespace is not None and is_pdf_array(mapping) and len(mapping)==2 and isinstance(mapping[0],pikepdf.Name) and isinstance(mapping[1],pikepdf.Dictionary) and mapping[1].is_indirect:
                role,namespace = safe_name(mapping[0]),mapping[1]; continue
            result = ("","Invalid role-map target"); break
        self.cache[initial] = result
        return result

def normalize_role_map(rolemap):
    out={}
    if rolemap is None:
        return out
    try:
        for k,v in rolemap.items():
            out[safe_name(k)]=safe_name(v)
    except Exception:
        pass
    return out

def structure_attribute_objects(elem,class_map):
    """Resolve classes before direct attributes; revisions never rank values."""
    errors=[]
    revision=dict_get(elem,"/R",0)
    if not isinstance(revision,int) or isinstance(revision,bool) or revision<0:
        errors.append("Structure R must be a nonnegative integer")

    def pairs(value,kind,label):
        if value is None:
            return []
        items=list(value) if is_pdf_array(value) else [value]
        if label=="A" and not items:
            errors.append("A array must contain an attribute object")
        result=[]
        index=0
        while index<len(items):
            item=items[index]
            if not isinstance(item,kind):
                errors.append(f"{label} contains an invalid object or unpaired revision")
            else:
                result.append(item)
            index+=1
            if index<len(items) and isinstance(items[index],int) and not isinstance(items[index],bool):
                if items[index]<0:
                    errors.append(f"{label} revision must be nonnegative")
                index+=1
        return result

    classes=pairs(dict_get(elem,"/C",None),pikepdf.Name,"C")
    attributes=[]
    if classes and class_map is not None and not isinstance(class_map,pikepdf.Dictionary):
        errors.append("ClassMap must be a dictionary")
    for name in classes:
        value=dict_get(class_map,str(name),None)
        # Current 14.7.6.2 errata explicitly defines a missing class as empty.
        if value is None:
            continue
        for attribute in list(value) if is_pdf_array(value) else [value]:
            if isinstance(attribute,(pikepdf.Dictionary,pikepdf.Stream)):
                attributes.append(attribute)
            else:
                errors.append("ClassMap values must be attribute objects or arrays of attribute objects")
    attributes.extend(pairs(dict_get(elem,"/A",None),(pikepdf.Dictionary,pikepdf.Stream),"A"))
    for attribute in attributes:
        owner=dict_get(attribute,"/O",None)
        if not isinstance(owner,pikepdf.Name):
            errors.append("Attribute O must be a name")
        if owner==pikepdf.Name.UserProperties:
            if dict_get(attribute,"/NS",None) is not None:
                errors.append("UserProperties cannot have an NS entry")
            props=dict_get(attribute,"/P",None)
            if not is_pdf_array(props) or any(not isinstance(p,pikepdf.Dictionary) for p in props):
                errors.append("UserProperties P must be an array of property dictionaries")
    return attributes,errors

def parse_userproperties(attr):
    # P is the attribute value. A later UserProperties P replaces the entire
    # earlier array; individual N entries do not merge across attribute objects.
    props=None
    attrs=attr if is_pdf_array(attr) or isinstance(attr,list) else [attr]
    for a in attrs:
        owner=dict_get(a,"/O",None)
        if not isinstance(owner,pikepdf.Name) or owner!=pikepdf.Name.UserProperties:
            continue
        if dict_get(a,"/P",None) is not None:
            props=dict_get(a,"/P",None)
    return list(props) if is_pdf_array(props) or isinstance(props,list) else []

def userprop_name(prop):
    """Decode actual Text strings without coercing other objects or hiding raw types."""
    value=dict_get(prop,"/N",None)
    if not isinstance(value,pikepdf.String):
        return None
    try:
        return str(value)
    except (UnicodeError,ValueError,pikepdf.PdfError):
        return None

def userprop_value_kind(v):
    # Classify the PDF object before converting it for display. Names and
    # compound objects can all be stringified, but are not PDF strings.
    if isinstance(v,bool):
        return "bool"
    if isinstance(v,int):
        return "int"
    if isinstance(v,(Decimal,float)):
        return "real"
    if isinstance(v,pikepdf.String):
        return "string"
    return "unknown"

def get_af_specs(pdf):
    root=pdf.Root
    af=dict_get(root,"/AF",None)
    out=[]
    if af is None: return out
    for item in af:
        out.append(parse_filespec("",item,source="AF"))
    return out

def collect_object_af_specs(pdf):
    """Read AF arrays on reachable dictionaries/streams, including direct ones."""
    pending=[(pdf.Root,0)]
    seen=set()
    count=0
    files=[]
    while pending:
        obj,depth=pending.pop()
        if not isinstance(obj,(pikepdf.Dictionary,pikepdf.Stream,pikepdf.Array)):
            continue
        identity=objgen(obj)
        if identity and identity!=(0,0):
            if identity in seen:
                continue
            seen.add(identity)
        count+=1
        if depth>MAX_TREE_DEPTH or count>MAX_TREE_ITEMS:
            raise ValueError("Associated-file object traversal exceeds resource limits")
        if isinstance(obj,pikepdf.Array):
            pending.extend((child,depth+1) for child in obj)
        else:
            associated=dict_get(obj,"/AF",None)
            if is_pdf_array(associated):
                files.extend(parse_filespec("",spec,source="ObjectAF") for spec in associated
                             if isinstance(spec,pikepdf.Dictionary))
            pending.extend((child,depth+1) for child in obj.values())
    return files

def walk_collection_folder_nodes(node, out, parent_id=None, seen=None, depth=0):
    if node is None:
        return
    if seen is None:
        seen=set()
    cur=node
    while cur is not None:
        visit_tree_node(cur,seen,len(out),depth,"collection folder")
        og=objgen(cur)
        folder_id=dict_get(cur,"/ID",None)
        try:
            folder_id=int(folder_id)
        except Exception:
            folder_id=None
        name_value=dict_get(cur,"/Name",None)
        name=str(name_value) if isinstance(name_value,pikepdf.String) else ""
        out.append({
            "objgen":og,
            "id":folder_id,
            "parent_id":parent_id,
            "name":name,
            "name_is_text":isinstance(name_value,pikepdf.String),
            "is_root":parent_id is None,
        })
        child=dict_get(cur,"/Child",None)
        if child is not None:
            walk_collection_folder_nodes(child, out, folder_id, seen, depth+1)
        cur=dict_get(cur,"/Next",None)

def collect_collection_folders(collection):
    folders_root=dict_get(collection,"/Folders",None)
    out=[]
    if folders_root is not None:
        walk_collection_folder_nodes(folders_root, out, None, set())
    return out

def parse_collection_name_tree_key(key,root_folder_id=0):
    text=str(key)
    m=re.match(r"^<([0-9]+)>(.*)$", text,re.DOTALL)
    if m:
        return int(m.group(1)), m.group(2)
    return root_folder_id, text

def invalid_collection_filename_reason(name):
    if not isinstance(name, str):
        name=str(name)
    if "\x00" in name:
        return "contains NULL"
    if len(name) < 1 or len(name) > 255:
        return "length must be between 1 and 255"
    bad_chars='/\\\\:*"<>|'
    for ch in name:
        if ch in bad_chars:
            return f"contains forbidden character {ch!r}"
    if name.endswith("."):
        return "ends with '.'"
    return ""

def collect_marked_content_af_specs(pdf):
    out=[]
    seen=set()
    for page_num,page in enumerate(pdf.pages, start=1):
        for event in iter_content_instructions(page):
            opname=event["operator"]
            if opname not in ("BDC","DP"):
                continue
            operands=event["operands"]
            if len(operands) < 2:
                continue
            props=resolved_content_properties(operands,event["resources"])
            # ISO 32000-2 erratum 374: /AF /resource BDC uses a property
            # dictionary containing /MCAF, not an array as the property list.
            af=(dict_get(props,"/MCAF",None) if opname=="BDC"
                and safe_name(operands[0])=="AF" else None)
            if af is None:
                # Retain legacy /AF observations for EA-PDF evidence checks;
                # reading them is not a generic syntax/conformance assertion.
                af=dict_get(props,"/AF",None)
            if af is None:
                continue
            specs=af if is_pdf_array(af) else [af]
            for spec in specs:
                og=objgen(spec)
                key=(page_num, og)
                if og and og!=(0,0):
                    if key in seen:
                        continue
                    seen.add(key)
                info=parse_filespec("",spec,source="MarkedContentAF")
                info["page"]=page_num
                out.append(info)
    return out

def map_embedded_by_objgen(files):
    m={}
    for f in files:
        if f["objgen"] is not None: m[f["objgen"]]=f
    return m

def map_stream_by_objgen(files):
    m={}
    for f in files:
        if f["ef_objgen"] is not None: m[f["ef_objgen"]]=f
    return m

def collect_font_objects(page,seen=None):
    if seen is None: seen=set()
    fonts=[]
    resource_path=set()
    resource_count=0
    def walk_resources(res,depth=0):
        nonlocal resource_count
        if res is None: return
        visit_tree_node(res,resource_path,resource_count,depth,"font resource")
        resource_count+=1
        fo=dict_get(res,"/Font",None)
        if fo is not None:
            for name,font in fo.items():
                og=objgen(font)
                if og and og in seen: continue
                if og: seen.add(og)
                fonts.append((safe_name(name),font))
        xo=dict_get(res,"/XObject",None)
        if xo is not None:
            for _,xobj in xo.items():
                subtype=safe_name(dict_get(xobj,"/Subtype",None))
                if subtype=="Form":
                    walk_resources(dict_get(xobj,"/Resources",None),depth+1)
        identity=objgen(res)
        resource_path.remove(identity if identity and identity!=(0,0) else id(res))
    walk_resources(dict_get(page,"/Resources",None))
    return fonts

def font_embedded(font):
    fd=dict_get(font,"/FontDescriptor",None)
    if fd is not None:
        if any(dict_get(fd,k,None) is not None for k in ("/FontFile","/FontFile2","/FontFile3")):
            return True
    descendants=dict_get(font,"/DescendantFonts",None)
    if descendants is not None:
        for df in descendants:
            fd=dict_get(df,"/FontDescriptor",None)
            if fd is not None and any(dict_get(fd,k,None) is not None for k in ("/FontFile","/FontFile2","/FontFile3")):
                return True
    return False

def collect_all_fonts(pdf):
    out=[]
    seen=set()
    for i,page in enumerate(pdf.pages):
        for name,font in collect_font_objects(page,seen):
            subtype=safe_name(dict_get(font,"/Subtype",None))
            base=norm_text(str(dict_get(font,"/BaseFont","")))
            out.append({"page":i+1,"name":name,"subtype":subtype,"basefont":base,"embedded":font_embedded(font),"objgen":objgen(font)})
    return out

def _pdf_rule_context(pdf, report):
    objects = _pdf_rules.PdfObjects(dict_get, observed_pdf_value, safe_name, norm_text)
    return _pdf_rules.PdfRuleContext(pdf, report, objects)


def check_pdf_version(pdf,report):
    return _pdf_rules.check_pdf_version(_pdf_rule_context(pdf, report), declared_version=get_declared_pdf_version, effective_version=effective_pdf_version)

def check_pages(pdf,report):
    return _pdf_rules.check_pages(_pdf_rule_context(pdf, report))

def check_output_intents(pdf,report):
    return _pdf_rules.check_output_intents(_pdf_rule_context(pdf, report))

def expected_navigation_mode(eapdf_conf, efiles=None, afiles=None, root_xml=None):
    if not eapdf_conf:
        return None
    if eapdf_conf in ("m", "mi"):
        return "UseOutlines"
    if eapdf_conf in ("c", "ci"):
        return "UseAttachments"
    if eapdf_conf in ("s", "si"):
        efiles = efiles or []
        afiles = afiles or []
        non_source = [f for f in efiles if f["afrelationship"] != "Source"]
        has_non_source_embeds = len(non_source) > 0
        _inspectable_sources, sources_with_attachments = source_email_mime_attachment_expectation(afiles)
        has_email_attachments = sources_with_attachments > 0
        if not has_email_attachments and root_xml is not None:
            xmp_attachments = flatten_attachments(root_xml)
            if xmp_attachments:
                has_email_attachments = True
            else:
                number_attachments = first_message_xmp_value(root_xml, "NumberAttachments", "Mail_NumberAttachments")
                if is_nonneg_int_str(number_attachments):
                    has_email_attachments = nonnegative_integer_digits(number_attachments) != "0"
        return "UseAttachments" if has_email_attachments else "UseOutlines"
    return None


def check_catalog_rules(pdf,report,eapdf_conf=None,efiles=None,afiles=None,root_xml=None):
    return _pdf_rules.check_catalog_rules(_pdf_rule_context(pdf, report), eapdf_conf, efiles, afiles, root_xml, navigation_mode=expected_navigation_mode)

def check_pdfa_xmp(root_xml,report):
    return _xmp_rules.check_pdfa_xmp(_xmp_rules_dependencies(), root_xml, report)

def check_eapdf_xmp(root_xml,nsmap,report):
    return _xmp_rules.check_eapdf_xmp(_xmp_rules_dependencies(), root_xml, nsmap, report)

def check_keywords(pdf,root_xml,pdfa_part,report):
    return _xmp_rules.check_keywords(_xmp_rules_dependencies(), pdf, root_xml, pdfa_part, report)

def check_docinfo_consistency(pdf,root_xml,pdfa_part,report):
    return _xmp_rules.check_docinfo_consistency(_xmp_rules_dependencies(), pdf, root_xml, pdfa_part, report)


def source_set_supplements(root_xml,afiles):
    # A Supplement alone is not proof of a source set (it can be an ordinary
    # auxiliary file). Require an exact source-asset XMP record as evidence.
    if root_xml is None or not any(f["afrelationship"]=="Source" for f in afiles):
        return []
    assets=flatten_assets(root_xml)
    return [f for f in afiles if f["afrelationship"]=="Supplement" and any(
        bool(a.get("Filename"))
        and str(a.get("SizeInBytes"))==str(f["size"])
        and str(a.get("Checksum","")).lower()==f["md5"].lower()
        and a.get("ContentType")==f["subtype"] for a in assets)]

def check_source_file_sets(pdf,root_xml,afiles,report,expected_sets=None):
    return _embedding_rules.check_source_file_sets(_embedding_rules_dependencies(), pdf, root_xml, afiles, report, expected_sets)

def check_collection_content(pdf,efiles,afiles,eapdf_conf,report,related_streams=(),supplements=()):
    return _embedding_rules.check_collection_content(_embedding_rules_dependencies(), pdf, efiles, afiles, eapdf_conf, report, related_streams, supplements)

def check_embedded_files(pdf,root_xml,eapdf_conf,pdfa_part,report):
    return _embedding_rules.check_embedded_files(_embedding_rules_dependencies(), pdf, root_xml, eapdf_conf, pdfa_part, report)

def represented_email_attachment_presence(root_xml,afiles,source_bindings=None):
    """Known attachment presence, or None when the represented original is unknown."""
    if root_xml is not None:
        if flatten_attachments(root_xml) or any(
                is_nonneg_int_str(record.get("NumberAttachments","")) and
                nonnegative_integer_digits(record["NumberAttachments"])!="0"
                for record in flatten_email_records(root_xml)):
            return True
    if source_bindings:
        if any(source is not None and source.get("mime_attachment_count",source.get("attachment_count",0))>0
               for source in source_bindings.values()):
            return True
        if any(source is None or not source.get("attachment_inventory_available",True) for source in source_bindings.values()) and any(
                file.get("afrelationship")=="Source" for file in afiles):
            return None
        return False
    _inspectable,with_attachments=source_email_mime_attachment_expectation(afiles)
    return with_attachments>0

def check_profile_rules(pdf,efiles,afiles,eapdf_conf,pdfa_part,report,root_xml=None):
    return _embedding_rules.check_profile_rules(_embedding_rules_dependencies(), pdf, efiles, afiles, eapdf_conf, pdfa_part, report, root_xml)

def check_outlines(pdf,report):
    return _structure_rules.check_outlines(_structure_rules_dependencies(), pdf, report)

def check_page_dpart_links(pdf,nodes,report):
    return _structure_rules.check_page_dpart_links(_structure_rules_dependencies(), pdf, nodes, report)

def check_dpart_and_content_sets(pdf,root_xml,eapdf_conf,report,outline_pages):
    return _structure_rules.check_dpart_and_content_sets(_structure_rules_dependencies(), pdf, root_xml, eapdf_conf, report, outline_pages)

def check_collection_rules(pdf,eapdf_conf,report):
    return _embedding_rules.check_collection_rules(_embedding_rules_dependencies(), pdf, eapdf_conf, report)

def check_logical_structure(pdf,eapdf_conf,report):
    return _structure_rules.check_logical_structure(_structure_rules_dependencies(), pdf, eapdf_conf, report)

def check_structure_correspondence(pdf,report):
    return _structure_rules.check_structure_correspondence(_structure_rules_dependencies(), pdf, report)

def check_user_properties(custom_elems,report):
    return _structure_rules.check_user_properties(_structure_rules_dependencies(), custom_elems, report)

def check_user_property_source_values(elems,root_xml,afiles,report):
    return _structure_rules.check_user_property_source_values(_structure_rules_dependencies(), elems, root_xml, afiles, report)

def check_ambiguous_source_asset_values(asset,candidates,report):
    return _embedding_rules.check_ambiguous_source_asset_values(_embedding_rules_dependencies(), asset, candidates, report)

def check_asset_metadata(root_xml,efiles,afiles,eapdf_conf,report,related_assets=()):
    return _embedding_rules.check_asset_metadata(_embedding_rules_dependencies(), root_xml, efiles, afiles, eapdf_conf, report, related_assets)

def valid_smtp_mailbox(value):
    from email.headerregistry import Address
    value = str(value)
    if any(ord(character)<32 or ord(character)==127 for character in value):
        return False
    value = norm_text(value)
    if value.lower().startswith("mailto:"):
        value = value[7:]
    if "@" not in value:
        return False
    try:
        address=Address(addr_spec="".join(character if ord(character) < 128 else "x" for character in value))
        # RFC 5322's domain atoms accept apostrophes, unlike SMTP domains.
        # Such source values must retain an exact raw representation.
        return bool(re.fullmatch(r"[A-Za-z0-9.-]+",address.domain)
                    or re.fullmatch(r"\[[^\[\]\r\n]+\]",address.domain))
    except (ValueError, IndexError, HeaderParseError):
        # Headerregistry can mistake a literal SMTP atom for an encoded word.
        # Accept only the independently valid ASCII dot-string/DNS production;
        # this fallback never decodes the addr-spec or repairs its punctuation.
        local_part,separator,domain=value.rpartition("@")
        atom=r"[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
        label=r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
        return bool(separator and re.fullmatch(atom+r"(?:\."+atom+r")*",local_part)
                    and re.fullmatch(label+r"(?:\."+label+r")*",domain))

def mailbox_comparison_key(value):
    """Compare decoded addr-specs, preserving local-part case and quoted data."""
    value=str(value).strip()
    if value.lower().startswith("mailto:"):
        value=value[7:]
    local_part,separator,domain=value.rpartition("@")
    if not separator:
        return None
    if local_part.startswith('"') and local_part.endswith('"'):
        local_part=re.sub(r"\\(.)",r"\1",local_part[1:-1])
    return local_part,domain.lower()

def compare_agent_property(root_xml,report,prop,source_agents,presence_code,foaf_code,source_raw=None):
    values=[xmp_semantic_value(node) for node in xmp_property_nodes(root_xml,NS_PDFMAILMETA,prop)]
    values.extend(record[prop] for record in flatten_email_records(root_xml) if prop in record)
    schema_valid=not any(not isinstance(value,XmpArray) or value.kind!="Seq"
           or any(not isinstance(member,dict) or not normalize_foaf_agents(member)
                  or not normalize_foaf_agents(member)[0].get("mbox") for member in value) for value in values)
    if not schema_valid:
        report.fail(foaf_code,f"XMP {prop} must be an ordered array of FOAF agents",
                    {"array_kinds":[getattr(value,"kind",None) for value in values]})
        report.fail(presence_code,f"Supplied {prop} requires the prescribed ordered Agent representation",{"field":prop})
    elif values:
        report.ok(presence_code,f"Supplied {prop} uses its prescribed Agent representation",{"field":prop})
    else:
        report.na(presence_code,f"No {prop} value is supplied; original-conditioned presence is assessed separately",{"field":prop})
    xmp_agents=extract_message_foaf_agents(root_xml,prop) if schema_valid else []
    if any(contains_surrogate(agent.get("mbox","")) for agent in source_agents or []):
        source_metadata_result(report,"WARN",prop,"Source address interpretation is unavailable for undecodable mailbox octets",{"assessment":"unavailable"},True)
        source_metadata_result(report,"WARN",prop,"Source mailbox comparison is unavailable for undecodable octets",{"assessment":"unavailable","source":source_agents})
        report.warn("VALIDATION_INCOMPLETE","Required source address comparison could not be completed",{"field":prop,"assessment":"unavailable"})
        source_agents=None
    invalid_source=[agent for agent in source_agents or [] if not valid_smtp_mailbox(agent.get("mbox",""))]
    source_comparison_unavailable=False
    if invalid_source:
        source_agents=[agent for agent in source_agents if valid_smtp_mailbox(agent.get("mbox",""))]
        raw=first_message_xmp_value(root_xml,"Raw-"+prop)
        if source_raw is not None and is_xmp_text(raw) and raw and decoded_header_text(raw)==decoded_header_text(source_raw):
            source_metadata_result(report,"PASS","Raw-"+prop,"Non-SMTP source addresses are represented by the matching raw Core Field",{"non_smtp_addresses":len(invalid_source)},True)
            if len(xmp_agents)>len(source_agents):
                source_comparison_unavailable=True
                source_metadata_result(report,"WARN",prop,"Additional SMTP mailboxes cannot be verified against non-SMTP source tokens without normalization evidence",{"assessment":"unavailable"})
                report.warn("VALIDATION_INCOMPLETE","Non-SMTP to SMTP mailbox normalization could not be verified",{"field":prop,"assessment":"unavailable"})
        else:
            source_comparison_unavailable=True
            if is_xmp_text(raw) and raw and isinstance(source_raw,str):
                try:
                    decoded_source=decoded_source_header(source_raw)
                except ValueError:
                    pass  # Unavailable source decoding cannot prove a raw-value mismatch.
                else:
                    if decoded_header_text(raw)!=decoded_source:
                        source_metadata_result(report,"FAIL","Raw-"+prop,"Supplied raw address text differs from the non-SMTP source header",{
                            "xmp":raw,"source":source_raw})
            if not source_agents:
                source_metadata_result(report,"WARN",prop,"Non-SMTP source tokens do not establish a missing SMTP Agent",{
                    "assessment":"unavailable"},True)
            source_metadata_result(report,"WARN",prop,"Non-SMTP source addresses cannot be compared without an exact raw Core Field",{"assessment":"unavailable"})
            report.warn("VALIDATION_INCOMPLETE","Non-SMTP source address preservation could not be verified",{"field":prop})
    if not schema_valid:
        if source_agents:
            source_metadata_result(report,"PASS",prop,"The source-conditioned field is supplied; its representation fails the separate schema check",{"source_count":len(source_agents)},True)
        if source_agents is not None:
            source_metadata_result(report,"N/A",prop,"Mailbox fidelity requires the separately checked Agent representation")
        return
    invalid_agents=[agent for agent in xmp_agents if agent.get("mbox_uri_error") or not valid_smtp_mailbox(agent.get("mbox",""))]
    if invalid_agents:
        report.fail(foaf_code,"FOAF mailbox entries must be SMTP mailbox addresses; non-SMTP source tokens belong in raw Core Fields",{"agents":invalid_agents})
        return
    if source_agents:
        if xmp_agents:
            source_metadata_result(report,"PASS",prop,f"Source {prop} has corresponding XMP metadata",{"count":len(xmp_agents),"agents":xmp_agents},True)
        else:
            source_metadata_result(report,"FAIL",prop,f"XMP {prop} missing even though source email has {prop} header",{"source_count":len(source_agents)},True)
            return
    elif not xmp_agents:
        report.na(foaf_code,f"No {prop} agents require mailbox schema checks",{"field":prop})
        return
    bad=[a for a in xmp_agents if not norm_text(a.get("mbox",""))]
    if bad:
        report.fail(foaf_code,f"XMP {prop} contains foaf:Agent entries without foaf:mbox",{"agents":bad})
    else:
        report.ok(foaf_code,f"XMP {prop} foaf:Agent entries include foaf:mbox",{"count":len(xmp_agents),"agents":xmp_agents})
    if source_agents is not None and xmp_agents and not source_comparison_unavailable:
        source_mboxes=[a["mbox"] for a in source_agents if a.get("mbox")]
        xmp_mboxes=[a["mbox"] for a in xmp_agents if a.get("mbox")]
        source_keys=[mailbox_comparison_key(value) for value in source_mboxes]
        xmp_keys=[mailbox_comparison_key(value) for value in xmp_mboxes]
        definite_mismatch=len(source_keys)!=len(xmp_keys)
        unavailable=any(key is None for key in source_keys+xmp_keys)
        for source_key,xmp_key in zip(source_keys,xmp_keys):
            if source_key is None or xmp_key is None:
                continue
            if source_key[0]!=xmp_key[0]:
                definite_mismatch=True
            if source_key[1]!=xmp_key[1]:
                if any(ord(character)>127 for character in source_key[1]+xmp_key[1]):
                    unavailable=True  # No unverified IDNA mapping is invented.
                else:
                    definite_mismatch=True
        details={"xmp":xmp_mboxes,"source":source_mboxes}
        if definite_mismatch:
            source_metadata_result(report,"FAIL",prop,f"XMP {prop} foaf:Agent values differ from source email header",details)
        elif unavailable:
            source_metadata_result(report,"WARN",prop,f"Source {prop} mailbox comparison requires unavailable normalization evidence",dict(details,assessment="unavailable"))
            report.warn("VALIDATION_INCOMPLETE","Source address comparisons require additional mailbox normalization evidence",{"field":prop,**details})
        else:
            source_metadata_result(report,"PASS",prop,f"XMP {prop} foaf:Agent values match source email",{"count":len(xmp_mboxes),**details})

def match_source_attachment_records(attachments,candidates):
    """Find compatible source comparisons for an unordered Table 19 set.

    Reserve each source occurrence once. An augmenting path lets a record with
    an optional checksum move aside for a more constrained record. Equivalent
    assignments establish field agreement, not unique occurrence identity.
    """
    def compatible_sources(att):
        guid=guid_value(att.get("Mail_GUID",""))
        message_id=norm_text(att.get("Message-ID",""))
        filename=att.get("Filename","") if is_xmp_text(att.get("Filename")) else ""
        checksum=att.get("CheckSum","") if is_xmp_text(att.get("CheckSum")) else ""
        placeholder=(parse_nonneg_int_value(att.get("SizeInBytes"))==0
                     and (not checksum or checksum.lower()==md5hex(b"")))
        for index,source in enumerate(candidates):
            if guid and guid!=source.get("_source_mail_guid"):
                continue
            if not guid and message_id and message_id!=source.get("_source_message_id"):
                continue
            source_names=source_attachment_filenames(source)
            if source_names and filename not in source_names:
                continue
            if not placeholder and parse_nonneg_int_value(att.get("SizeInBytes"))!=source.get("size_in_bytes"):
                continue
            if not placeholder and checksum and checksum.lower()!=source.get("checksum","").lower():
                continue
            if source.get("content_type_available",True) and media_type_signature(att.get("Content-Type",""))!=media_type_signature(source.get("content_type","")):
                continue
            yield index
    matched_records={}
    matched_sources={}
    for start in range(len(attachments)):
        pending=[start]
        visited_records={start}
        predecessors={}
        for record in pending:
            for source in compatible_sources(attachments[record]):
                if source in predecessors:
                    continue
                predecessors[source]=record
                if source not in matched_sources:
                    while True:
                        owner=predecessors[source]
                        previous=matched_records.get(owner)
                        matched_records[owner]=source
                        matched_sources[source]=owner
                        if previous is None:
                            break
                        source=previous
                    break
                owner=matched_sources[source]
                if owner not in visited_records:
                    visited_records.add(owner)
                    pending.append(owner)
            else:
                continue
            break
    return {record:candidates[source] for record,source in matched_records.items()}

def validate_attachment_records(attachments,summary,report):
    summaries = []
    if isinstance(summary, list):
        summaries = [s for s in summary if isinstance(s, dict)]
    elif isinstance(summary, dict):
        summaries = [summary]
    remaining=[]
    unavailable=[summary for summary in summaries if not summary.get("attachment_inventory_available",True)]
    summaries=[summary for summary in summaries if summary.get("attachment_inventory_available",True)]
    have_source_summaries = bool(summaries) and not unavailable
    for idx, src_summary in enumerate(summaries):
        src_message_id = norm_text(src_summary.get("message_id", ""))
        for candidate in src_summary.get("mime_attachments", src_summary.get("attachments", [])):
            enriched = dict(candidate)
            enriched["_summary_index"] = idx
            enriched["_source_message_id"] = src_message_id
            enriched["_source_mail_guid"] = guid_value(src_summary.get("mail_guid",""))
            remaining.append(enriched)
    original_attachment_count=len(remaining)
    if have_source_summaries and not remaining:
        report.na("ATTACHMENT_XMP_INVENTORY_RECOMMENDED","Inspected originals have no attachments")
    elif unavailable:
        report.warn("ATTACHMENT_XMP_INVENTORY_RECOMMENDED","Original attachment inventory is unavailable",{"assessment":"unavailable","advisory":True})
    if not attachments:
        if have_source_summaries and original_attachment_count:
            report.warn("ATTACHMENT_XMP_INVENTORY_RECOMMENDED","Inspected original attachments have no metadata records",{
                "metadata_records":0,"original_attachments":original_attachment_count,"represented_originals":0,"advisory":True})
        return
    source_matches=match_source_attachment_records(attachments,remaining)
    reserved={id(candidate) for candidate in source_matches.values()}
    remaining=[candidate for candidate in remaining if id(candidate) not in reserved]
    for attachment_index,att in enumerate(attachments):
        filename = att.get("Filename","") if is_xmp_text(att.get("Filename")) else ""
        if filename:
            report.ok("ATTACHMENT_XMP_FILENAME","Attachment XMP Filename is present",{"Filename":filename})
        else:
            report.fail("ATTACHMENT_XMP_FILENAME","Attachment XMP record must include Filename",{"attachment":att})

        size_text = norm_text(att.get("SizeInBytes",""))
        if size_text:
            report.ok("ATTACHMENT_XMP_SIZE_PRESENT","Attachment XMP SizeInBytes is present",{"Filename":att.get("Filename"),"SizeInBytes":size_text})
            if is_nonneg_int_str(att.get("SizeInBytes","")):
                report.ok("ATTACHMENT_XMP_SIZE_TYPE","Attachment XMP SizeInBytes is a non-negative integer",{"Filename":att.get("Filename"),"SizeInBytes":str(att.get("SizeInBytes"))})
            else:
                report.fail("ATTACHMENT_XMP_SIZE_TYPE","Attachment XMP SizeInBytes must be a non-negative integer",{"attachment":att})
        else:
            report.fail("ATTACHMENT_XMP_SIZE_PRESENT","Attachment XMP record must include SizeInBytes",{"attachment":att})

        ctype=att.get("Content-Type","")
        if is_xmp_text(ctype) and norm_text(ctype):
            report.ok("ATTACHMENT_XMP_CONTENTTYPE","Attachment XMP Content-Type is present",{"Filename":att.get("Filename"),"Content-Type":ctype})
        else:
            report.fail("ATTACHMENT_XMP_CONTENTTYPE","Attachment XMP record must include Content-Type",{"attachment":att})

        checksum=att.get("CheckSum","")
        if "CheckSum" in att:
            if is_xmp_text(checksum) and re.fullmatch(r"[0-9A-Fa-f]{32}",checksum):
                report.ok("ATTACHMENT_XMP_CHECKSUM_FORM","Attachment XMP CheckSum format valid",{"Filename":att.get("Filename"),"CheckSum":str(checksum)})
            else:
                report.fail("ATTACHMENT_XMP_CHECKSUM_FORM","Attachment XMP CheckSum must be normal text containing 32 ASCII hex characters when present",{"attachment":att})
        else:
            report.ok("ATTACHMENT_XMP_CHECKSUM_FORM","Attachment XMP CheckSum is absent, which Table 19 permits",{"Filename":att.get("Filename")})
        if "Mail_GUID" in att and not is_xmp_text(att["Mail_GUID"]):
            report.fail("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","Supplied attachment Mail_GUID must be normal text even when Message-ID permits its omission",{"attachment":att})
        elif "Message-ID" in att and not is_xmp_text(att["Message-ID"]):
            report.fail("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","Supplied attachment Message-ID must be normal text; a URI or array cannot identify its email",{"attachment":att})
        elif is_xmp_text(att.get("Message-ID")) and norm_text(att["Message-ID"]):
            report.ok("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","Attachment XMP Message-ID is present, so Mail_GUID is not required",{"Filename":att.get("Filename"),"Message-ID":att["Message-ID"]})
        elif guid_value(att.get("Mail_GUID","")):
            report.ok("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","Attachment XMP Mail_GUID is present when Message-ID is absent",{"Filename":att.get("Filename"),"Mail_GUID":att.get("Mail_GUID")})
        else:
            report.fail("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","Attachment XMP record must have Mail_GUID when Message-ID is absent",{"attachment":att})
        if not is_xmp_text(checksum):
            checksum=""
        if not have_source_summaries:
            omitted=(getattr(report,"_eapdf_conf","").endswith("i") and not getattr(report,"_originals_supplied",False))
            contained=getattr(report,"_eapdf_conf","") in ("c","ci")
            for code in ("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE","ATTACHMENT_XMP_CONTENTTYPE_MATCH","ATTACHMENT_XMP_MESSAGE_ID_MATCH"):
                if code=="ATTACHMENT_XMP_MESSAGE_ID_MATCH" and "Message-ID" not in att:
                    report.na(code,"No optional attachment Message-ID is supplied",{"Filename":filename})
                elif omitted or contained:
                    report.na(code,"Original comparison is not performed for omitted isolated originals or collection-level attachment metadata",{"Filename":filename})
                else:
                    evidence_unavailable(report,code,"An independently associated original attachment is unavailable",{"Filename":filename})
            report.add("N/A" if omitted or contained else "WARN","ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED",
                "Original native-byte preservation evidence is not available",{"Filename":filename,"advisory":True})
            continue
        att_message_id = norm_text(att.get("Message-ID",""))
        att_mail_guid = guid_value(att.get("Mail_GUID", ""))
        candidate_pool = remaining
        if att_message_id or att_mail_guid:
            scoped = []
            for candidate in remaining:
                source_message_id = norm_text(candidate.get("_source_message_id", ""))
                if att_mail_guid and candidate.get("_source_mail_guid")==att_mail_guid:
                    scoped.append(candidate)
                elif not att_mail_guid and att_message_id and source_message_id == att_message_id:
                    scoped.append(candidate)
            candidate_pool = scoped
        match=source_matches.get(attachment_index)
        fn=att.get("Filename","") if is_xmp_text(att.get("Filename")) else ""
        named=[candidate for candidate in candidate_pool if fn and fn in source_attachment_filenames(candidate)]
        if match is None and named:
            def attachment_match_score(candidate):
                score=100 if checksum and checksum.lower()==candidate.get("checksum","").lower() else 0
                if parse_nonneg_int_value(att.get("SizeInBytes"))==candidate.get("size_in_bytes"):
                    score+=10
                if norm_text(ctype).split(";",1)[0].lower()==norm_text(candidate.get("content_type","")).split(";",1)[0].lower():
                    score+=1
                return score
            match=max(named,key=attachment_match_score)
        if match is None:
            for candidate in candidate_pool:
                if not candidate.get("nested_content") or source_attachment_filenames(candidate):
                    continue
                for file in summaries[candidate["_summary_index"]].get("native_files",[]):
                    if source_file_name(file)!=filename:
                        continue
                    try:
                        if standalone_outlook_content(file.get("bytes") or b"")==candidate["nested_content"]:
                            match=candidate
                            break
                    except (OSError,ValueError):
                        pass
                if match is not None:
                    break
        if match is None:
            try:
                sz=parse_nonneg_int_value(att.get("SizeInBytes"))
            except Exception:
                sz=None
            if sz is not None:
                for candidate in candidate_pool:
                    if not source_attachment_filenames(candidate) and (candidate.get("nested_content") or candidate.get("size_in_bytes")==sz and norm_text(candidate.get("content_type",""))==norm_text(ctype)):
                        match=candidate
                        break
        if match is None:
            # A supplied checksum can independently expose a changed original
            # filename; no media/size comparison chooses the name being tested.
            payload_matches=[candidate for candidate in candidate_pool if checksum and checksum.lower()==candidate.get("checksum","").lower()]
            if len(payload_matches)==1:
                match=payload_matches[0]
            else:
                original_names=[source_attachment_filenames(candidate) for candidate in candidate_pool]
                filename_contradiction=bool(filename and original_names and all(
                    names and filename not in names for names in original_names))
                for code in ("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE","ATTACHMENT_XMP_CONTENTTYPE_MATCH","ATTACHMENT_XMP_MESSAGE_ID_MATCH"):
                    if code=="ATTACHMENT_XMP_FILENAME_MATCH_SOURCE" and filename_contradiction:
                        report.fail(code,"Supplied Filename differs from every readable original filename in its known email owner",{
                            "Filename":filename,"source_filenames":[sorted(names) for names in original_names],
                            "association_scope":"all_candidate_originals","unique_occurrence_identity":False})
                    elif code=="ATTACHMENT_XMP_MESSAGE_ID_MATCH" and not att.get("Message-ID"):
                        report.na(code,"No optional attachment Message-ID is supplied",{"Filename":filename})
                    else:
                        evidence_unavailable(report,code,"No original attachment occurrence can be associated independently",{"Filename":filename})
                report.warn("ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED","Original payload association is unavailable",{"Filename":filename,"advisory":True})
                continue
        if attachment_index not in source_matches and match in remaining:
            remaining.remove(match)
        detail={"Filename":filename,"source_filenames":sorted(source_attachment_filenames(match)),"unique_occurrence_identity":False}
        source_names=source_attachment_filenames(match)
        if source_names:
            (report.ok if filename in source_names else report.fail)("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE","Attachment Filename compared with its original case-sensitive filename",detail)
        else:
            report.na("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE","No original filename is supplied; a generated filename is permitted",detail)
        if att_message_id:
            (report.ok if att_message_id==match.get("_source_message_id") else report.fail)("ATTACHMENT_XMP_MESSAGE_ID_MATCH",
                "Attachment Message-ID compared with its containing original email",dict(detail,xmp=att_message_id,source=match.get("_source_message_id")))
        else:
            report.na("ATTACHMENT_XMP_MESSAGE_ID_MATCH","No optional attachment Message-ID is supplied",detail)
        native_files=getattr(report,"_native_attachment_files",summaries[match["_summary_index"]].get("native_files",[]))
        retained=[file for file in native_files if file.get("afrelationship") not in ("Source","Alternative")
                  and att in attachment_candidates_for_embedded(file,[att]) and file.get("size",0)>0]
        if not retained:
            report.na("ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED","No associated nonzero native attachment copy is retained",dict(detail,advisory=True))
        elif hasattr(report,"_native_attachment_files") and any(
                file.get("_scoped_email_guids") is not None and match.get("_source_mail_guid") not in file["_scoped_email_guids"]
                for file in retained):
            report.warn("ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED","Independent native-payload ownership does not establish the original attachment association",dict(detail,assessment="unavailable",advisory=True))
        else:
            matches=[]
            for file in retained:
                value=None
                if match.get("nested_content"):
                    try:value=standalone_outlook_content(file.get("bytes") or b"")==match["nested_content"]
                    except (OSError,ValueError):pass
                elif match.get("checksum") and match.get("size_in_bytes") is not None:
                    value=file.get("md5","").lower()==match["checksum"].lower() and file.get("size")==match["size_in_bytes"]
                matches.append({"file":source_file_name(file),"preserves_original":value})
            level="PASS" if all(row["preserves_original"] is True for row in matches) else "WARN"
            report.add(level,"ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED","Retained native payloads should preserve the original decoded bytes",dict(detail,comparisons=matches,advisory=True))
        if match.get("nested_content"):
            expected_type="application/vnd.ms-outlook"
        elif not match.get("content_type_available",True):
            report.na("ATTACHMENT_XMP_CONTENTTYPE_MATCH","The original MSG attachment has no stored MIME type to compare",detail)
            continue
        else:
            expected_type=match.get("content_type","")
        (report.ok if media_type_signature(ctype)==media_type_signature(expected_type) else report.fail)("ATTACHMENT_XMP_CONTENTTYPE_MATCH",
            "Attachment Content-Type compared with its original field",dict(detail,xmp=ctype,source=expected_type))
    if have_source_summaries and original_attachment_count:
        report.add("WARN" if remaining else "PASS","ATTACHMENT_XMP_INVENTORY_RECOMMENDED",
            "Original attachment occurrences should each have a corresponding metadata record",{
                "metadata_records":len(attachments),"original_attachments":original_attachment_count,
                "represented_originals":original_attachment_count-len(remaining),"advisory":True})

def decoded_header_text(value):
    try:
        return clean_header_text(str(make_header(decode_header(str(value)))))
    except (LookupError,UnicodeError,ValueError,HeaderParseError):
        return norm_text(value)

def email_source_match_score(record,source):
    score=0
    message_id=norm_text(first_present(record,"messageid","Message-ID"))
    if message_id and message_id==source["message_id"]:
        score+=100
    size=parse_nonneg_int_value(record.get("SizeInBytes"))
    if size is not None and size==source["size_in_bytes"]:
        score+=50
    if record.get("Subject") and decoded_header_text(record["Subject"])==decoded_header_text(source["subject"]):
        score+=10
    sent=parse_xmp_datetime(record.get("Sent"))
    source_sent=parse_xmp_datetime(source.get("sent_norm"))
    if sent is not None and source_sent is not None and sent==source_sent:
        score+=10
    if record.get("Raw-From") and decoded_header_text(record["Raw-From"])==decoded_header_text(source["raw_from"]):
        score+=5
    return score

def select_source_email(record,sources):
    if not sources:
        return None
    scores=[email_source_match_score(record,source) for source in sources]
    best=max(scores)
    candidates=[source for source,score in zip(sources,scores) if score==best]
    if best>=40 and (len(candidates)==1 or len({source["source_sha256"] for source in candidates})==1):
        return candidates[0]
    return None

def source_email_for_record(record,records,sources,source_bindings=None):
    """Reuse pipeline ownership, including unassigned, for downstream checks."""
    if source_bindings is None:
        return select_source_email(record,sources)
    for index,candidate in enumerate(records,1):
        if candidate is record:
            return source_bindings.get(index)
    return None

def raw_address_mailbox_keys(value):
    """Known ASCII mailbox data, independent of display whitespace cleanup."""
    if not is_xmp_text(value):
        return None
    try:
        addresses=getaddresses([value])
    except (ValueError,IndexError,HeaderParseError):
        return None
    keys=[]
    for _,address in addresses:
        address=address.strip(" \t\r\n")
        if not address or any(ord(char)>127 for char in address) or not valid_smtp_mailbox(address):
            return None
        key=mailbox_comparison_key(address)
        if key is None:
            return None
        keys.append(key)
    return keys or None

def source_date_present(source):
    if "sent_present" in source:
        return bool(source["sent_present"])
    if "header_values" in source:
        return "date" in source["header_values"]
    return bool(source.get("sent_raw"))

def source_metadata_result(report,level,field,message,details=None,required=False):
    code="MESSAGE_CORE_FIELDS_SOURCE_REQUIRED" if required else "MESSAGE_METADATA_MATCH_SOURCE"
    report.add(level,code,message,dict(details or {},field=field))


def check_source_field_values(record,source,report):
    return _message_rules.check_source_field_values(_message_rules_dependencies(), record, source, report)


def source_assertion_key(item):
    details=item.get("details") or {}
    field=details.get("field")
    if not field and item["code"] in ("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","MESSAGE_RAW_SENT_OMIT_IF_VALID"):
        field="Raw-Sent"
    return item["code"],field


def source_header_candidate_failures(root_xml,record,source_file,eapdf_conf,path):
    candidate=Report(path)
    check_single_message_metadata(root_xml,[source_file],eapdf_conf,candidate)
    unavailable={source_assertion_key(item) for item in candidate.items if item["level"]=="WARN" and
                 ((item.get("details") or {}).get("assessment")=="unavailable" or "unavailable" in item["message"].lower())}
    unavailable.update(("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED",field) for code,field in list(unavailable)
                       if code=="MESSAGE_METADATA_MATCH_SOURCE" and field in ("To","From","Cc","Bcc"))
    return [item for item in candidate.items if item["level"]=="FAIL"
            and item["code"] in {"MESSAGE_CORE_FIELDS_SOURCE_REQUIRED","MESSAGE_METADATA_MATCH_SOURCE","MESSAGE_RAW_SENT_REQUIRED_IF_INVALID"}
            and source_assertion_key(item) not in unavailable and source_assertion_key(item)[1]!="NumberAttachments"]


def check_ambiguous_source_header_values(root_xml,record,afiles,eapdf_conf,report):
    return _message_rules.check_ambiguous_source_header_values(_message_rules_dependencies(), root_xml, record, afiles, eapdf_conf, report)

def check_message_metadata(root_xml,afiles,eapdf_conf,report):
    return _message_rules.check_message_metadata(_message_rules_dependencies(), root_xml, afiles, eapdf_conf, report)

def compare_native_outlook_raw(record,native,field,report):
    from collections import Counter
    code="MESSAGE_METADATA_MATCH_SOURCE"
    detail={"field":"Raw-"+field}
    actual=record.get("Raw-"+field,"")
    raw=native.get("raw_addresses",{}).get(field)
    if raw is not None and (raw["display"] or native["addresses"].get(field)==[]) and is_xmp_text(actual) and norm_text(actual)==norm_text(raw["display"]):
        report.ok(code,"Raw-"+field+" preserves the stored Outlook display field",detail)
        return
    addresses=native["addresses"].get(field)
    if raw is None or addresses is None or len(addresses)!=len(raw["names"]):
        report.warn(code,"Stored Outlook "+field+" names and mailboxes are not fully readable",dict(detail,assessment="unavailable"))
        return
    try:
        supplied=[]
        for name,address in (getaddresses([actual]) if actual else []):
            if not valid_smtp_mailbox(address):
                raise ValueError("Unrecognized native address text")
            supplied.append((mailbox_comparison_key(address),norm_text(decoded_header_text(name))))
        expected=[(mailbox_comparison_key(address),norm_text(name)) for address,name in zip(addresses,raw["names"])]
        (report.ok if Counter(supplied)==Counter(expected) else report.fail)(code,"Raw-"+field+" names and mailboxes must preserve the stored Outlook fields",detail)
    except (TypeError,ValueError):
        report.warn(code,"Raw-"+field+" cannot be compared with the stored Outlook fields",dict(detail,assessment="unavailable"))

def check_native_outlook_core(record,source,report,attachments=None,native=None):
    return _message_rules.check_native_outlook_core(_message_rules_dependencies(), record, source, report, attachments, native)

def check_single_message_metadata(root_xml,afiles,eapdf_conf,report):
    return _message_rules.check_single_message_metadata(_message_rules_dependencies(), root_xml, afiles, eapdf_conf, report)

def check_standard_xmp_semantics(root_xml, afiles, report):
    return _xmp_rules.check_standard_xmp_semantics(_xmp_rules_dependencies(), root_xml, afiles, report)

def check_single_source_xmp_semantics(root_xml, afiles, report):
    return _xmp_rules.check_single_source_xmp_semantics(_xmp_rules_dependencies(), root_xml, afiles, report)

def check_source_media_types(afiles,eapdf_conf,report):
    return _media_rules.check_source_media_types(_media_rules_dependencies(), afiles, eapdf_conf, report)

def email_part_looks_like_attachment(part):
    return email_part_is_mime_attachment(part)

def email_part_is_mime_attachment(part):
    disp=(part.get_content_disposition() or "").lower()
    ctype=(part.get_content_type() or "").lower()
    filename=part.get_filename()
    if disp=="attachment" or ctype in ("message/rfc822","message/global"):
        return True
    if filename and ctype not in ("text/plain","text/html"):
        return True
    return False

def raw_mime_body(raw):
    separator=re.search(br"\r\n\r\n|\n\n|\r\r",raw)
    return raw[separator.end():] if separator else b""

def raw_multipart_children(raw,boundary):
    body=raw_mime_body(raw)
    marker=re.compile(br"(?m)^--"+re.escape(boundary.encode("ascii"))+br"(--)?[ \t]*(?:\r?\n|$)")
    boundaries=list(marker.finditer(body))
    children=[]
    for current,following in zip(boundaries,boundaries[1:]):
        if current.group(1):
            break
        child=body[current.end():following.start()]
        if child.endswith(b"\r\n"):
            child=child[:-2]
        elif child.endswith(b"\n"):
            child=child[:-1]
        children.append(child)
    return children

def iter_email_attachment_parts(message,raw=None,depth=0):
    if depth>MAX_TREE_DEPTH:
        raise ValueError("Excessively deep source email MIME structure")
    if email_part_is_mime_attachment(message):
        if raw is not None:
            payload=raw_mime_body(raw)
            encoding=str(message.get("Content-Transfer-Encoding","")).lower().strip()
            if encoding=="base64":
                payload=base64.b64decode(payload)
            elif encoding=="quoted-printable":
                payload=quopri.decodestring(payload)
        else:
            payload=message.get_payload(decode=True)
            if payload is None and message.get_content_type() in ("message/rfc822","message/global"):
                payload=b"".join(child.as_bytes(policy=policy.SMTP.clone(refold_source="none"))
                                 for child in (message.get_payload() or []))
            payload=payload or b""
        yield message,payload
        return
    if message.is_multipart():
        parts=list(message.iter_parts())
        raw_parts=raw_multipart_children(raw,message.get_boundary()) if raw is not None and message.get_boundary() else []
        if raw is not None and len(raw_parts)!=len(parts):
            raise ValueError("Source email MIME boundaries cannot be mapped to their original bytes")
        for index,part in enumerate(parts):
            yield from iter_email_attachment_parts(part,raw_parts[index] if raw_parts else None,depth+1)

def original_mime_content_type(part):
    for name,value in part.raw_items():
        if name.lower()=="content-type":
            # BytesParser preserves non-ASCII header bytes with surrogateescape.
            # Decode valid UTF-8 without normalizing parameters or quoted spaces.
            try:
                return str(value).encode("utf-8","surrogateescape").decode("utf-8","surrogateescape")
            except UnicodeError:
                return str(value)
    return part.get_content_type()

def original_mime_filename(part):
    # get_filename() strips significant surrounding spaces even in a quoted
    # MIME parameter. Preserve the parsed parameter, including RFC 2231 values.
    values=original_mime_filenames(part)
    return values[0] if values else ""

def original_mime_filenames(part):
    # Table 19 permits either original parameter; it prescribes no precedence.
    values=[]
    for parameter,header in (("filename","Content-Disposition"),("name","Content-Type")):
        value=part.get_param(parameter,header=header)
        if value is not None:
            values.append(collapse_rfc2231_value(value) if isinstance(value,tuple) else str(value))
    return values

def summarize_email_attachments(msg,raw=None):
    out=[]
    for part,payload in iter_email_attachment_parts(msg,raw):
        out.append({
            "filename":clean_header_text(part.get_filename() or ""),
            "original_filename":original_mime_filename(part),
            "original_filenames":original_mime_filenames(part),
            "content_type":original_mime_content_type(part),
            "size_in_bytes":len(payload),
            "message_id":clean_header_text(part.get("Message-ID","")),
            "checksum":md5hex(payload),
        })
    return out

def summarize_email_mime_attachments(msg,raw=None):
    return summarize_email_attachments(msg,raw)

def embedded_email_has_attachments(af):
    if not af.get("bytes"):
        return False
    try:
        msg=BytesParser(policy=policy.default).parsebytes(af["bytes"])
    except Exception:
        return False
    return bool(summarize_email_attachments(msg,af["bytes"]))

def embedded_email_has_mime_attachments(af):
    if not af.get("bytes"):
        return False
    try:
        msg=BytesParser(policy=policy.default).parsebytes(af["bytes"])
    except Exception:
        return False
    return bool(summarize_email_mime_attachments(msg,af["bytes"]))

def source_email_attachment_expectation(afiles):
    summaries=summarize_source_emails(afiles)
    return len(summaries),sum(bool(source["attachments"]) for source in summaries)

def source_email_mime_attachment_expectation(afiles):
    return source_email_attachment_expectation(afiles)

def summarize_source_email(afiles):
    summaries = summarize_source_emails(afiles)
    if summaries:
        return summaries[0]
    return None

def decode_outlook_rtf(data):
    """Bounded MS-OXRTFCP decoding; corrupt or oversized bodies remain unavailable."""
    import struct
    import zlib
    if len(data)<16:
        raise ValueError("Truncated Outlook RTF header")
    packed,size,kind,checksum=struct.unpack_from("<IIII",data)
    if packed+4!=len(data) or size>16*1024*1024:
        raise ValueError("Outlook RTF size is invalid or exceeds the inspection limit")
    if kind==0x414c454d:
        if len(data)-16<size:
            raise ValueError("Truncated uncompressed Outlook RTF")
        return data[16:16+size]
    if kind!=0x75465a4c:
        raise ValueError("Unknown Outlook RTF compression")
    if (zlib.crc32(data[16:],0xffffffff)^0xffffffff)!=checksum:
        raise ValueError("Outlook RTF checksum mismatch")
    preload=(b"{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss "
             b"\\fmodern \\fscript \\fdecor MS Sans SerifSymbolArialTimes New RomanCourier"
             b"{\\colortbl\\red0\\green0\\blue0\n\r\\par \\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx")
    dictionary=bytearray(4096);dictionary[:len(preload)]=preload
    write=len(preload);position=16;output=bytearray()
    while len(output)<size and position<len(data):
        flags=data[position];position+=1
        for bit in range(8):
            if len(output)>=size:
                break
            if position>=len(data):
                raise ValueError("Truncated compressed Outlook RTF")
            if not flags&(1<<bit):
                value=data[position];position+=1
                output.append(value);dictionary[write]=value;write=(write+1)&4095
            else:
                if position+1>=len(data):
                    raise ValueError("Truncated Outlook RTF dictionary reference")
                token=(data[position]<<8)|data[position+1];position+=2
                offset=token>>4;length=(token&15)+2
                if offset==write:
                    raise ValueError("Outlook RTF ended before its declared size")
                for index in range(min(length,size-len(output))):
                    value=dictionary[(offset+index)&4095]
                    output.append(value);dictionary[write]=value;write=(write+1)&4095
    if len(output)!=size:
        raise ValueError("Truncated Outlook RTF body")
    return bytes(output)

def outlook_storage_content(storage,prefix=(),standalone=False):
    content={}
    for path in storage.listdir(streams=True,storages=True):
        if tuple(path[:len(prefix)])!=tuple(prefix) or len(path)<=len(prefix):
            continue
        relative=path[len(prefix):]
        key="/".join(relative)
        if storage.get_type(path)==1:
            content[key+"/"]=""
            continue
        data=storage.openstream(path).read()
        if standalone and relative==["__properties_version1.0"]:
            if len(data)<32:
                raise ValueError("Truncated standalone MSG property header")
            data=data[:24]+data[32:]
        content[key]=sha256hex(data)
    return content

def standalone_outlook_content(data):
    import olefile
    with olefile.OleFileIO(BytesIO(data),raise_defects=olefile.DEFECT_INCORRECT) as storage:
        if len(storage.listdir(streams=True,storages=True))>MAX_TREE_ITEMS:
            raise ValueError("MSG storage exceeds inspection limit")
        return outlook_storage_content(storage,standalone=True)

def inspect_outlook_source(data, native_core_only=False):
    """Read stored MSG transport headers and complete by-value attachment evidence.

    No transport headers are reconstructed from MAPI fields. Unsupported storage,
    ANSI encodings, nested messages and inline-resource inventories stay unavailable.
    """
    import struct
    try:
        import olefile
        with olefile.OleFileIO(BytesIO(data), raise_defects=olefile.DEFECT_INCORRECT) as storage:
            paths=storage.listdir()
            if len(paths)>MAX_TREE_ITEMS:
                return None
            names={tuple(path) for path in paths}
            consumed=0
            def read(path):
                nonlocal consumed
                if tuple(path) not in names:
                    return None
                size=storage.get_size(path)
                consumed+=size
                if size>len(data) or consumed>len(data):
                    raise ValueError("MSG stream inventory exceeds source bytes")
                return storage.openstream(path).read()
            def property_table(values,offset):
                if values is None or len(values)<offset:
                    raise ValueError("Incomplete MSG property inventory")
                padding=(len(values)-offset)%16
                if padding:
                    if any(values[-padding:]):
                        raise ValueError("Incomplete nonzero MSG property entry")
                    values=values[:-padding]
                return values
            properties=property_table(read(["__properties_version1.0"]),32)
            def integer(values,tag,offset):
                values=property_table(values,offset)
                matches=[struct.unpack_from("<I",values,index+8)[0] for index in range(offset,len(values),16)
                         if struct.unpack_from("<I",values,index)[0]==tag]
                if len(matches)>1:
                    raise ValueError("Repeated MSG property")
                return matches[0] if matches else None
            codepage=integer(properties,0x3ffd0003,32)
            def text(directory,tag):
                for suffix,encoding in (("001F","utf-16-le"),("001E",None)):
                    path=[*directory,"__substg1.0_"+tag+suffix]
                    if tuple(path) not in names:
                        continue
                    if encoding is None:
                        if codepage is None:
                            raise ValueError("MSG ANSI codepage is unavailable")
                        encoding="utf-8" if codepage==65001 else "cp"+str(codepage)
                    value=read(path).decode(encoding).rstrip("\0")
                    if "\0" in value:
                        raise ValueError("Embedded NUL in MSG string")
                    return value
                return ""
            def attachment_inventory():
                attachments=[]
                rendered_body=None
                directories=sorted({path[0] for path in paths if path[0].startswith("__attach_version1.0_")})
                if struct.unpack_from("<I",properties,20)[0]!=len(directories):
                    raise ValueError("MSG attachment inventory is incomplete")
                for directory in directories:
                    props=read([directory,"__properties_version1.0"])
                    method=integer(props,0x37050003,8)
                    if method==5:
                        prefix=(directory,"__substg1.0_3701000D")
                        if not storage.exists(list(prefix)) or storage.get_type(list(prefix))!=1:
                            raise ValueError(f"Embedded Outlook message storage is missing: {directory}")
                        content=outlook_storage_content(storage,prefix)
                        if not storage.exists([*prefix,"__nameid_version1.0"]) and storage.exists(["__nameid_version1.0"]):
                            content["__nameid_version1.0/"]=""
                            content.update({"__nameid_version1.0/"+key:value for key,value in outlook_storage_content(storage,("__nameid_version1.0",)).items()})
                        filename=text([directory],"3707") or text([directory],"3704")
                        if filename and not filename.lower().endswith(".msg"):
                            filename+=".msg"
                        attachments.append({"filename":filename,"original_filename":filename,"original_filenames":[filename] if filename else [],
                            "content_type":"application/vnd.ms-outlook","content_type_available":True,"size_in_bytes":-1,"checksum":"","nested_content":content})
                        continue
                    if method!=1:
                        raise ValueError(f"MSG attachment {directory} uses unsupported storage method {method}")
                    cid=text([directory],"3712")
                    payload=read([directory,"__substg1.0_37010102"])
                    content_type=text([directory],"370E")
                    if payload is None:
                        raise ValueError(f"MSG attachment payload is missing: {directory}")
                    filename=text([directory],"3707") or text([directory],"3704")
                    if cid or filename:
                        if rendered_body is None:
                            html=read(["__substg1.0_10130102"])
                            if html is not None:
                                rendered_body=html.decode("latin1")
                            else:
                                rtf=read(["__substg1.0_10090102"])
                                rendered_body=decode_outlook_rtf(rtf).decode("latin1") if rtf is not None else ""
                        if any(reference and reference.isascii() and re.search(r"cid:['\"]?"+re.escape(reference)+r"(?=['\"\s>\\}]|$)",rendered_body)
                               for reference in (cid,filename)):
                            continue
                    attachments.append({"filename":filename,"original_filename":filename,
                        "original_filenames":[filename] if filename else [],"content_type":content_type,
                        "content_type_available":bool(content_type),
                        "size_in_bytes":len(payload),"message_id":"","checksum":md5hex(payload)})
                return attachments
            inventory_error=""
            try:
                attachments=attachment_inventory()
            except (ValueError,LookupError) as error:
                attachments=None
                inventory_error=str(error)
            body_types=[media for prefix,media in (("1000","text/plain"),("1013","text/html"),("1009","text/rtf"))
                        if any(len(path)==1 and path[0].startswith("__substg1.0_"+prefix) for path in paths)]
            headers=text([],"007D")
            if native_core_only:
                message_class=text([],"001A").lower()
                if headers.strip() or not (message_class.startswith("ipm.note") or message_class=="ipm.post"):
                    return None
                values={"SizeInBytes":len(data),"NumberAttachments":len(attachments) if attachments is not None else None,
                        "attachments":attachments,"addresses":{},"raw_addresses":{},"body_media_types":body_types,"attachment_error":inventory_error,"reply":text([],"1042")}
                # Native rows establish mailbox occurrences, not transport-header order.
                recipients=sorted({path[0] for path in paths if path[0].startswith("__recip_version1.0_")})
                addresses={field:[] for field in ("To","Cc","Bcc")}
                native_names={field:[] for field in addresses}
                try:
                    if struct.unpack_from("<I",properties,16)[0]!=len(recipients):
                        raise ValueError("Incomplete MSG recipient inventory")
                    unavailable=set()
                    for directory in recipients:
                        kind=integer(read([directory,"__properties_version1.0"]),0x0c150003,8)
                        field={1:"To",2:"Cc",3:"Bcc"}.get(kind)
                        if field is None:
                            raise ValueError("Unknown MSG recipient type")
                        try:
                            address=text([directory],"39FE")
                            address_type=text([directory],"3002")
                            native_address=text([directory],"3003")
                            if not address and address_type.upper()=="SMTP":
                                address=native_address
                            if not address and not address_type and not native_address:
                                continue  # A display-only recipient has no stored mailbox.
                            if not address or address.lower().startswith("mailto:") or not valid_smtp_mailbox(address):
                                unavailable.add(field)
                            else:
                                addresses[field].append(address)
                                native_names[field].append(text([directory],"3001"))
                        except (ValueError,LookupError):
                            unavailable.add(field)
                    values["addresses"]={field:value for field,value in addresses.items() if field not in unavailable}
                    values["raw_addresses"]={field:{"names":native_names[field],"display":text([],tag)}
                                             for field,tag in (("To","0E04"),("Cc","0E03"),("Bcc","0E02"))}
                except (ValueError,LookupError):
                    pass  # Partial rows do not prove that other recipients are absent.
                try:
                    address=text([],"5D02")
                    represented=text([],"0065")
                    if not address and text([],"0064").upper()=="SMTP":
                        address=represented
                    # Never substitute a delegate's identity for a different author.
                    sender=text([],"0C1F")
                    if not address and (not represented or represented==sender):
                        address=text([],"5D01")
                        if not address and text([],"0C1E").upper()=="SMTP":
                            address=sender
                    name=text([],"0042")
                    if not name and (not represented or represented==sender):
                        name=text([],"0C1A")
                    if address and not address.lower().startswith("mailto:") and valid_smtp_mailbox(address):
                        values["addresses"]["From"]=[address]
                        values["raw_addresses"]["From"]={"names":[name],"display":name}
                    elif not address and not represented and not sender and not name:
                        values["addresses"]["From"]=[]
                        values["raw_addresses"]["From"]={"names":[],"display":""}
                except (ValueError,LookupError):
                    pass
                for tag,field in (("0037","Subject"),("1035","messageid")):
                    value=text([],tag)
                    values[field]=value
                times=[struct.unpack_from("<Q",properties,index+8)[0] for index in range(32,len(properties),16)
                       if struct.unpack_from("<I",properties,index)[0]==0x00390040]
                if len(times)>1:
                    return None
                if times and times[0]:
                    seconds,ticks=divmod(times[0],10000000)
                    try:
                        instant=datetime(1601,1,1,tzinfo=timezone.utc)+timedelta(seconds=seconds)
                        values["Sent"]=instant.strftime("%Y-%m-%dT%H:%M:%S")+("."+f"{ticks:07d}".rstrip("0") if ticks else "")+"Z"
                    except (OverflowError,ValueError):
                        pass
                return values
            if not headers.strip():
                return None
            body_types=[]
            for prefix,media in (("1000","text/plain"),("1013","text/html"),("1009","text/rtf")):
                if any(len(path)==1 and path[0].startswith("__substg1.0_"+prefix) for path in paths):
                    body_types.append(media)
            return {"headers":headers.encode("utf-8"),"attachments":attachments,"body_media_types":body_types,"attachment_error":inventory_error}
    except (ImportError,OSError,ValueError,LookupError,struct.error):
        return None

def native_outlook_summary(source,native):
    """Keep native facts separate from transport headers, which are not invented."""
    attachments=native["attachments"] or []
    return {"native_outlook":native,"native_source":dict(source),
        "subject":native.get("Subject",""),"message_id":native.get("messageid",""),
        "sent_norm":native.get("Sent",""),"sent_raw":"","sent_present":False,
        "raw_to":"","raw_from":"","raw_cc":"","raw_bcc":"","in_reply_to":native.get("reply",""),
        "native_present_fields":{"raw_"+field.lower() for field in ("To","From","Cc","Bcc")
            if native.get("addresses",{}).get(field) or native.get("raw_addresses",{}).get(field,{}).get("display")},
        "body_media_types":native["body_media_types"],"attachments":attachments,"mime_attachments":attachments,
        "attachment_count":len(attachments),"mime_attachment_count":len(attachments),
        "attachment_inventory_available":native["attachments"] is not None,"attachment_error":native["attachment_error"],
        "uninspected_email_candidates":[] if native["attachments"] is not None else [{"kind":"uninspected_msg_attachment_inventory"}],
        "size_in_bytes":native["SizeInBytes"],"source_objgen":source.get("objgen"),
        "source_filename":source_file_name(source),"source_sha256":sha256hex(source.get("bytes") or b"")}

def summarize_source_emails(afiles):
    out = []
    for af in afiles:
        if af.get("afrelationship")!="Source":
            continue
        subtype=norm_text(af.get("subtype","")).lower()
        name=norm_text(af.get("ufilename") or af.get("filename") or af.get("tree_name","")).lower()
        if "_email_summary" in af:
            natives=[f for f in afiles if f.get("afrelationship")!="Source"]
            if natives:
                af["_email_summary"]["native_files"]=natives
            out.append(af["_email_summary"])
            continue
        outlook=None
        if subtype=="application/vnd.ms-outlook" or name.endswith(".msg"):
            outlook=inspect_outlook_source(af.get("bytes") or b"")
            if outlook is None:
                native=inspect_outlook_source(af.get("bytes") or b"",native_core_only=True)
                if native is None:
                    continue
                summary=native_outlook_summary(af,native)
                summary["native_files"]=[f for f in afiles if f.get("afrelationship")!="Source"]
                af["_email_summary"]=summary
                out.append(summary)
                continue
            source_headers=outlook["headers"]
        elif subtype in ("message/rfc822","message/global") or name.endswith(".eml"):
            source_headers=af["bytes"]
        else:
            continue
        try:
            msg=BytesParser(policy=policy.default).parsebytes(source_headers)
        except Exception:
            continue
        attachments=(outlook["attachments"] or []) if outlook is not None else summarize_email_attachments(msg,af["bytes"])
        mime_attachments=attachments
        mime_attachment_count=len(attachments)
        uninspected_email_candidates=[]
        if outlook is not None and outlook["attachments"] is None:
            uninspected_email_candidates.append({"kind":"uninspected_msg_attachment_inventory"})
        if msg.get_unixfrom():
            uninspected_email_candidates.append({"kind":"mailbox_envelope"})
        for attachment in attachments:
            media=media_type_signature(attachment.get("content_type",""))[0]
            filename=attachment.get("original_filename",attachment.get("filename",""))
            filenames=source_attachment_filenames(attachment)
            if media in {"message/rfc822","message/global","application/mbox","application/vnd.ms-outlook"} or any(
                    name.lower().endswith((".eml",".msg",".mbox",".mbx",".pst",".ost",".nsf")) for name in filenames):
                uninspected_email_candidates.append({"kind":"mail_attachment","filename":filename,
                                                     "filenames":sorted(filenames),
                                                     "content_type":attachment.get("content_type","")})
        raw_headers={}
        header_occurrences=[]
        for header_name,header_value in msg.raw_items():
            try:
                # BytesParser preserves original eight-bit header octets using
                # surrogateescape. Restore valid RFC6532 UTF-8 before comparison,
                # without guessing a charset for malformed/legacy byte values.
                header_value=header_value.encode("utf-8","surrogateescape").decode("utf-8")
            except UnicodeError:
                pass
            header=header_name.lower()
            values=raw_headers.setdefault(header,[])
            values.append(header_value)
            header_occurrences.append({"index":len(header_occurrences)+1,"name":header_name,
                                       "header":header,"occurrence":len(values),"raw":header_value})
        def raw_header(name):
            return clean_header_text(", ".join(raw_headers.get(name.lower(),[])))
        sent_raw=raw_header("Date")
        sent_norm=""
        sent_normalization_unavailable=False
        if sent_raw:
            try:
                parsed=parse_source_datetime(sent_raw)
                sent_norm=parsed.isoformat() if parsed is not None else ""
                if parsed is not None and not looks_like_xmp_datetime(sent_norm):
                    # An RFC numeric offset may exceed XSD's +/-14-hour range.
                    # Preserve the instant using UTC when datetime supports it.
                    sent_norm=parsed.astimezone(timezone.utc).isoformat()
                if parsed is None:
                    sent_normalization_unavailable=source_date_exceeds_parser_range(sent_raw)
            except OverflowError:
                sent_norm=""
                sent_normalization_unavailable=True
            except Exception:
                sent_norm=""
        def parse_header_agents(name):
            vals=[]
            # Parse structure before decoding display-name encoded words. A
            # quoted local part contains mailbox data, including repeated spaces;
            # an encoded-word-looking addr-spec is literal address data as well.
            # Unfold only line breaks before whitespace. The address parser can
            # misread a folded quoted display name as a mailbox; significant
            # spaces inside quoted local parts must remain intact.
            address_values=[re.sub(r"\r?\n(?=[ \t])","",value) for value in raw_headers.get(name.lower(),[])]
            quoted_names=[value for value in address_values if re.fullmatch(r'\s*"(?:\\.|[^"\\])*"\s*',value)]
            # A whole quoted display-name token is not an addr-spec, even when
            # its text contains @. Keep it non-SMTP so an exact Raw field can
            # preserve it without inventing an Agent mailbox.
            for value in quoted_names:
                vals.append({"name":"","mbox":"mailto:"+value.strip()})
            for disp,addr in getaddresses([value for value in address_values if value not in quoted_names]):
                addr=addr.strip(" \t\r\n")
                disp=decoded_header_text(disp)
                if not addr:
                    continue
                vals.append({"name":disp,"mbox":addr if addr.lower().startswith("mailto:") else "mailto:"+addr})
            return vals
        summary={
            "to_agents":parse_header_agents("To"),
            "from_agents":parse_header_agents("From"),
            "cc_agents":parse_header_agents("Cc"),
            "bcc_agents":parse_header_agents("Bcc"),
            "raw_to":raw_header("To"),
            "raw_from":raw_header("From"),
            "raw_cc":raw_header("Cc"),
            "raw_bcc":raw_header("Bcc"),
            "subject":raw_header("Subject"),
            "message_id":raw_header("Message-ID"),
            "in_reply_to":raw_header("In-Reply-To"),
            "sent_raw":clean_header_text(sent_raw),
            "sent_present":"date" in raw_headers,
            "sent_norm":sent_norm,
            "sent_normalization_unavailable":sent_normalization_unavailable,
            "header_values":raw_headers,
            "header_occurrences":header_occurrences,
            "header_parse_defects":[type(defect).__name__ for defect in msg.defects if isinstance(defect,
                (HeaderDefect,FirstHeaderLineIsContinuationDefect,MisplacedEnvelopeHeaderDefect,MissingHeaderBodySeparatorDefect))],
            "other_parse_defects":[] if outlook is not None else [type(defect).__name__ for defect in msg.defects if not isinstance(defect,
                (HeaderDefect,FirstHeaderLineIsContinuationDefect,MisplacedEnvelopeHeaderDefect,MissingHeaderBodySeparatorDefect))],
            "uninspected_email_candidates":uninspected_email_candidates,
            "body_media_types":sorted(outlook["body_media_types"] if outlook is not None else source_body_media_types(msg)),
            "attachment_inventory_available":outlook is None or outlook["attachments"] is not None,
            "attachment_error":outlook.get("attachment_error","") if outlook else "",
            "attachment_count":len(attachments),
            "mime_attachment_count":mime_attachment_count,
            "attachments":attachments,
            "mime_attachments":mime_attachments,
            "size_in_bytes":len(af.get("bytes") or b""),
            "source_objgen":af.get("objgen"),
            "source_filename":af.get("ufilename") or af.get("filename") or af.get("tree_name", ""),
            "source_sha256":sha256hex(af.get("bytes") or b""),
        }
        summary["native_files"]=[f for f in afiles if f.get("afrelationship")!="Source"]
        af["_email_summary"]=summary
        out.append(summary)
    return out

def has_pdfa3_eapdf_extension_schema(xmp_bytes):
    text=xmp_bytes.decode("utf-8","ignore").lower()
    return "pdfaextension:schemas" in text and "http://www.pdfa.org/eapdf/ns/id/" in text

def looks_like_xmp_date(value):
    if not is_xmp_text(value):
        return False
    match=re.fullmatch(r"([0-9]{4})(?:-([0-9]{2})(?:-([0-9]{2})(?:T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]+))?)?(Z|[+-][0-9]{2}:[0-9]{2})?)?)?)?",value)
    if match is None:
        return False
    year,month,day,hour,minute,second,_fraction,zone=match.groups()
    month,day=int(month or 1),int(day or 1)
    if not 1<=month<=12 or not 1<=day<=monthrange(int(year),month)[1]:
        return False
    if int(hour or 0)>23 or int(minute or 0)>59 or int(second or 0)>59:
        return False
    return not zone or zone=="Z" or (int(zone[1:3])<=23 and int(zone[4:6])<=59)

def ts32005_declaration_assessment(xmp_bytes):
    result={"namespace":NS_PDFD,"required_identifier":ISO32005_IDENTIFIER,
            "matched_declarations":0,"errors":[]}
    try:
        root=ET.fromstring(xmp_bytes)
    except (ET.ParseError,ValueError):
        result["errors"].append("Document XMP cannot be parsed")
        return result
    values=[xmp_semantic_value(node) for node in xmp_property_nodes(root,NS_PDFD,"declarations")]
    values.extend(desc.get(qn(NS_PDFD,"declarations")) for desc in xmp_descriptions(root)
                  if qn(NS_PDFD,"declarations") in desc.attrib)
    def uri(value):
        return isinstance(value,str) and bool(re.match(r"[A-Za-z][A-Za-z0-9+.-]*:",value)) and not any(char.isspace() for char in value)
    for value in values:
        if not isinstance(value,XmpArray) or value.kind!="Bag":
            result["errors"].append("declarations must be an unordered array of structures")
            continue
        for record in value:
            if not isinstance(record,dict):
                result["errors"].append("A declaration entry must be a structure")
                continue
            target=record.get(NS_PDFD+":conformsTo")
            if not uri(target):
                result["errors"].append("Declaration conformsTo must be a URI value")
            elif target==ISO32005_IDENTIFIER:
                result["matched_declarations"]+=1
            claims=record.get(NS_PDFD+":claimData")
            if claims is None:
                continue
            if not isinstance(claims,XmpArray) or claims.kind!="Bag" or any(not isinstance(claim,dict) for claim in claims):
                result["errors"].append("Supplied claimData must be an unordered array of structures")
                continue
            for claim in claims:
                for field in ("claimBy","claimCredentials"):
                    key=NS_PDFD+":"+field
                    if key in claim and not is_xmp_text(claim[key]):
                        result["errors"].append(field+" must be normal text when supplied")
                if NS_PDFD+":claimDate" in claim and not looks_like_xmp_date(claim[NS_PDFD+":claimDate"]):
                    result["errors"].append("claimDate must be an XMP Date when supplied")
                if NS_PDFD+":claimReport" in claim and not uri(claim[NS_PDFD+":claimReport"]):
                    result["errors"].append("claimReport must be a URI when supplied")
    if not values:
        result["errors"].append("No document-level declarations property uses the canonical namespace")
    return result

def has_ts32005_declaration(xmp_bytes):
    result=ts32005_declaration_assessment(xmp_bytes)
    return bool(result["matched_declarations"]) and not result["errors"]

def source_datetime_text(value):
    text=norm_text(value)
    for depth in range(32):
        reduced=re.sub(r"\([^()]*\)"," ",text)
        if reduced==text:
            break
        text=reduced
    return norm_text(text)

def source_date_exceeds_parser_range(value):
    """A valid calendar-cycle proxy identifies the bounded extended-year gap.

    This establishes parser unavailability, not a normalized original instant.
    It never substitutes the proxy year in source evidence or comparisons.
    """
    text=source_datetime_text(value)
    if looks_like_xmp_datetime(text) and re.match(r"-?[0-9]{5,}-",text):
        return True
    year=re.search(r"\b([0-9]{5,})(?= [0-9]{2}:[0-9]{2})",text)
    if year is None:
        return False
    digits=year.group(1).lstrip("0") or "0"
    if len(digits)<=4 and int(digits)<1900:
        return False
    cycle=str(2000+int(digits[-4:])%400)
    return parse_source_datetime(text[:year.start()]+cycle+text[year.end():]) is not None

def parse_source_datetime(value):
    text=source_datetime_text(value)
    if not text:
        return None
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}T[^\s]+",text):
        return parse_xmp_datetime(text)
    pattern=(r"(?:(Mon|Tue|Wed|Thu|Fri|Sat|Sun),?\s+)?\d{1,2}\s+"
             r"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+(?:\d{4}|\d{2})\s+"
             r"\d{2}:\d{2}(?::\d{2})?\s+(?:[+-]\d{4}|UT|UTC|GMT|EST|EDT|CST|CDT|MST|MDT|PST|PDT)")
    match=re.fullmatch(pattern,text,re.I)
    if not match:
        return None
    try:
        # RFC 5322 section 4.3 uses 1950-2049, not Python's 1969-2068 window.
        short_year=re.search(r"\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+(\d{2})(?=\s)",text,re.I)
        if short_year:
            year=int(short_year.group(1))
            text=text[:short_year.start(1)]+str(year+(2000 if year<50 else 1900))+text[short_year.end(1):]
        parsed=parsedate_to_datetime(text)
        if parsed.tzinfo is None:
            if text.endswith("-0000"):
                # RFC5322 3.3 still specifies UTC; only the originating local
                # timezone is unknown. Do not infer that local timezone.
                parsed=parsed.replace(tzinfo=timezone.utc)
            else:
                return None
        if match.group(1) and parsed.strftime("%a").lower()!=match.group(1).lower():
            return None
        return parsed
    except (ValueError,OverflowError,TypeError):
        return None

def source_body_media_types(message,depth=0):
    if depth>MAX_TREE_DEPTH:
        raise ValueError("Excessive source body MIME nesting")
    if email_part_looks_like_attachment(message):
        return set()
    if message.get_content_type().lower()=="multipart/encrypted":
        return {original_mime_content_type(message)}
    if message.get_content_maintype()!="multipart":
        content_type=original_mime_content_type(message)
        return {content_type if content_type.strip() else message.get_content_type()}
    parts=list(message.iter_parts())
    if message.get_content_subtype()=="related":
        start=norm_text(message.get_param("start") or "").strip("<>")
        roots=[part for part in parts if norm_text(part.get("Content-ID","")).strip("<>")==start] if start else parts[:1]
        parts=roots
    types=set()
    for part in parts:
        types.update(source_body_media_types(part,depth+1))
    return types

def declared_content_sets_are_nonbody(pdf,nodes,excluded_type="BodyRendering"):
    leaves=[node for node in nodes if node.get("start") is not None or node.get("end") is not None]
    pages=page_objgen_map(pdf)
    def known_leaf(node):
        value=node["dpm"].get("Mail_ContentSetType")
        if not isinstance(value,pikepdf.Name):
            return False
        try:
            content_type=bytes(value)[1:].decode("utf-8")
        except (UnicodeError,ValueError,pikepdf.PdfError):
            return False
        return content_type!=excluded_type and page_range_from_dpart(node,pages) is not None
    return bool(leaves) and not any(safe_name(node["dpm"].get("Mail_ContentSetType"))==excluded_type for node in nodes) and all(known_leaf(node) for node in leaves)

def independent_content_ownership(pdf,xroot,report):
    """Connect actual logical content to leaves without using the DPM GUID tested."""
    cached=getattr(report,"_independent_content_ownership",None)
    if cached is not None:
        return cached
    _root,_rootnode,nodes=get_dpart_nodes(pdf)
    result={"nodes":nodes,"owners":defaultdict(set),"document_wide":set(),"rendered_guids":set(),"complete":False}
    report._independent_content_ownership=result
    elements=[]
    structure=dict_get(pdf.Root,"/StructTreeRoot",None)
    children=dict_get(structure,"/K",None)
    roots=children if is_pdf_array(children) else [children] if children is not None else []
    try:
        for child in roots:
            walk_struct_elems(child,elements,None,class_map=dict_get(structure,"/ClassMap",None))
        targets=[item for item in elements if item["type"] in {"Mail_Message","Mail_ContentGroup","Mail_ContentSet"}]
        rows=structure_content_dpm_assessments(pdf,targets,nodes) if targets else []
        report._structure_content_dpm_rows=rows
        if any(item["code"]=="STRUCTURE_CORRESPONDENCE" and item["level"]=="FAIL" for item in report.items):
            return result
        pages=page_objgen_map(pdf)
        page_leaves=defaultdict(set)
        work=0
        for node in nodes:
            if node["child_count"]:
                continue
            bounds=page_range_from_dpart(node,pages)
            if bounds is None:
                return result  # An unreadable range may overlap any apparent owner.
            for number in range(bounds[0],bounds[1]+1):
                work+=1
                if work>MAX_TREE_ITEMS:
                    return result
                page_leaves[number].add(node["objgen"])
        records=flatten_email_records(xroot) if xroot is not None else []
        guid_counts=defaultdict(int)
        for record in records:
            guid_counts[guid_value(record.get("Mail_GUID",""))]+=1
        by_object={item["objgen"]:item for item in elements}
        global_pages=document_wide_content_pages(pdf,nodes)
        for target,row in zip(targets,rows):
            if target.get("attribute_errors") or row["unavailable"] or not row["references"]:
                continue
            connected=set()
            for reference in row["references"]:
                work+=1
                choices=reference["pages"]
                if work>MAX_TREE_ITEMS:
                    raise ValueError("Independent ownership exceeds configured traversal limits")
                if len(choices)!=1 or len(page_leaves[choices[0]])!=1:
                    connected.clear();break
                connected.update(page_leaves[choices[0]])
            if not connected:
                continue
            if target["type"]=="Mail_Message":
                values=[dict_get(prop,"/V",None) for prop in parse_userproperties(target["attrs"])
                        if userprop_name(prop)=="Mail_GUID"]
                if not values or any(not isinstance(value,pikepdf.String) for value in values):
                    continue
                guids={str(value) for value in values}
                if len(guids)==1 and guid_counts.get(next(iter(guids)),0)==1:
                    for leaf in connected:
                        result["owners"][leaf].update(guids)
            elif target["type"]=="Mail_ContentSet" and all(ref["pages"][0] in global_pages for ref in row["references"]):
                current=by_object.get(target["parent_objgen"]);seen=set();inside_message=False
                while current is not None:
                    if current["objgen"] in seen or len(seen)>MAX_TREE_DEPTH:
                        inside_message=True;break
                    seen.add(current["objgen"])
                    if current["type"]=="Mail_Message":
                        inside_message=True;break
                    current=by_object.get(current["parent_objgen"])
                if not inside_message:
                    result["document_wide"].update(connected)
    except (ValueError,TypeError,UnicodeError,pikepdf.PdfError):
        result["owners"].clear();result["document_wide"].clear()
    return result


def dpart_ancestor_guid(node,by_object):
    if node["parent"] is not None and pdf_object_identity(dict_get(node["node"],"/Parent",None))!=pdf_object_identity(node["parent"]):
        return None,"unavailable"
    current=by_object.get(node["parent_objgen"]);seen=set();guids=set()
    while current is not None:
        if current["objgen"] in seen or len(seen)>MAX_TREE_DEPTH:
            return None,"unavailable"
        seen.add(current["objgen"])
        value=current["dpm"].get("Mail_GUID")
        if value is not None:
            if not isinstance(value,pikepdf.String):
                return None,"unavailable"
            try:
                guid=str(value)
            except (ValueError,UnicodeError,pikepdf.PdfError):
                return None,"unavailable"
            if not guid:
                return None,"missing"
            guids.add(guid)
        if current["parent"] is not None and pdf_object_identity(dict_get(current["node"],"/Parent",None))!=pdf_object_identity(current["parent"]):
            return None,"unavailable"
        current=by_object.get(current["parent_objgen"])
    return (next(iter(guids)),"present") if len(guids)==1 else (None,"conflicting" if guids else "missing")


def evidence_unavailable(report,code,message,details):
    report.warn(code,message,dict(details,assessment="unavailable"))
    report.warn("VALIDATION_INCOMPLETE",message,{"rules":[code],**details,"assessment":"unavailable"})


def check_content_ownership_evidence(pdf,xroot,report):
    return _structure_rules.check_content_ownership_evidence(_structure_rules_dependencies(), pdf, xroot, report)


def check_rendered_asset_counts(xroot,afiles,report,ownership):
    return _media_rules.check_rendered_asset_counts(_media_rules_dependencies(), xroot, afiles, report, ownership)


def check_native_media_parameters(file,expected,report,evidence="associated XMP"):
    return _media_rules.check_native_media_parameters(_media_rules_dependencies(), file, expected, report, evidence)


def original_attachment_media_types(file,source):
    if source is None or not source.get("attachment_inventory_available",True):
        return set()
    checksum=str(file.get("md5","")).lower()
    if not re.fullmatch(r"[0-9a-f]{32}",checksum):
        return set()
    index=source.get("_attachment_media_by_payload")
    if index is None:
        attachments=source.get("mime_attachments",source.get("attachments",[]))
        if len(attachments)>MAX_TREE_ITEMS:
            return set()
        index=defaultdict(set)
        for attachment in attachments:
            # Readable MSG properties may prove that no original MIME value was
            # supplied. Keep that absence distinct from an uninspected payload.
            if isinstance(attachment.get("content_type"),str):
                index[(str(attachment.get("checksum","")).lower(),attachment.get("size_in_bytes"))].add(attachment["content_type"])
        source["_attachment_media_by_payload"]=index
    return set(index.get((checksum,file.get("size")),set()))


def check_body_media_types(pdf,xroot,afiles,report,profile=None):
    return _media_rules.check_body_media_types(_media_rules_dependencies(), pdf, xroot, afiles, report, profile)


def check_attachment_rendering_media(pdf,records,sources,bindings,nodes,ownership,report,afiles,original_omitted=False):
    return _media_rules.check_attachment_rendering_media(_media_rules_dependencies(), pdf, records, sources, bindings, nodes, ownership, report, afiles, original_omitted)

def core_field_text_evidence(pdf,xroot,page_analysis):
    if xroot is None or not page_analysis:
        return [],[{"reason":"No extractable page text or email metadata"}]
    _root,_rootnode,nodes=get_dpart_nodes(pdf)
    bodies=collect_body_content_sets(pdf,nodes)
    pages={page["page"]:page["text"] for page in page_analysis}
    verified=[]
    unresolved=[]
    def comparable(value):
        return re.sub(r'[\s<>"\u201c\u201d]+',"",decoded_header_text(value))
    for record in flatten_email_records(xroot):
        relevant=[body for body in bodies if identities_match(record,body["identity"])]
        if not relevant:
            unresolved.append({"identity":identity_fields(record),"reason":"No owning body content set"})
        for body in relevant:
            text="\n".join(pages.get(number,"") for number in body["pages"][:3])
            fields={}
            active=None
            expected_fields={label:record.get(property_name,"") for label,property_name in
                             (("From","Raw-From"),("To","Raw-To"),("Cc","Raw-Cc"),("Bcc","Raw-Bcc"),
                              ("Subject","Subject"),("Message-ID","messageid"))}
            expected_fields["Message-ID"]=first_present(record,"messageid","Message-ID")
            def date_is_text(value):
                raw_sent=record.get("Raw-Sent","")
                if raw_sent:
                    return comparable(value).startswith(comparable(raw_sent))
                parsed=parse_source_datetime(value)
                return parsed is not None and parsed==parse_xmp_datetime(record.get("Sent",""))
            for line in text.splitlines()[:160]:
                match=re.match(r"^(From|To|Cc|Bcc|Date|Sent|Subject|Message-ID|In-Reply-To|Folder|Archive Global ID|Archive Email\(s\)):\s*(.*)$",line)
                if match:
                    active=match.group(1)
                    fields[active]=match.group(2)
                elif active:
                    expected=expected_fields.get(active,"")
                    if (expected and comparable(fields[active]).startswith(comparable(expected))) or (active in ("Date","Sent") and date_is_text(fields[active])):
                        break
                    fields[active]+=" "+line
                    if expected and not comparable(expected).startswith(comparable(fields[active])) and not comparable(fields[active]).startswith(comparable(expected)):
                        break
                if (date_is_text(fields.get("Date",fields.get("Sent","")))
                    and all(not expected or comparable(fields.get(label,"")).startswith(comparable(expected))
                            for label,expected in expected_fields.items())):
                    break
            missing=[]
            for label in ("From","To","Cc","Bcc"):
                if record.get(label) and not record.get("Raw-"+label):
                    missing.append(label+" (unrecognized structured address rendering)")
            for label,expected in expected_fields.items():
                if expected and not comparable(fields.get(label,"")).startswith(comparable(expected)):
                    missing.append(label)
            date_value=fields.get("Date",fields.get("Sent",""))
            if not date_is_text(date_value):
                missing.append("Date/Sent")
            if missing:
                unresolved.append({"identity":identity_fields(record),"pages":body["pages"][:3],"unverified_fields":missing})
            else:
                verified.append({"identity":identity_fields(record),"pages":body["pages"][:3],"method":"recognized header labels and XMP-consistent extracted text"})
    return verified,unresolved

def structure_field_text_sequences(pdf):
    root=dict_get(pdf.Root,"/StructTreeRoot",None)
    elements=[]
    if root is None:
        return []
    children=dict_get(root,"/K",[])
    for child in children if is_pdf_array(children) else [children]:
        walk_struct_elems(child,elements)
    fields=[element for element in elements if element["type"] in ("Mail_FieldName","Mail_FieldValue")]
    if not fields:
        return []
    content={}
    locations=defaultdict(set)
    page_numbers={pdf_object_identity(page.obj):number for number,page in enumerate(pdf.pages,start=1)}
    stream_pages=defaultdict(set)
    for page in pdf.pages:
        streams=dict_get(page.obj,"/Contents",[])
        for stream in streams if is_pdf_array(streams) else [streams]:
            stream_pages[pdf_object_identity(stream)].add(pdf_object_identity(page.obj))
    for number,page in enumerate(pdf.pages,start=1):
        for sequence in analyze_page_content(page)["sequences"]:
            if sequence["mcid"] is not None:
                key=(pdf_object_identity(sequence["scope"]),sequence["mcid"])
                content[key]=(number,sequence["has_text_object"])
                locations[key].add(number)
    def resolved_reference(scope,mcid,page):
        identity=pdf_object_identity(scope)
        page_id=pdf_object_identity(page)
        if identity in stream_pages:
            aliases=stream_pages[identity]
            if page_id in aliases:
                identity=page_id
            elif len(aliases)==1:
                identity=next(iter(aliases))
        choices=locations.get((identity,mcid),set())
        number=page_numbers.get(page_id)
        if number is None and len(choices)==1:
            number=next(iter(choices))
        ref={"page":number,"scope":identity,"mcid":mcid}
        if not isinstance(mcid,int) or isinstance(mcid,bool) or mcid<0:
            ref["reason"]="Field MCID is not a nonnegative integer"
        elif number is None or number not in choices:
            ref["reason"]="Field content is missing or ambiguous on its declared page"
        return ref
    def text_references(element,inherited_page,seen,resolved,depth=0):
        visit_tree_node(element,seen,len(seen),depth,"Core Field structure")
        page=dict_get(element,"/Pg",inherited_page)
        children=dict_get(element,"/K",[])
        references=[]
        for child in children if is_pdf_array(children) else [children]:
            if isinstance(child,int) and not isinstance(child,bool):
                references.append(content.get((pdf_object_identity(page),child),(None,False)))
                resolved.append(resolved_reference(page,child,page))
            elif dict_get(child,"/S",None) is not None:
                references.extend(text_references(child,page,seen,resolved,depth+1))
            elif safe_name(dict_get(child,"/Type",None))=="MCR":
                scope=dict_get(child,"/Stm",dict_get(child,"/Pg",page))
                references.append(content.get((pdf_object_identity(scope),dict_get(child,"/MCID",None)),(None,False)))
                resolved.append(resolved_reference(scope,dict_get(child,"/MCID",None),dict_get(child,"/Pg",page)))
        return references
    sequences=[]
    for field in fields:
        page=None
        ancestor=field["elem"]
        seen=set()
        while ancestor is not None and page is None:
            visit_tree_node(ancestor,seen,len(seen),len(seen),"Core Field page inheritance")
            page=dict_get(ancestor,"/Pg",None)
            ancestor=dict_get(ancestor,"/P",None)
        resolved=[]
        references=text_references(field["elem"],page,set(),resolved)
        sequences.append({"page":next((page for page,has_text in references if page is not None),None),
                          "field_objgen":field["objgen"],"tag":field["type"],"has_text_object":any(has_text for page,has_text in references),"content_refs":resolved})
    return sequences

def decoded_source_header(raw):
    try:
        # The standard decoder repairs some malformed payloads. Such a repair
        # cannot establish source fidelity or select a repeated occurrence.
        for word in re.finditer(r"=\?([^?]*)\?([^?]*)\?([^?]*)\?=",raw):
            charset,encoding,payload=word.groups()
            if not charset or any(char.isspace() for char in charset) or not payload or any(not 33<=ord(char)<=126 for char in payload):
                raise ValueError("Malformed encoded-word components")
            if encoding.upper()=="B":
                if len(payload)%4:
                    raise ValueError("Incomplete encoded-word base64 padding")
                base64.b64decode(payload,validate=True)
            elif encoding.upper()=="Q":
                if re.search(r"=(?![0-9A-Fa-f]{2})",payload):
                    raise ValueError("Malformed encoded-word Q escape")
            else:
                raise ValueError("Unsupported encoded-word transfer encoding")
        parts=decode_header(raw)
        for text,charset in parts:
            if isinstance(text,bytes):
                text.decode(charset or "ascii",errors="strict")
        value=str(make_header(parts))
    except (LookupError,UnicodeError,ValueError,HeaderParseError) as error:
        raise ValueError("Original header encoding cannot be decoded") from error
    if any(0xD800<=ord(char)<=0xDFFF for char in value):
        raise ValueError("Original header encoding cannot be decoded reliably")
    return value

def header_display_values_equal(header,actual,expected):
    if norm_text(actual)==norm_text(expected):
        return True
    if header=="date":
        left=parse_source_datetime(actual) or parse_xmp_datetime(actual)
        right=parse_source_datetime(expected) or parse_xmp_datetime(expected)
        if left is None or right is None:
            raise ValueError("Rendered date formatting cannot be compared as an instant")
        return left==right
    if header in ("from","to","cc","bcc","sender","reply-to"):
        def agents(value):
            result=getaddresses([value])
            if not result or any(not address or "@" not in address for name,address in result):
                raise ValueError("Rendered/source address formatting is not supported for comparison")
            return [(norm_text(name),address) for name,address in result]
        return agents(actual)==agents(expected)
    return False

def message_header_source_inventories(elements,xroot,afiles,source_bindings=None):
    records=flatten_email_records(xroot) if xroot is not None else []
    sources=summarize_source_emails(afiles)
    inventories=[]
    for owner in (element for element in elements if element["type"]=="Mail_Message"):
        item={"message_object":owner["objgen"],"assessment":"unavailable","occurrences":[]}
        inventories.append(item)
        try:
            identities=defaultdict(set)
            for prop in parse_userproperties(owner["attrs"]):
                name=userprop_name(prop)
                if name in ("Mail_GUID","Mail_MessageID"):
                    value=dict_get(prop,"/V",None)
                    if not isinstance(value,pikepdf.String):
                        raise ValueError("Message identity is not a PDF string")
                    identities[name].add(str(value))
            if any(len(values)>1 for values in identities.values()):
                raise ValueError("Enclosing message identity has conflicting values")
            guid=next(iter(identities.get("Mail_GUID",set())),"")
            message_id=next(iter(identities.get("Mail_MessageID",set())),"")
            owners=[record for record in records if (guid and identity_fields(record)["Mail_GUID"]==guid) or
                    (not guid and message_id and identity_fields(record)["Message-ID"]==message_id)]
            if len(owners)!=1:
                raise ValueError("Enclosing message has no unique XMP owner")
            item["Mail_GUID"]=identity_fields(owners[0])["Mail_GUID"]
            source=source_email_for_record(owners[0],records,sources,source_bindings)
            if source is None:
                raise ValueError("No unique inspectable retained source matches the XMP owner")
            item.update(source=source["source_filename"],source_sha256=source["source_sha256"])
            item["parse_defects"]=source.get("header_parse_defects",[])
            item["other_parse_defects"]=source.get("other_parse_defects",[])
            if "header_occurrences" not in source:
                raise ValueError("Ordered source header occurrences are unavailable")
            for occurrence in source["header_occurrences"]:
                row=dict(occurrence,decoded=None,matched_fields=[],candidate_fields=[])
                try:
                    row["decoded"]=decoded_source_header(row["raw"])
                except ValueError as error:
                    row["reason"]=str(error)
                if any(0xDC80<=ord(char)<=0xDCFF for char in row["raw"]):
                    # The parser uses surrogateescape for undecoded octets.
                    # Preserve them without putting surrogates in UTF-8 reports.
                    row["raw_bytes_base64"]=base64.b64encode(row["raw"].encode("utf-8","surrogateescape")).decode("ascii")
                    row["raw"]=None
                item["occurrences"].append(row)
            item["assessment"]="partial" if item["parse_defects"] else "available"
            if item["parse_defects"]:
                item["reason"]="Source parser recovery prevents a complete header inventory"
        except ValueError as error:
            item["reason"]=str(error)
    return inventories

def observed_header_source_inventory(elements,page_analysis,xroot,afiles,owner_ranges=(),source_inventory=None,source_bindings=None):
    observations={tuple(row["field_objgen"]):row for page in page_analysis or [] for row in page.get("rendered_fields",[])}
    by_object={element["objgen"]:element for element in elements if element["objgen"]}
    source_inventories=message_header_source_inventories(elements,xroot,afiles,source_bindings)
    by_owner={item["message_object"]:item for item in source_inventories}
    inventory=[]
    for field in (element for element in elements if element["type"]=="Mail_Field"):
        item={"field_object":field["objgen"],"comparison":"unavailable","ownership":"unavailable"}
        inventory.append(item)
        try:
            # Preserve observations independently of retained-source availability.
            children=[element for element in elements if element["parent_objgen"]==field["objgen"]]
            names=[element for element in children if element["type"]=="Mail_FieldName"]
            values=[element for element in children if element["type"]=="Mail_FieldValue"]
            refs=[ref for element in names+values for ref in observations.get(tuple(element["objgen"]),{}).get("content_refs",[])]
            item["pages"]=sorted({ref["page"] for ref in refs if isinstance(ref.get("page"),int)})
            if len(names)!=1 or len(values)>1:
                raise ValueError("Observed field name/value fragments do not have a unique supported grouping")
            def text(element):
                observation=observations.get(tuple(element["objgen"]),{})
                if observation.get("text") is None:
                    raise ValueError(observation.get("reason","No decoded text evidence exists for the field markup"))
                return observation["text"]
            label=norm_text(text(names[0])).rstrip(":").strip()
            item["name"]=label
            actual=text(values[0]) if values else ""
            item["value"]=actual
            owner=field
            visited=set()
            while owner is not None and owner["type"]!="Mail_Message":
                if owner["objgen"] in visited:
                    owner=None;break
                visited.add(owner["objgen"])
                owner=by_object.get(owner["parent_objgen"])
            if owner is None:
                raise ValueError("No enclosing Mail_Message identifies the observed field")
            item["message_object"]=owner["objgen"]
            source=by_owner[owner["objgen"]]
            if "Mail_GUID" in source:
                item["Mail_GUID"]=source["Mail_GUID"]
            owning_pages=sorted({number for span in owner_ranges
                                 if span["identity"].get("Mail_GUID") and span["identity"]["Mail_GUID"]==item.get("Mail_GUID")
                                 for number in span["pages"]})
            item["owner_pages"]=owning_pages
            if owning_pages and item["pages"]:
                item["ownership"]="within-owner-ranges" if set(item["pages"]).issubset(owning_pages) else "outside-owner-ranges"
            if source["assessment"]!="available":
                raise ValueError(source["reason"])
            item.update(source=source["source"],source_sha256=source["source_sha256"])
            if re.fullmatch(r"[!-9;-~]+",label) is None:
                raise ValueError("Rendered label is not a supported source header name")
            header=label.lower()
            originals=[row for row in source["occurrences"] if row["header"]==header]
            if header=="sent" and not originals:
                header="date"
                originals=[row for row in source["occurrences"] if row["header"]==header]
            item.update(source_header=header,source_header_count=len(originals))
            if not originals:
                raise ValueError("Original header is absent; no source occurrence is inferred")
            matches=[]
            unknown=[]
            for row in originals:
                try:
                    if row["decoded"] is None:
                        raise ValueError(row["reason"])
                    if not values and norm_text(row["decoded"]):
                        raise ValueError("No custom value markup exists for a nonempty source header; untagged value text is not inferred")
                    if header_display_values_equal(header,actual,row["decoded"]):
                        matches.append(row)
                        row["candidate_fields"].append(field["objgen"])
                except ValueError as error:
                    unknown.append((row,str(error)))
            item["candidate_occurrences"]=[row["occurrence"] for row in matches]
            item["unresolved_occurrences"]=[row["occurrence"] for row,reason in unknown]
            if unknown:
                raise ValueError(unknown[0][1])
            if len(originals)>1 and len(matches)!=1:
                raise ValueError("Repeated source headers have no unique supported display match; no occurrence is guessed")
            selected=matches[0] if matches else originals[0]
            item.update(expected=selected["decoded"],source_index=selected["index"],source_occurrence=selected["occurrence"])
            equal=bool(matches)
            if item["ownership"]=="outside-owner-ranges":
                equal=False
                item["reason"]="Field content references pages outside its enclosing message's declared DPart ranges"
            item["comparison"]="equal" if equal else "different"
            if equal:
                selected["matched_fields"].append(field["objgen"])
        except ValueError as error:
            item["reason"]=str(error)
    if source_inventory is not None:
        source_inventory.extend(source_inventories)
    return inventory

def check_tagging_recommendations(pdf,dpart_nodes,elements,report,xroot=None,afiles=(),page_analysis=None):
    return _structure_rules.check_tagging_recommendations(_structure_rules_dependencies(), pdf, dpart_nodes, elements, report, xroot, afiles, page_analysis)

def structure_content_dpm_assessments(pdf,elements,dpart_nodes):
    """Resolve connected DPart candidates without consulting tested attributes."""
    rows={item["objgen"]:{"objgen":item["objgen"],"references":[],"unavailable":[]} for item in elements}
    work=0
    reference_cache={}  # Page connections are independent of the enclosing tag being assessed.
    def tick(depth=0,items=1):
        nonlocal work
        work+=items
        if work>MAX_TREE_ITEMS or depth>MAX_TREE_DEPTH:
            raise ValueError("Structure content ownership exceeds configured traversal limits")
    try:
        page_numbers={pdf_object_identity(page.obj):number for number,page in enumerate(pdf.pages,1)}
        page_map=page_objgen_map(pdf)
        page_owners=defaultdict(set)
        unknown_owner=set()
        page_types=defaultdict(set)
        unknown_type=set()
        page_subtypes=defaultdict(set)
        unknown_subtype=set()
        ancestry={}
        for item in dpart_nodes:
            tick()
            parent=ancestry.get(item["parent_objgen"],(frozenset(),False)) if item["parent"] is not None else (frozenset(),False)
            guids,unknown=parent
            value=item["dpm"].get("Mail_GUID")
            if item["child_count"] and value is not None:
                tick(items=len(guids))
                try:
                    guid=guid_value(value) if isinstance(value,pikepdf.String) else ""
                except (UnicodeError,ValueError,pikepdf.PdfError):
                    guid=""
                if guid:guids=guids|{guid}
                else:unknown=True
            ancestry[item["objgen"]]=(guids,unknown)
            if item["child_count"]:
                continue
            bounds=page_range_from_dpart(item,page_map)
            if bounds is None:
                raise ValueError("DPart content ownership has an unresolved page range")
            raw_type=item["dpm"].get("Mail_ContentSetType")
            type_known=isinstance(raw_type,pikepdf.Name)
            if type_known:
                try:
                    content_type=bytes(raw_type)[1:].decode("utf-8")
                except UnicodeError:
                    type_known=False
            raw_subtype=item["dpm"].get("Mail_Subtype")
            subtype_known=isinstance(raw_subtype,pikepdf.Name)
            if subtype_known:
                try:
                    content_subtype=bytes(raw_subtype)[1:].decode("utf-8")
                except UnicodeError:
                    subtype_known=False
            for number in range(bounds[0],bounds[1]+1):
                tick()
                if len(guids)==1 and not unknown:page_owners[number].update(guids)
                else:unknown_owner.add(number)
                if type_known:page_types[number].add(content_type)
                else:unknown_type.add(number)
                if subtype_known:page_subtypes[number].add(content_subtype)
                else:unknown_subtype.add(number)
        locations=defaultdict(set)
        stream_pages=defaultdict(set)
        object_pages=defaultdict(set)
        for number,page in enumerate(pdf.pages,1):
            tick()
            contents=dict_get(page.obj,"/Contents",[])
            for stream in contents if is_pdf_array(contents) else [contents]:
                tick()
                if isinstance(stream,pikepdf.Stream):stream_pages[pdf_object_identity(stream)].add(pdf_object_identity(page.obj))
            for annotation in dict_get(page.obj,"/Annots",[]):
                tick()
                object_pages[pdf_object_identity(annotation)].add(number)
            try:
                analysis=analyze_page_content(page)
            except (ValueError,TypeError,pikepdf.PdfError):
                continue  # A declared page alone cannot certify a content connection.
            for identity in analysis["forms"]:
                tick()
                object_pages[identity].add(number)
            for sequence in analysis["sequences"]:
                tick()
                mcid=sequence["mcid"]
                if isinstance(mcid,int) and not isinstance(mcid,bool) and mcid>=0:
                    locations[(pdf_object_identity(sequence["scope"]),mcid)].add(number)
        for item in elements:
            row=rows[item["objgen"]]
            page=None
            ancestor=item["elem"]
            seen=set()
            while ancestor is not None and page is None:
                tick(len(seen))
                identity=pdf_object_identity(ancestor)
                if identity in seen:raise ValueError("Cyclic page inheritance for structure content")
                seen.add(identity)
                page=dict_get(ancestor,"/Pg",None)
                ancestor=dict_get(ancestor,"/P",None)
            seen=set()
            def walk(element,inherited_page,depth=0):
                tick(depth)
                identity=pdf_object_identity(element)
                if identity in seen:raise ValueError("Repeated structure content node")
                seen.add(identity)
                current_page=dict_get(element,"/Pg",inherited_page)
                children=dict_get(element,"/K",None)
                entries=[] if children is None else children if is_pdf_array(children) else [children]
                for child_index,child in enumerate(entries):
                    tick(depth)
                    if isinstance(child,pikepdf.Dictionary) and dict_get(child,"/S",None) is not None:
                        walk(child,current_page,depth+1)
                        continue
                    cache_key=(identity,child_index,pdf_object_identity(current_page))
                    if cache_key in reference_cache:
                        references,unavailable=reference_cache[cache_key]
                        row["references"].extend(references)
                        row["unavailable"].extend(unavailable)
                        continue
                    first_reference=len(row["references"])
                    first_unavailable=len(row["unavailable"])
                    try:
                        declared_page=current_page
                        if isinstance(child,int) and not isinstance(child,bool):
                            mcid=child;target=current_page
                        elif safe_name(dict_get(child,"/Type",None))=="MCR":
                            mcid=dict_get(child,"/MCID",None)
                            declared_page=dict_get(child,"/Pg",current_page)
                            target=dict_get(child,"/Stm",declared_page)
                        elif safe_name(dict_get(child,"/Type",None))=="OBJR":
                            declared_page=dict_get(child,"/Pg",current_page)
                            choices=set(object_pages.get(pdf_object_identity(dict_get(child,"/Obj",None)),set()))
                            target=None;mcid=None
                        else:
                            row["unavailable"].append({"reason":"Unsupported structure content reference"})
                            continue
                        if target is not None:
                            if not isinstance(mcid,int) or isinstance(mcid,bool) or mcid<0:
                                row["unavailable"].append({"reason":"Structure content MCID is unavailable"})
                                continue
                            scope=pdf_object_identity(target)
                            scopes=stream_pages.get(scope,{scope})
                            choices=set()
                            for candidate in scopes:
                                tick()
                                connected=locations.get((candidate,mcid),set())
                                tick(items=len(connected))
                                choices.update(connected)
                        elif safe_name(dict_get(child,"/Type",None))!="OBJR":
                            row["unavailable"].append({"reason":"Structure content scope is unavailable"})
                            continue
                        if declared_page is not None:
                            number=page_numbers.get(pdf_object_identity(declared_page))
                            choices={number} if number in choices else set()
                        if not choices:
                            row["unavailable"].append({"reason":"Structure content has no verified page connection"})
                            continue
                        possible=set();unknown=False
                        possible_types=set();type_unavailable=False
                        possible_subtypes=set();subtype_unavailable=False
                        for number in choices:
                            tick()
                            tick(items=len(page_owners.get(number,set())))
                            possible.update(page_owners.get(number,set()))
                            unknown=unknown or number in unknown_owner or not page_owners.get(number)
                            tick(items=len(page_types.get(number,set())))
                            possible_types.update(page_types.get(number,set()))
                            type_unavailable=type_unavailable or number in unknown_type or not page_types.get(number)
                            tick(items=len(page_subtypes.get(number,set())))
                            possible_subtypes.update(page_subtypes.get(number,set()))
                            subtype_unavailable=subtype_unavailable or number in unknown_subtype or not page_subtypes.get(number)
                        row["references"].append({"pages":sorted(choices),"dpm_guids":sorted(possible),"unknown_ownership":unknown,
                                                  "dpm_content_set_types":sorted(possible_types),"unknown_content_set_type":type_unavailable,
                                                  "dpm_subtypes":sorted(possible_subtypes),"unknown_subtype":subtype_unavailable})
                    finally:
                        references=tuple(row["references"][first_reference:])
                        unavailable=tuple(row["unavailable"][first_unavailable:])
                        if references or unavailable:
                            reference_cache[cache_key]=(references,unavailable)
            try:
                walk(item["elem"],page)
            except (ValueError,TypeError,pikepdf.PdfError) as error:
                if work>MAX_TREE_ITEMS:raise
                row["unavailable"].append({"reason":str(error)})
            if not row["references"]:
                row["unavailable"].append({"reason":"No verified structure content connection"})
        return list(rows.values())
    except (ValueError,TypeError,pikepdf.PdfError) as error:
        return [{"objgen":item["objgen"],"references":[],"unavailable":[{"reason":str(error)}]} for item in elements]

def document_wide_content_pages(pdf,dpart_nodes):
    """Prove global page ownership from the DPart tree, never a tested tag attribute."""
    by_id={node["objgen"]:node for node in dpart_nodes}
    pages=page_objgen_map(pdf)
    candidates=defaultdict(list)
    for node in dpart_nodes:
        if node["child_count"]:
            continue
        bounds=page_range_from_dpart(node,pages)
        if bounds is None:
            return set()  # Unresolved ranges could overlap an apparent global set.
        current=node
        content_type=node["dpm"].get("Mail_ContentSetType")
        global_set=isinstance(content_type,pikepdf.Name) and safe_name(content_type) in {"FrontMatter","ConversionReport","PolicySettings"}
        seen=set()
        while current is not None:
            if current["objgen"] in seen:
                global_set=False
                break
            seen.add(current["objgen"])
            raw_metadata=dict_get(current["node"],"/DPM",None)
            if raw_metadata is not None and not isinstance(raw_metadata,pikepdf.Dictionary):
                global_set=False
            if any(key in current["dpm"] for key in ("Mail_GUID","Mail_MessageID")):
                global_set=False
            if current["parent"] is None:
                if pdf_object_identity(dict_get(current["node"],"/Parent",None))!=pdf_object_identity(dict_get(pdf.Root,"/DPartRoot",None)):
                    global_set=False
                break
            if pdf_object_identity(dict_get(current["node"],"/Parent",None))!=pdf_object_identity(current["parent"]):
                global_set=False
            current=by_id.get(current["parent_objgen"])
            if current is None:
                global_set=False
        for page in range(bounds[0],bounds[1]+1):
            candidates[page].append(global_set)
    return {page for page,owners in candidates.items() if owners==[True]}

def check_content_set_tag_coverage(pdf,leaf_nodes,content_rows,report):
    return _structure_rules.check_content_set_tag_coverage(_structure_rules_dependencies(), pdf, leaf_nodes, content_rows, report)


def check_content_set_art_usage(pdf,leaf_nodes,content_rows,report):
    return _structure_rules.check_content_set_art_usage(_structure_rules_dependencies(), pdf, leaf_nodes, content_rows, report)


def check_additional_rule_coverage(pdf, xroot, xmp_bytes, efiles, afiles, eapdf_conf, pdfa_part, report):
    source_summary=summarize_source_email(afiles) if xroot is not None and len(flatten_email_records(xroot))<=1 else None
    bindings=getattr(report,"_email_source_summaries",{})
    if len(bindings)==1:
        # Reuse the actual scoped outcome, including an unassigned original.
        # The first catalog Source is not an email-ownership decision.
        source_summary=bindings.get(1)
    source_files=[f for f in afiles if f["afrelationship"]=="Source"]
    mail_profile=eapdf_conf in ("s","si","m","mi")
    container_profile=eapdf_conf in ("c","ci")
    if mail_profile:
        ownership=check_content_ownership_evidence(pdf,xroot,report)
        check_rendered_asset_counts(xroot,afiles,report,ownership)

    if mail_profile and xroot is not None:
        emit_if_unseen(report,"ok","MESSAGE_METADATA_SKIPPED","Message metadata checks were applied",{"profile":eapdf_conf})
    elif mail_profile:
        emit_if_unseen(report,"fail","MESSAGE_METADATA_SKIPPED","Message metadata checks could not be applied because XMP metadata is unavailable",{"profile":eapdf_conf})
    else:
        emit_if_unseen(report,"na","MESSAGE_METADATA_SKIPPED","Rule not applicable for this PDF",{"reason":"Not applied because message metadata rules apply only to EA-PDF mail profiles."})

    all_embedded=list(dict.fromkeys([f["objgen"] for f in efiles if f.get("objgen") is not None]))
    embedded_candidates=embedded_file_candidates(pdf,efiles,afiles)
    report._embedded_media_candidates=embedded_candidates
    file_observations=embedded_file_observations(embedded_candidates)
    bad_paths=[]
    missing_subtypes=[]
    bad_subtypes=[]
    bad_subtype_xmp=[]
    comparable_subtype_xmp=0
    subtype_comparisons=[]
    bad_params=[]
    comparable_params=0
    parameter_comparisons=[]
    ambiguous_media_types=[]
    assets=flatten_assets(xroot) if xroot is not None else []
    attachments=flatten_attachments(xroot) if xroot is not None else []
    email_records=flatten_email_records(xroot) if xroot is not None else []
    owner_scopes=native_attachment_owner_scopes(pdf,email_records) if attachments else {}
    attachment_associations=check_attachment_stream_consistency(attachments,embedded_candidates,report,
        email_records,summarize_source_emails(afiles) if attachments else [],ambiguous_media_types,owner_scopes) if mail_profile else {}
    def matching_asset_for_source(f):
        fn=normalize_path_tail(source_file_name(f))
        named=[a for a in assets if fn and fn==normalize_path_tail(a.get("Filename",""))]
        checksummed=[a for a in assets if is_xmp_text(a.get("Checksum"))
                     and re.fullmatch(r"[0-9A-Fa-f]{32}",a["Checksum"])
                     and a["Checksum"].lower()==str(f.get("md5","")).lower()]
        candidates=checksummed or named
        if len(candidates)>1:
            named_candidates=[a for a in candidates if any(a is item for item in named)]
            if len(named_candidates)==1:
                candidates=named_candidates
        if len(candidates)!=1:
            declared_parameters=[a.get("ContentType") for a in (candidates or assets)
                                 if is_xmp_text(a.get("ContentType")) and ";" in a["ContentType"]]
            if len(candidates)>1 or declared_parameters:
                detail={"filename":fn,"candidate_count":len(candidates),"association_scope":"source_asset"}
                if declared_parameters:
                    detail.update(parameter_comparison_unavailable=True,xmp_content_types=declared_parameters)
                ambiguous_media_types.append(detail)
        return candidates[0] if len(candidates)==1 else None
    for f in embedded_candidates:
        names=[norm_text(v) for v in (f.get("ufilename",""), f.get("filename",""), f.get("tree_name","")) if norm_text(v)]
        bad_name=None
        for name in names:
            if any(tok in name for tok in ("/","\\","..")):
                bad_name=name
                break
            if re.match(r"^[A-Za-z]:", name) or name.startswith("~"):
                bad_name=name
                break
        if bad_name:
            bad_paths.append(bad_name)
        subtype=norm_text(f.get("subtype",""))
        if not report.delegates("EMBEDDED_SUBTYPE_PRESENT") and f.get("stream") is not None and not subtype:
            missing_subtypes.append(f.get("tree_name") or f.get("ufilename") or f.get("filename") or str(f.get("objgen")))
        if not report.delegates("EMBEDDED_SUBTYPE_NO_PARAMETERS") and ";" in subtype:
            bad_subtypes.append(subtype)
        xmp_content_type=""
        if f.get("afrelationship")=="Source":
            asset=matching_asset_for_source(f)
            content_type=str(asset.get("ContentType","")) if asset else ""
        else:
            attachment=attachment_associations.get(id(f))
            content_type=str(attachment.get("Content-Type","")) if attachment else ""
        xmp_content_type=content_type
        reconstructed=f.get("reconstructed_media_type",subtype)
        if ";" in content_type:
            comparable_params += 1
            expected=content_type.split(";",1)[1]
            actual=f.get("media_type_parameters","")
            detail={"filename":f.get("ufilename") or f.get("filename") or f.get("tree_name",""),"xmp":expected,"actual":actual}
            parameter_comparisons.append(detail)
            if media_type_signature("application/octet-stream;"+expected)!=media_type_signature("application/octet-stream;"+actual):
                bad_params.append(detail)
        if xmp_content_type:
            comparable_subtype_xmp += 1
            subtype_comparisons.append({"filename":f.get("ufilename") or f.get("filename") or f.get("tree_name",""),"subtype":subtype,"reconstructed":reconstructed,"xmp_content_type":xmp_content_type})
            if not reconstructed or media_type_signature(reconstructed)!=media_type_signature(xmp_content_type):
                bad_subtype_xmp.append({
                    "filename":f.get("ufilename") or f.get("filename") or f.get("tree_name",""),
                    "subtype":subtype,
                    "xmp_content_type":xmp_content_type,
                })
    emit_if_unseen(report,"ok" if not bad_paths else "fail","EMBEDDED_FILESPEC_NO_PATH_COMPONENTS",
                   "Embedded file specification strings omit path components" if not bad_paths else "Embedded file specification strings must not include path components",
                   {"count":len(embedded_candidates),"files":file_observations} if not bad_paths else {"actual":bad_paths})
    if not report.delegates("EMBEDDED_SUBTYPE_PRESENT"):
        emit_if_unseen(report,"ok" if not missing_subtypes else "fail","EMBEDDED_SUBTYPE_PRESENT",
                       "All embedded file streams include /Subtype" if not missing_subtypes else "Embedded file streams must include /Subtype",
                       {"count":len(embedded_candidates),"files":file_observations} if not missing_subtypes else {"actual":missing_subtypes})
    if not report.delegates("EMBEDDED_SUBTYPE_NO_PARAMETERS"):
        emit_if_unseen(report,"ok" if not bad_subtypes else "fail","EMBEDDED_SUBTYPE_NO_PARAMETERS",
                       "Embedded file /Subtype values omit media-type parameters" if not bad_subtypes else "Embedded file /Subtype values must not include semicolons or media-type parameters",
                       {"count":len(embedded_candidates),"files":file_observations} if not bad_subtypes else {"actual":bad_subtypes})
    if bad_subtype_xmp:
        emit_if_unseen(report,"fail","EMBEDDED_MEDIA_TYPE_MATCH_XMP","Reconstructed embedded media type must match equivalent document-level XMP ContentType",{"actual":bad_subtype_xmp})
    elif ambiguous_media_types:
        emit_if_unseen(report,"warn","EMBEDDED_MEDIA_TYPE_MATCH_XMP","Media-type consistency cannot be established for ambiguous XMP asset associations",{"assessment":"unavailable","actual":ambiguous_media_types,"verified_comparisons":subtype_comparisons})
    elif comparable_subtype_xmp:
        emit_if_unseen(report,"ok","EMBEDDED_MEDIA_TYPE_MATCH_XMP","Reconstructed embedded media types match equivalent document-level XMP ContentType values",{"count":comparable_subtype_xmp,"comparisons":subtype_comparisons})
    else:
        emit_if_unseen(report,"na","EMBEDDED_MEDIA_TYPE_MATCH_XMP","Rule not applicable for this PDF",{"reason":"Not applied because the validator found no embedded files with comparable document-level XMP ContentType metadata."})
    # Source fidelity is separate from agreement between the two stored copies.
    # In particular, matching omissions in XMP and the PDF must not hide original
    # parameters when the independently bound original payload is readable.
    sources=summarize_source_emails(afiles) if mail_profile else []
    for file in embedded_candidates:
        check_native_media_parameters(file,None,report)
        if file.get("afrelationship")=="Source":
            asset=matching_asset_for_source(file)
            if asset and is_xmp_text(asset.get("ContentType")):
                check_native_media_parameters(file,asset["ContentType"],report,"source-asset XMP")
        attachment=attachment_associations.get(id(file))
        if not attachment:
            if (mail_profile and file.get("afrelationship") not in {"Source","Alternative"}
                    and attachment_candidates_for_embedded(file,attachments)):
                for code in ("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED","SOURCE_MEDIATYPE_PARAMETERS_MATCH"):
                    if eapdf_conf.endswith("i") and not source_files:
                        report.na(code,"Original attachment parameter comparison was not performed: isolated output omits original sources",{"file":source_file_name(file)})
                    else:
                        evidence_unavailable(report,code,"Native attachment association does not establish which original supplies its parameters",{"file":source_file_name(file)})
            continue
        if is_xmp_text(attachment.get("Content-Type")):
            check_native_media_parameters(file,attachment["Content-Type"],report,"attachment XMP")
        detail={"assertion":"EMBEDDED-PARAMS","check":"original_parameter_fidelity","file":source_file_name(file)}
        if eapdf_conf.endswith("i") and not source_files:
            for code in ("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED","SOURCE_MEDIATYPE_PARAMETERS_MATCH"):
                report.na(code,"Original attachment parameter comparison was not performed: isolated output omits original sources",detail)
            continue
        owners=attachment_email_owners(attachment,email_records) if any(identity_fields(attachment).values()) else []
        source=source_email_for_record(owners[0],email_records,sources,bindings) if len(owners)==1 else None
        original_types=original_attachment_media_types(file,source)
        if len(original_types)==1:
            original_type=next(iter(original_types))
            if original_type:
                check_native_media_parameters(file,original_type,report,"original attachment bytes")
            else:
                for code in ("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED","SOURCE_MEDIATYPE_PARAMETERS_MATCH"):
                    report.na(code,"The byte-matched original attachment supplies no MIME type or parameters",detail)
        else:
            for code in ("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED","SOURCE_MEDIATYPE_PARAMETERS_MATCH"):
                evidence_unavailable(report,code,"Original attachment parameters cannot be independently associated with these native bytes",detail)

    if container_profile:
        invalid=[]
        folders=collect_collection_folders(dict_get(pdf.Root,"/Collection",None))
        for folder in folders:
            reason=(invalid_collection_filename_reason(folder["name"]) if folder["name_is_text"]
                    else "must be a PDF text string")
            if reason:
                invalid.append({"kind":"folder","name":folder["name"],"folder_id":folder["id"],"reason":reason})
        file_names=[]
        root_folder_id=next((folder["id"] for folder in folders if folder["is_root"]),0)
        # Names belong to name-tree entries. Multiple keys may reference the
        # same file specification and still participate in naming collisions.
        for f in efiles:
            folder_id,file_name=parse_collection_name_tree_key(f.get("tree_name",""),root_folder_id)
            file_names.append({"folder_id":folder_id,"name":file_name})
            reason=(invalid_collection_filename_reason(file_name) if f.get("tree_name_is_text",True)
                    else "must be a PDF text string")
            if reason:
                invalid.append({"kind":"file","name":file_name,"folder_id":folder_id,"reason":reason})
        casefold_dupes=[]
        seen_folders={}
        for folder in folders:
            folded=folder["name"].casefold()
            key=(folder["parent_id"], folded)
            if key in seen_folders:
                casefold_dupes.append({"kind":"folder","parent_id":folder["parent_id"],"names":[seen_folders[key], folder["name"]]})
            else:
                seen_folders[key]=folder["name"]
        seen_files={}
        for item in file_names:
            folded=item["name"].casefold()
            key=(item["folder_id"], folded)
            if key in seen_files:
                casefold_dupes.append({"kind":"file","folder_id":item["folder_id"],"names":[seen_files[key], item["name"]]})
            else:
                seen_files[key]=item["name"]
        emit_if_unseen(report,"ok" if not invalid else "fail","COLLECTION_FILENAMES_VALID",
                       "Collection folder and file names satisfy filename restrictions" if not invalid else "Collection folder and file names violate filename restrictions",
                       {"folders":len(folders),"files":len(file_names),"folder_names":folders,"file_names":file_names} if not invalid else {"actual":invalid})
        emit_if_unseen(report,"ok" if not casefold_dupes else "fail","COLLECTION_FILENAMES_UNIQUE_CASEFOLD",
                       "Collection folder and file names are unique after case normalization" if not casefold_dupes else "Collection folder and file names must be unique after case normalization",
                       {"folders":len(folders),"files":len(file_names),"folder_names":folders,"file_names":file_names} if not casefold_dupes else {"actual":casefold_dupes})
    else:
        emit_if_unseen(report,"na","COLLECTION_FILENAMES_VALID","Rule not applicable for this PDF",{"reason":"Not applied because collection filename rules apply only to container profiles."})
        emit_if_unseen(report,"na","COLLECTION_FILENAMES_UNIQUE_CASEFOLD","Rule not applicable for this PDF",{"reason":"Not applied because collection filename uniqueness rules apply only to container profiles."})

    _dpart_root,_dpart_rootnode,dpart_nodes=get_dpart_nodes(pdf)
    leaf_nodes=[item for item in dpart_nodes if item.get("start") is not None or item.get("end") is not None]
    content_types=[safe_name(item["dpm"].get("Mail_ContentSetType",None)) for item in leaf_nodes]
    frontmatter_present="FrontMatter" in content_types
    if container_profile:
        emit_if_unseen(report,"ok" if frontmatter_present else "fail","DPM_FRONTMATTER_REQUIRED_CONTAINER",
                       "Container profile includes FrontMatter content" if frontmatter_present else "Container profile must include FrontMatter content",
                       {"actual":content_types})
    else:
        emit_if_unseen(report,"na","DPM_FRONTMATTER_REQUIRED_CONTAINER","Rule not applicable for this PDF",{"reason":"Not applied because FrontMatter is required only for container profiles."})
    subtype_bad=[]
    for item in leaf_nodes:
        raw_subtype=item["dpm"].get("Mail_Subtype",None)
        subtype=safe_name(raw_subtype)
        if (subtype or isinstance(raw_subtype,pikepdf.Name)) and not valid_body_media_type(subtype):
            subtype_bad.append({"objgen":item["objgen"],"subtype":subtype})
    if subtype_bad:
        emit_if_unseen(report,"fail","DPM_SUBTYPE_MEDIA_TYPE_NAME","Leaf DPM Mail_Subtype must be an RFC 2046 media type name",{"actual":subtype_bad})
    elif any(safe_name(item["dpm"].get("Mail_Subtype",None)) for item in leaf_nodes):
        emit_if_unseen(report,"ok","DPM_SUBTYPE_MEDIA_TYPE_NAME","Leaf DPM Mail_Subtype values look like RFC 2046 media types",{"count":len(leaf_nodes),"subtypes":[{"objgen":item["objgen"],"subtype":safe_name(item["dpm"].get("Mail_Subtype",None))} for item in leaf_nodes]})
    else:
        emit_if_unseen(report,"na","DPM_SUBTYPE_MEDIA_TYPE_NAME","Rule not applicable for this PDF",{"reason":"Not applied because no DPart leaf node in this PDF declares Mail_Subtype."})
    if mail_profile:
        check_body_media_types(pdf,xroot,afiles,report,eapdf_conf)

    if report.delegates("PDFA3_EAPDF_XMP_EXTENSION_SCHEMA"):
        pass
    elif pdfa_part==3 and xroot is not None:
        emit_if_unseen(report,"ok" if has_pdfa3_eapdf_extension_schema(xmp_bytes) else "fail","PDFA3_EAPDF_XMP_EXTENSION_SCHEMA",
                       "PDF/A-3 EA-PDF XMP extension schema present" if has_pdfa3_eapdf_extension_schema(xmp_bytes) else "PDF/A-3 files must include the EA-PDF XMP extension schema",
                       {"schemas":[ET.tostring(node,encoding="unicode") for node in xroot.iter() if local(node.tag)=="schemas"]})
    else:
        emit_if_unseen(report,"na","PDFA3_EAPDF_XMP_EXTENSION_SCHEMA","Rule not applicable for this PDF",{"reason":"Not applied because the file does not declare PDF/A-3 conformance."})
    if (str(pdf.pdf_version)=="2.0" or safe_name(dict_get(pdf.Root,"/Version",None))=="2.0") and dict_get(pdf.Root,"/StructTreeRoot",None) is not None:
        assessment=ts32005_declaration_assessment(xmp_bytes)
        declared=bool(assessment["matched_declarations"]) and not assessment["errors"]
        emit_if_unseen(report,"ok" if declared else "fail","LOGICAL_TS32005_DECLARATION",
                       "ISO TS 32005 declaration uses the canonical schema and identifier" if declared else "PDF 2.0 files with logical structure require a valid document-level ISO TS 32005 declaration",assessment)
    else:
        emit_if_unseen(report,"na","LOGICAL_TS32005_DECLARATION","Rule not applicable for this PDF",{"reason":"Not applied because this rule applies only to PDF 2.0 files with logical structure."})

    if mail_profile and source_summary is not None:
        for prop in ("To","From","Cc","Bcc"):
            presence_code="MESSAGE_"+prop.upper()+"_XMP"
            foaf_code="MESSAGE_"+prop.upper()+"_FOAF"
            if presence_code not in report.seen_codes or foaf_code not in report.seen_codes:
                compare_agent_property(xroot,report,prop,None,presence_code,foaf_code)
        if "MESSAGE_SENT_XMP" not in report.seen_codes:
            sent=first_message_xmp_value(xroot,"Sent")
            if sent:
                emit_if_unseen(report,"ok","MESSAGE_SENT_XMP","XMP Sent present",{"value":sent})
            else:
                emit_if_unseen(report,"warn","MESSAGE_SENT_XMP","XMP Sent not found for inspected source email",{"source_date":source_summary.get("sent_raw","")})
        if "MESSAGE_RAW_SENT_REQUIRED_IF_INVALID" not in report.seen_codes and "MESSAGE_RAW_SENT_OMIT_IF_VALID" not in report.seen_codes:
            raw_sent=first_message_xmp_value(xroot,"Raw-Sent")
            raw_sent_present=message_xmp_property_present(xroot,"Raw-Sent")
            sent_raw=source_summary.get("sent_raw","")
            sent_norm=source_summary.get("sent_norm","")
            sent_present=source_date_present(source_summary)
            if sent_present and source_summary.get("sent_normalization_unavailable"):
                emit_if_unseen(report,"warn","MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","Raw-Sent applicability is unavailable within the source date parser's range",{"assessment":"unavailable"})
                emit_if_unseen(report,"warn","VALIDATION_INCOMPLETE","Required source date normalization assessment could not be completed",{"assessment":"unavailable"})
            elif sent_present and not sent_norm:
                if raw_sent_present:
                    emit_if_unseen(report,"ok","MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","XMP Raw-Sent present for non-normalizable source date",{"value":raw_sent})
                else:
                    emit_if_unseen(report,"fail","MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","XMP Raw-Sent required when source Date header is not a valid XMP dateTime",{"source_date":sent_raw})
            elif sent_present:
                if raw_sent_present:
                    emit_if_unseen(report,"warn","MESSAGE_RAW_SENT_OMIT_IF_VALID","XMP Raw-Sent SHOULD be omitted when the source date is valid",{"value":raw_sent,"source_date":sent_raw})
                else:
                    emit_if_unseen(report,"ok","MESSAGE_RAW_SENT_OMIT_IF_VALID","XMP Raw-Sent is omitted because source Date header is already a valid XMP dateTime",{"source_date":sent_raw})
        if "MESSAGE_SUBJECT_XMP" not in report.seen_codes:
            subject=first_message_xmp_value(xroot,"Subject")
            if subject:
                emit_if_unseen(report,"ok","MESSAGE_SUBJECT_XMP","XMP Subject present",{"value":subject})
            else:
                emit_if_unseen(report,"na","MESSAGE_SUBJECT_XMP","No optional XMP Subject value is supplied; source-required presence is assessed separately",{"value":subject})
        if "MESSAGE_INREPLYTO_XMP" not in report.seen_codes:
            vals=find_xmp_values(xroot,NS_PDFMAILMETA,"In-Reply-To")
            if vals:
                emit_if_unseen(report,"ok","MESSAGE_INREPLYTO_XMP","XMP In-Reply-To present",{"count":len(vals),"values":vals})
            else:
                emit_if_unseen(report,"na","MESSAGE_INREPLYTO_XMP","No optional XMP In-Reply-To value is supplied; source-required presence is assessed separately",{"values":vals})
        if "MESSAGE_SIZE_XMP" not in report.seen_codes:
            size_in_bytes=first_message_xmp_value(xroot,"SizeInBytes")
            if size_in_bytes and is_nonneg_int_str(size_in_bytes):
                emit_if_unseen(report,"ok","MESSAGE_SIZE_XMP","XMP SizeInBytes is present and comparable",{"value":size_in_bytes})
            else:
                emit_if_unseen(report,"ok","MESSAGE_SIZE_XMP","XMP SizeInBytes omitted for an inspected source email where the validator has no stricter requirement",{"value":size_in_bytes})
        if "MESSAGE_ATTACHMENT_COUNT_XMP" not in report.seen_codes:
            number_attachments=first_message_xmp_value(xroot,"NumberAttachments","Mail_NumberAttachments")
            if number_attachments and is_nonneg_int_str(number_attachments):
                emit_if_unseen(report,"ok","MESSAGE_ATTACHMENT_COUNT_XMP","XMP NumberAttachments is present",{"value":number_attachments})
            else:
                emit_if_unseen(report,"ok","MESSAGE_ATTACHMENT_COUNT_XMP","XMP NumberAttachments absent because the inspected source email has no MIME attachments",{"value":number_attachments})

    nonblank_pages=[idx for idx,page in enumerate(pdf.pages, start=1) if page_has_visibleish_content(page)]
    text_pages=[idx for idx,page in enumerate(pdf.pages, start=1) if page_has_text_objects(page)]
    if nonblank_pages:
        if set(nonblank_pages).issubset(set(text_pages)):
            emit_if_unseen(report,"ok","TEXT_CONTENT_PDF_TEXT_OBJECTS","Nonblank pages contain PDF text objects",{"pages":len(nonblank_pages),"nonblank_pages":nonblank_pages,"text_object_pages":text_pages})
        else:
            emit_if_unseen(report,"warn","TEXT_CONTENT_PDF_TEXT_OBJECTS","Some nonblank pages do not appear to use PDF text objects",{"nonblank_pages":nonblank_pages,"text_object_pages":text_pages})
    else:
        emit_if_unseen(report,"na","TEXT_CONTENT_PDF_TEXT_OBJECTS","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain any pages with visible content."})

    core_field_sequences=structure_field_text_sequences(pdf)
    page_analysis_error=None
    try:
        page_analysis=collect_page_text_analysis(report.path,field_sequences=core_field_sequences)
        if len(page_analysis)!=len(pdf.pages) or [page["page"] for page in page_analysis]!=list(range(1,len(pdf.pages)+1)):
            raise ValueError("Page-text analysis does not cover every PDF page exactly once in order")
    except Exception as error:
        page_analysis=None
        page_analysis_error=f"{type(error).__name__}: {error}"
    for idx,page in enumerate(pdf.pages, start=1):
        for seq in page_marked_content_sequences(page, {"Mail_FieldName","Mail_FieldValue"}):
            core_field_sequences.append({"page":idx, **seq})
    if core_field_sequences:
        missing_text=[
            {"page":seq["page"],"tag":seq["tag"]}
            for seq in core_field_sequences
            if not seq["has_text_object"]
        ]
        if missing_text:
            emit_if_unseen(report,"fail","CORE_FIELDS_TEXT_OBJECTS","Rendered Core Field names/values must use PDF text objects",{"actual":missing_text})
        else:
            emit_if_unseen(report,"ok","CORE_FIELDS_TEXT_OBJECTS","Rendered Core Field names/values use PDF text objects",{"count":len(core_field_sequences),"pages":sorted({seq["page"] for seq in core_field_sequences})})
    elif mail_profile:
        verified,unresolved=core_field_text_evidence(pdf,xroot,page_analysis)
        if verified and not unresolved:
            emit_if_unseen(report,"ok","CORE_FIELDS_TEXT_OBJECTS","Recognized rendered Core Fields have XMP-consistent values in extracted PDF text",{"actual":verified})
        else:
            emit_if_unseen(report,"warn","CORE_FIELDS_TEXT_OBJECTS","Core Field text encoding could not be fully verified from field tags or recognized header text",{"actual":unresolved,"reason":"Optional field tags are not required; unrecognized or localized layouts may still need manual review."})
            report.warn("VALIDATION_INCOMPLETE","Core Field text-object coverage requires manual review when field evidence is unavailable",{"verified":verified,"unresolved":unresolved})
    else:
        emit_if_unseen(report,"na","CORE_FIELDS_TEXT_OBJECTS","Rule not applicable for this PDF",{"reason":"Not applied because the validator could not identify any rendered Core Field name/value marked-content sequences in the page content."})

    if mail_profile and xroot is not None:
        attachment_records=flatten_attachments(xroot)
        if not attachment_records:
            for code in ("ATTACHMENT_ANNOTATIONS_PRESENT","ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX"):
                emit_if_unseen(report,"na",code,"No XMP attachment record activates this annotation check")
        else:
            _dpart_root,_dpart_rootnode,dpart_nodes=get_dpart_nodes(pdf)
            body_sets=collect_body_content_sets(pdf,dpart_nodes)
            file_attachment_annots=collect_file_attachment_annotations(pdf)
            unique_body_identities={
                tuple(sorted((k,v) for k,v in identity_fields(b["identity"]).items() if v))
                for b in body_sets
                if any(identity_fields(b["identity"]).values())
            }
            unknown_body_sets=[b for b in body_sets if not any(identity_fields(b["identity"]).values())]
            failures=[]
            geometry_failures=[]
            geometry_checked=0
            omissions=[]
            unavailable=[]
            known_nonbody=not body_sets and declared_content_sets_are_nonbody(pdf,dpart_nodes)
            source_summaries=summarize_source_emails(afiles)
            email_records=flatten_email_records(xroot)
            messageid_owner_counts=defaultdict(int)
            for record in email_records:
                if not isinstance(record,dict):
                    continue
                record_mid=first_present(record,"Message-ID","MessageID","messageid","Mail_MessageID")
                if is_xmp_text(record_mid):
                    record_mid=norm_text(record_mid)
                    if record_mid:
                        messageid_owner_counts[record_mid]+=1
            reference_cache={}
            for attachment_index,att in enumerate(attachment_records):
                att_ident=identity_fields(att)
                owners=attachment_email_owners(att,email_records)
                parent_mid=first_present(owners[0],"Message-ID","MessageID","messageid","Mail_MessageID") if len(owners)==1 else None
                parent_mid_unique=is_xmp_text(parent_mid) and messageid_owner_counts.get(norm_text(parent_mid),0)==1
                body_identity=owners[0] if len(owners)==1 and (not att_ident["Mail_GUID"] or parent_mid_unique) else att
                if not att_ident["Mail_GUID"] and att_ident["Message-ID"] and email_records and len(owners)!=1 and body_sets:
                    unavailable.append({"attachment":first_present(att,"Filename"),
                        "reason":"Message-ID does not identify one unique email XMP owner", "candidate_owners":len(owners)})
                    report.warn("VALIDATION_INCOMPLETE","Attachment owner cannot be uniquely resolved before body annotation matching",{
                        "attachment":first_present(att,"Filename"),"assessment":"unavailable"})
                    continue
                if unknown_body_sets:
                    unavailable.append({"attachment":first_present(att,"Filename") or "<unnamed>",
                                        "reason":"Some BodyRendering owner identities are unavailable",
                                        "content_sets":[b["objgen"] for b in unknown_body_sets]})
                    report.warn("VALIDATION_INCOMPLETE","Attachment applicability to unknown body owners could not be assessed",
                                {"rules":["ATTACHMENT_ANNOTATIONS_PRESENT"],"assessment":"unavailable"})
                relevant_sets=[b for b in body_sets if identities_match(body_identity,b["identity"])]
                if not relevant_sets and not unknown_body_sets and len(unique_body_identities)<=1 and not any(att_ident.values()):
                    relevant_sets=list(body_sets)
                if not relevant_sets:
                    if known_nonbody:
                        continue
                    if not body_sets:
                        unavailable.append({"attachment":first_present(att,"Filename") or "<unnamed>",
                                            "reason":"DPM does not establish whether a BodyRendering Content Set applies"})
                        continue
                    if unknown_body_sets:
                        continue
                    if att_ident["Mail_GUID"] and not owners:
                        unavailable.append({"attachment":first_present(att,"Filename") or "<unnamed>",
                                            "reason":"Attachment GUID has no established XMP email owner; body applicability is unavailable"})
                        report.warn("VALIDATION_INCOMPLETE","Unknown attachment GUID owner prevents body annotation assessment",
                                    {"rules":["ATTACHMENT_ANNOTATIONS_PRESENT"],"assessment":"unavailable"})
                        continue
                    failures.append({
                        "attachment":first_present(att,"Filename") or "<unnamed>",
                        "reason":"no matching BodyRendering content set was identified from DPM",
                        "identity":att_ident,
                    })
                    continue
                for body in relevant_sets:
                    body_annotations=[annot for annot in file_attachment_annots if annot["page"] in body["pages"]]
                    references=[]
                    for annot in body_annotations:
                        if (annot.get("file") or {}).get("afrelationship")!="Source":
                            continue
                        key=(attachment_index,annot.get("fs_objgen"),tuple(sorted(identity_fields(body["identity"]).items())))
                        if key not in reference_cache:
                            reference_cache[key]=source_attachment_reference(att,annot,body,afiles,email_records,source_summaries,
                                                                            getattr(report,"_email_source_summaries",None))
                        references.append((annot,reference_cache[key]))
                    association_checksum=attachment_source_checksum(att,body,email_records,source_summaries,
                                                                    getattr(report,"_email_source_summaries",None))
                    matching=[annot for annot in body_annotations if attachment_record_matches_annotation(att, annot,association_checksum)]
                    geometry_checked+=bool(matching)
                    good=[annot for annot in matching if annot.get("within_page_box") and annot.get("visible_ok")]
                    if good:
                        if all((annot.get("file") or {}).get("size")==0
                               and (annot.get("file") or {}).get("bytes")==b"" for annot in good):
                            omissions.append({"attachment":first_present(att,"Filename"),
                                              "reason":"attachment is represented by a zero-byte placeholder"})
                        continue
                    if not matching and "CheckSum" not in att and not association_checksum:
                        # Compatible size/media observations cannot prove which
                        # original attachment an arbitrary PDF filename denotes.
                        plausible=[annot for annot in body_annotations if annot.get("within_page_box")
                                   and annot.get("visible_ok") and attachment_record_matches_annotation(
                                       dict(att,Filename=""),annot)]
                        if plausible:
                            unavailable.append({"attachment":first_present(att,"Filename"),
                                "reason":"A differently named visible native payload has compatible observations, but independent filename/payload association is unavailable",
                                "candidate_annotations":[annot.get("objgen") for annot in plausible]})
                            report.warn("VALIDATION_INCOMPLETE","Attachment filename/payload association could not be verified",{
                                "attachment":first_present(att,"Filename"),"assessment":"unavailable"})
                            continue
                    detail={
                        "attachment":first_present(att,"Filename") or "<unnamed>",
                        "content_set_pages":[body["start_page"],body["end_page"]],
                        "identity":att_ident,
                    }
                    if matching:
                        detail["candidate_annotations"]=[
                            {
                                "page":annot["page"],
                                "filename":filename_key(annot.get("filename","")) or filename_key((annot.get("file") or {}).get("ufilename","")) or filename_key((annot.get("file") or {}).get("filename","")),
                                "within_cropbox":annot.get("within_page_box"),
                                "visible":annot.get("visible_ok"),
                                "rect":annot.get("rect"),
                                "cropbox":annot.get("page_box"),
                            }
                            for annot in matching
                        ]
                        detail["reason"]="matching File Attachment annotation exists, but not fully within CropBox or not visible"
                    else:
                        detail["reason"]="no matching File Attachment annotation found in this body Content Set"
                    if references:
                        detail["source_references"]=[reference for annot,reference in references]
                    (geometry_failures if matching else failures).append(detail)
            if failures:
                emit_if_unseen(report,"fail","ATTACHMENT_ANNOTATIONS_PRESENT","Each XMP-declared email attachment must have a qualifying File Attachment annotation in each relevant body Content Set",{"actual":failures})
            elif unavailable:
                emit_if_unseen(report,"warn","ATTACHMENT_ANNOTATIONS_PRESENT","Attachment annotation applicability or payload association is unavailable",{"assessment":"unavailable","actual":unavailable})
            elif known_nonbody:
                emit_if_unseen(report,"na","ATTACHMENT_ANNOTATIONS_PRESENT","No BodyRendering Content Set activates this annotation requirement",{"reason":"All declared Content Sets have known non-body types and valid page ranges."})
            else:
                emit_if_unseen(report,"ok","ATTACHMENT_ANNOTATIONS_PRESENT","Qualifying File Attachment annotations occur in each relevant body Content Set",{"attachments":len(attachment_records),"body_content_sets":len(body_sets),"annotations":len(file_attachment_annots),"placeholders":omissions})
            geometry_code="ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX"
            if geometry_failures:
                report.fail(geometry_code,"A qualifying attachment annotation must be visible and entirely inside CropBox for each attachment/body pair",{"actual":geometry_failures})
            elif unavailable:
                evidence_unavailable(report,geometry_code,"Qualifying annotation geometry cannot be established while attachment association is unavailable",{"actual":unavailable})
            elif geometry_checked:
                report.ok(geometry_code,"Each assessed attachment/body pair has a qualifying visible annotation inside CropBox",{"pairs":geometry_checked})
            else:
                report.na(geometry_code,"No qualifying attachment annotation activates this geometry check; required presence is assessed separately")
    else:
        for code in ("ATTACHMENT_ANNOTATIONS_PRESENT","ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX"):
            if not mail_profile:
                report.na(code,"Attachment/body annotation requirements apply to the owning email PDFs")
            else:
                evidence_unavailable(report,code,"Unreadable email metadata prevents attachment/body applicability assessment",{"email_metadata_readable":False})

    if page_analysis is None:
        codes=["PAGE_CONTEXT_PRESENT_RECOMMENDED","PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED",
               "ACTIONABLE_URLS_LINK_ANNOTATIONS"]
        details={"state":"unverified","reason":f"Page-text extraction failed: {page_analysis_error}",
                 "strength":"SHOULD"}
        for code in codes:
            emit_if_unseen(report,"warn",code,"Recommendation assessment unavailable because page-text extraction failed",details)
        report.warn("VALIDATION_INCOMPLETE","Page-dependent recommendation checks could not execute",dict(details,rules=codes))
    if page_analysis is not None:
        total_pages=len(page_analysis)
        candidate_freq=defaultdict(int)
        for page in page_analysis:
            for line in {norm_compare_text(line) for line in page["candidate_lines"] if norm_text(line)}:
                if line:
                    candidate_freq[line]+=1
        missing_context=[]
        identified_pages=[]
        for page in page_analysis:
            reasons=[]
            for line in page["candidate_lines"]:
                if line_has_page_marker(line, page["page"], total_pages):
                    reasons.append({"kind":"page-marker","line":line})
            if not reasons:
                unique_lines=[
                    line for line in page["candidate_lines"]
                    if len(norm_text(line))>=4 and candidate_freq.get(norm_compare_text(line),0)==1
                ]
                if unique_lines:
                    reasons.append({"kind":"unique-context","line":unique_lines[0]})
            if reasons:
                identified_pages.append({"page":page["page"],"context":reasons[0]})
            else:
                missing_context.append(page["page"])
        if not page_analysis:
            emit_if_unseen(report,"na","PAGE_CONTEXT_PRESENT_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain any pages."})
            emit_if_unseen(report,"na","PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain any pages."})
        else:
            if missing_context:
                emit_if_unseen(report,"warn","PAGE_CONTEXT_PRESENT_RECOMMENDED","Some pages do not appear to have unique page-identifying context in extracted page text",{"pages_missing":missing_context,"identified_pages":identified_pages[:25]})
            else:
                emit_if_unseen(report,"ok","PAGE_CONTEXT_PRESENT_RECOMMENDED","Each page appears to have unique page-identifying context in extracted page text",{"pages":total_pages,"identified_pages":identified_pages})
            if identified_pages and not missing_context:
                emit_if_unseen(report,"ok","PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED","The inferred page-identifying context is present as extractable PDF text",{"pages":[item["page"] for item in identified_pages[:50]],"identified_pages":identified_pages})
            else:
                emit_if_unseen(report,"warn","PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED","Page context text-object recommendation could not be assessed on every page",
                               {"assessment":"partial" if identified_pages else "unavailable","strength":"SHOULD",
                                "reason":"No page-identifying context could be inferred on the unassessed pages; graphical context may require manual review.",
                                "pages_unassessed":missing_context,"identified_pages":identified_pages})

        url_pages=[page for page in page_analysis if page["urls"]]
        if not url_pages:
            emit_if_unseen(report,"na","ACTIONABLE_URLS_LINK_ANNOTATIONS","Rule not applicable for this PDF",{"reason":"Not applied because no actionable URL-like text was found in extracted page text."})
        else:
            unmatched=[]
            for page in url_pages:
                link_uris=set()
                for link in page["links"]:
                    link_uris.update(link.get("uri_candidates",[]))
                page_unmatched=[]
                for url in page["urls"]:
                    if url_has_matching_annotation(url,link_uris,page["text"]):
                        continue
                    page_unmatched.append(url)
                if page_unmatched:
                    unmatched.append({
                        "page":page["page"],
                        "urls":page_unmatched,
                        "link_uris":sorted(link_uris),
                    })
            if unmatched:
                emit_if_unseen(report,"warn","ACTIONABLE_URLS_LINK_ANNOTATIONS","Some URL-like text is not backed by matching PDF Link annotations",{"actual":unmatched})
            else:
                emit_if_unseen(report,"ok","ACTIONABLE_URLS_LINK_ANNOTATIONS","URL-like text found in page content is backed by PDF Link annotations",{"pages":[page["page"] for page in url_pages],"links":[{"page":page["page"],"urls":page["urls"],"annotations":page["links"]} for page in url_pages]})

    # Missing source/conversion-policy evidence does not establish that a
    # recommendation's premise is absent. Keep this limitation visible as WARN.
    for code, reason, needed in (
        ("UNSPECIFIED_COLOR_SRGB_ASSUMED","Original color intent and conversion policy have not been compared.",
         "Original email color declarations and the conversion color-management policy."),
        ("REFERENCED_ASSETS_AS_ASSOCIATED_FILES","Which rendered source assets were separately preserved has not been established.",
         "Source-to-rendered-asset inventory, separate-preservation policy and associated-file relationships."),
        ("REFERENCED_ASSETS_ASSOCIATED_TO_APPROPRIATE_OBJECT","Appropriate object-level associations of separately preserved rendered assets have not been established.",
         "Source-to-rendered-object mapping and the separate-preservation inventory."),
        ("LOGICAL_STRUCTURE_EQUIVALENCE_RECOMMENDED","PDF logical semantics have not been compared with the original source semantics.",
         "Original source semantic structure and a comparison with the PDF structure, including omitted structure."),
    ):
        emit_if_unseen(report,"warn",code,"Recommendation assessment unavailable: "+reason,
                       {"assessment":"unavailable","strength":"SHOULD","reason":reason,"needed_evidence":needed})

    check_custom_tag_operands(pdf,report)
    struct_root=dict_get(pdf.Root,"/StructTreeRoot",None)
    if struct_root is None:
        check_user_property_source_values([],xroot,afiles,report)
        check_tagging_recommendations(pdf,dpart_nodes,[],report,xroot,afiles,page_analysis)
        emit_if_unseen(report,"na","CUSTOM_TAG_MARKED_SEQUENCE_ONLY","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain logical structure or custom EA-PDF tags."})
        emit_if_unseen(report,"na","CUSTOM_TAG_OPERAND_MATCH","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain logical structure or custom EA-PDF tags."})
        emit_if_unseen(report,"na","CONTENT_SET_MAIL_CONTENTSET_TAGGED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain logical structure or DPart content sets to compare."})
        emit_if_unseen(report,"na","MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentGroup structure elements."})
        emit_if_unseen(report,"na","MAIL_CONTENTSET_ART_ONLY","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain logical structure."})
        emit_if_unseen(report,"na","MULTI_EMAIL_DOCUMENT_STRUCTURE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain logical structure."})
        emit_if_unseen(report,"na","MAIL_MESSAGE_GUID_ATTRIBUTE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_Message structure elements."})
        emit_if_unseen(report,"na","MAIL_MESSAGE_GUID_MATCH_DPM_XMP","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_Message structure elements."})
        emit_if_unseen(report,"na","MAIL_MESSAGE_ROLEMAP_DOCUMENT","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_Message structure elements."})
        emit_if_unseen(report,"na","MAIL_CONTENTGROUP_ROLEMAP_PART","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentGroup structure elements."})
        emit_if_unseen(report,"na","MAIL_CONTENTSET_NESTED_UNDER_MESSAGE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentSet structure elements."})
        emit_if_unseen(report,"na","MAIL_CONTENTSET_ROLEMAP_ART","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentSet structure elements."})
        emit_if_unseen(report,"na","MAIL_FIELD_ROLEMAP_APPROPRIATE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_Field structure elements."})
        emit_if_unseen(report,"na","MAIL_FIELDNAME_ROLEMAP_APPROPRIATE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_FieldName structure elements."})
        emit_if_unseen(report,"na","MAIL_FIELDVALUE_ROLEMAP_APPROPRIATE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_FieldValue structure elements."})
        emit_if_unseen(report,"na","MAIL_FIELD_PROFILE_USE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_Field structure elements."})
        emit_if_unseen(report,"na","MAIL_FIELDNAME_PROFILE_USE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_FieldName structure elements."})
        emit_if_unseen(report,"na","MAIL_FIELDVALUE_PROFILE_USE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_FieldValue structure elements."})
        emit_if_unseen(report,"na","MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentGroup structure elements."})
        emit_if_unseen(report,"na","MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentGroup structure elements with Mail_ContentSetType attributes."})
        emit_if_unseen(report,"na","MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentSet structure elements."})
        emit_if_unseen(report,"na","MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentSet structure elements with Mail_Subtype attributes."})
        emit_if_unseen(report,"na","MAIL_FIELD_NESTING_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_Field structure elements."})
        emit_if_unseen(report,"na","MAIL_FIELDNAME_NESTING_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_FieldName structure elements."})
        emit_if_unseen(report,"na","MAIL_FIELDVALUE_NESTING_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_FieldValue structure elements."})
        return

    k=dict_get(struct_root,"/K",None)
    root_elem=(k[0] if len(k)==1 else None) if is_pdf_array(k) or isinstance(k,list) else k
    elems=[]
    if root_elem is not None:
        walk_struct_elems(root_elem, elems, None,class_map=dict_get(struct_root,"/ClassMap",None))
    check_user_property_source_values(elems,xroot,afiles,report)
    check_tagging_recommendations(pdf,dpart_nodes,elems,report,xroot,afiles,page_analysis)
    by_objgen={e["objgen"]:e for e in elems if e["objgen"] is not None}
    custom=[e for e in elems if e["type"].startswith("Mail_")]
    by_parent={}
    for e in custom:
        by_parent.setdefault(e["parent_objgen"],[]).append(e)
    rolemap=normalize_role_map(dict_get(struct_root,"/RoleMap",None))
    msg=[e for e in custom if e["type"]=="Mail_Message"]
    cgroup=[e for e in custom if e["type"]=="Mail_ContentGroup"]
    cset=[e for e in custom if e["type"]=="Mail_ContentSet"]
    fields=[e for e in custom if e["type"]=="Mail_Field"]
    fieldnames=[e for e in custom if e["type"]=="Mail_FieldName"]
    fieldvalues=[e for e in custom if e["type"]=="Mail_FieldValue"]

    def has_ancestor_type(elem, wanted):
        cur=by_objgen.get(elem.get("parent_objgen"))
        while cur is not None:
            if cur["type"]==wanted:
                return True
            cur=by_objgen.get(cur.get("parent_objgen"))
        return False

    def userprop_occurrences(elem,name):
        return [(index+1,dict_get(prop,"/V",None))
                for index,prop in enumerate(parse_userproperties(elem["attrs"]))
                if userprop_name(prop)==name]

    def content_candidates(content,key,unknown_key,normalize=None):
        # Fold contexts once, rather than multiplying references by properties.
        candidates=None
        unavailable=bool(content["unavailable"]) or not content["references"]
        for reference in content["references"]:
            values={normalize(value) if normalize else value for value in reference[key]}
            if reference[unknown_key] or not values:
                unavailable=True
                continue
            candidates=values if candidates is None else candidates&values
            unavailable=unavailable or len(values)>1
        return candidates,unavailable

    def userprops_by_name(elem):
        out={}
        for prop in parse_userproperties(elem["attrs"]):
            name=userprop_name(prop)
            if name is not None:
                out[name]=dict_get(prop,"/V",None)
        return out

    bad_groups=[e["objgen"] for e in cgroup if not has_ancestor_type(e,"Mail_Message")]
    emit_if_unseen(report,"ok" if not bad_groups else "fail","MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE",
                   "Mail_ContentGroup elements are nested below Mail_Message" if not bad_groups else "Mail_ContentGroup elements must be nested below Mail_Message",
                   {"count":len(cgroup)} if not bad_groups else {"actual":bad_groups})
    content_owner_rows=getattr(report,"_structure_content_dpm_rows",None)
    if content_owner_rows is None:
        content_owner_rows=structure_content_dpm_assessments(pdf,msg+cgroup+cset,dpart_nodes) if msg or cgroup or cset else []
    content_guids={row["objgen"]:row for row in content_owner_rows}
    global_pages=document_wide_content_pages(pdf,dpart_nodes)
    def proven_global(element):
        row=content_guids.get(element["objgen"],{})
        refs=row.get("references",[])
        return bool(refs) and not row.get("unavailable") and all(
            len(ref["pages"])==1 and ref["pages"][0] in global_pages for ref in refs)
    orphan_sets=[e for e in cset if not has_ancestor_type(e,"Mail_Message")]
    unknown_sets=[e["objgen"] for e in orphan_sets if not content_guids.get(e["objgen"],{}).get("references")
                  or content_guids.get(e["objgen"],{}).get("unavailable")]
    bad_sets=[e["objgen"] for e in orphan_sets if e["objgen"] not in unknown_sets and not proven_global(e)]
    if eapdf_conf in ("c","ci"):
        emit_if_unseen(report,"na","MAIL_CONTENTSET_NESTED_UNDER_MESSAGE",
                       "Collection Content Sets do not require a Mail_Message ancestor",
                       {"reason":"Collection front matter belongs to the document, while Mail_Message is prohibited in c/ci.",
                        "conformance":eapdf_conf,"local_interpretation":"SPEC-T13-CONTAINER-PARENT"})
    else:
        if unknown_sets:
            report.warn("VALIDATION_INCOMPLETE","Document-wide content-set ownership could not be established",
                        {"rules":["MAIL_CONTENTSET_NESTED_UNDER_MESSAGE"],"objects":unknown_sets})
        emit_if_unseen(report,"fail" if bad_sets else "warn" if unknown_sets else "ok","MAIL_CONTENTSET_NESTED_UNDER_MESSAGE",
                       "Document-wide content-set ownership is unavailable" if unknown_sets and not bad_sets else
                       "Content Sets have a message ancestor or proven document-wide ownership" if not bad_sets else
                       "Mail_ContentSet requires a message ancestor unless its content has proven document-wide ownership",
                       {"count":len(cset),"unavailable":unknown_sets} if not bad_sets else {"actual":bad_sets})
    if not any(e["type"] in MAIL_STRUCTURE_TYPES for e in custom):
        emit_if_unseen(report,"na","CONTENT_SET_MAIL_CONTENTSET_TAGGED","Optional EA-PDF custom tags are not used",
                       {"reason":"Standard logical structure does not require the optional Mail_ContentSet vocabulary."})
    else:
        coverage_rows=[content_guids[e["objgen"]] for e in cset]
        if len({e["objgen"] for e in msg+cgroup+cset})!=len(msg+cgroup+cset):
            coverage_rows=[dict(row,unavailable=row["unavailable"]+[{"reason":"Structure identities are not distinct"}])
                           for row in coverage_rows]
        check_content_set_tag_coverage(pdf,leaf_nodes,coverage_rows,report)

    page_tag_usage={"all_tags":[],"point_tags":[],"sequence_tags":[]}
    for page in pdf.pages:
        usage=page_custom_marked_content(page)
        for key in page_tag_usage:
            page_tag_usage[key].extend(usage[key])
    if custom:
        if page_tag_usage["point_tags"]:
            emit_if_unseen(report,"fail","CUSTOM_TAG_MARKED_SEQUENCE_ONLY","Custom EA-PDF tags must not be used as marked-content points",{"actual":page_tag_usage["point_tags"][:25]})
        else:
            emit_if_unseen(report,"ok","CUSTOM_TAG_MARKED_SEQUENCE_ONLY","Custom EA-PDF tags appear only on marked-content sequences",{"count":len(page_tag_usage["sequence_tags"])})
    else:
        emit_if_unseen(report,"na","CUSTOM_TAG_MARKED_SEQUENCE_ONLY","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain custom EA-PDF structure elements."})

    art_elems=[e for e in elems if e["type"]=="Art"]
    article_aliases=[e for e in elems if e["type"] not in ("Art","Mail_ContentSet") and rolemap.get(e["type"])=="Art"]
    if cset or art_elems or article_aliases:
        art_rows=structure_content_dpm_assessments(pdf,art_elems,dpart_nodes) if art_elems else []
        art_rows.extend(content_guids[e["objgen"]] for e in cset)
        article_nodes={e["objgen"]:e for e in art_elems+cset}
        for index,row in enumerate(art_rows):
            node=article_nodes[row["objgen"]]
            namespace=dict_get(node["elem"],"/NS",None)
            if namespace is not None:
                uri=dict_get(namespace,"/NS",None)
                supported=("https://pdfa.org/ns/ea-pdf/mail-1",) if node["type"]=="Mail_ContentSet" else ("http://iso.org/pdf/ssn",)
                try:
                    uri_text=str(uri) if isinstance(uri,pikepdf.String) else ""
                except (UnicodeError,ValueError,pikepdf.PdfError):
                    uri_text=""
                if uri_text not in supported:
                    art_rows[index]=dict(row,role_unavailable=True,unavailable=row["unavailable"]+[{"reason":"Article namespace role remains unavailable"}])
        art_rows.extend({"objgen":e["objgen"],"references":[],"unavailable":[{"reason":"Other Art role mappings require separate resolution"}]} for e in article_aliases)
        check_content_set_art_usage(pdf,leaf_nodes,art_rows,report)
    else:
        emit_if_unseen(report,"na","MAIL_CONTENTSET_ART_ONLY","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Art or Mail_ContentSet structure elements."})

    if eapdf_conf in ("m","mi"):
        email_documents=[e for e in elems if e["parent"] is not None and e["type"] in ("Document","Mail_Message")]
        emit_if_unseen(report,"ok" if len(email_documents) >= 2 else "fail","MULTI_EMAIL_DOCUMENT_STRUCTURE",
                       "Multiple explicit or implicit Document elements represent emails" if len(email_documents) >= 2 else "Multi-message profiles must represent emails by explicit Document or implicit Mail_Message structure",
                       {"count":len(email_documents)})
    else:
        emit_if_unseen(report,"na","MULTI_EMAIL_DOCUMENT_STRUCTURE","Rule not applicable for this PDF",{"reason":"Not applied because this rule applies only to multi-message profiles."})

    guid_missing=[]
    guid_presence_unavailable=[]
    for e in msg:
        present=False
        unreadable=False
        for _,value in userprop_occurrences(e,"Mail_GUID"):
            if value is None:
                continue
            if not isinstance(value,pikepdf.String):
                present=True  # The separate type check rejects wrong object classes.
                break
            try:
                if str(value)!="":
                    present=True
                    break
            except (UnicodeError,ValueError,pikepdf.PdfError):
                unreadable=True
        if not present:
            (guid_presence_unavailable if unreadable else guid_missing).append(e["objgen"])
    if msg:
        if guid_missing:
            emit_if_unseen(report,"fail","MAIL_MESSAGE_GUID_ATTRIBUTE",
                           "Mail_Message elements must carry Mail_GUID attributes",
                           {"actual":guid_missing,"unavailable":guid_presence_unavailable})
        elif guid_presence_unavailable:
            emit_if_unseen(report,"warn","MAIL_MESSAGE_GUID_ATTRIBUTE",
                           "Required Mail_GUID text presence remains unavailable",
                           {"assessment":"unavailable","actual":guid_presence_unavailable})
        else:
            emit_if_unseen(report,"ok","MAIL_MESSAGE_GUID_ATTRIBUTE",
                           "Mail_Message elements carry Mail_GUID attributes",{"count":len(msg)})
    else:
        emit_if_unseen(report,"na","MAIL_MESSAGE_GUID_ATTRIBUTE","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_Message structure elements."})
    if guid_presence_unavailable:
        report.warn("VALIDATION_INCOMPLETE","Required message GUID text could not be decoded",
                    {"rules":["MAIL_MESSAGE_GUID_ATTRIBUTE"],"assessment":"unavailable"})
    dpm_mail_guids=set()
    dpm_guid_inventory_unavailable=False
    for item in dpart_nodes:
        try:
            dpm_mail_guids.add(guid_value(item["dpm"].get("Mail_GUID","")))
        except (UnicodeError,ValueError,pikepdf.PdfError):
            dpm_guid_inventory_unavailable=True
    if dpm_guid_inventory_unavailable:
        if not any(i["code"]=="DPM_MAIL_GUID_MATCH" and i["level"]=="WARN" and
                   (i.get("details") or {}).get("assessment")=="unavailable" for i in report.items):
            report.warn("DPM_MAIL_GUID_MATCH","Some DPM GUID values could not be decoded",
                        {"assessment":"unavailable","reason":"The global DPM GUID inventory is incomplete"})
        report.warn("VALIDATION_INCOMPLETE","Some DPM GUID values remain unavailable",
                    {"rules":["DPM_MAIL_GUID_MATCH"],"assessment":"unavailable"})
    dpm_mail_guids.discard("")
    xmp_mail_guids=xmp_value_set(xroot,"Mail_GUID")
    guid_mismatch=[]
    guid_unavailable=[]
    message_objects={item["objgen"] for item in msg}
    content_guid_rows=[row for row in content_owner_rows if row["objgen"] in message_objects]
    dpm_guid_sample=sorted(dpm_mail_guids)[:25]
    xmp_guid_sample=sorted(xmp_mail_guids)[:25]
    for e in msg:
        occurrences=userprop_occurrences(e,"Mail_GUID")
        if not occurrences:
            guid_unavailable.append({"objgen":e["objgen"],"reason":"No Mail_GUID property is available to compare"})
            continue
        content=content_guids[e["objgen"]]
        candidates,uncertain=content_candidates(content,"dpm_guids","unknown_ownership")
        candidate_sample=sorted(candidates)[:25] if candidates is not None else []
        if uncertain:
            guid_unavailable.append({"objgen":e["objgen"],"reason":"Some connected content identity evidence is unavailable or ambiguous"})
        guid_unavailable.extend(dict(detail,objgen=e["objgen"]) for detail in content["unavailable"])
        for number,value in occurrences:
            if not isinstance(value,pikepdf.String):
                guid_unavailable.append({"objgen":e["objgen"],"property_number":number,
                                         "reason":"GUID property is not a PDF string; see USERPROPS_TYPE"})
                continue
            try:
                mail_guid=str(value)
            except (UnicodeError,ValueError,pikepdf.PdfError):
                guid_unavailable.append({"objgen":e["objgen"],"property_number":number,
                                         "reason":"GUID text cannot be decoded",
                                         "value":observed_pdf_value(value)})
                continue
            problem={}
            if not dpm_guid_inventory_unavailable and dpm_mail_guids and mail_guid not in dpm_mail_guids:
                problem.update(dpm_guids=dpm_guid_sample,dpm_guid_count=len(dpm_mail_guids))
            if xmp_mail_guids and mail_guid not in xmp_mail_guids:
                problem.update(xmp_guids=xmp_guid_sample,xmp_guid_count=len(xmp_mail_guids))
            if candidates is not None and mail_guid not in candidates:
                problem["content_dpm_mismatches"]={"matching_all_known_references":candidate_sample,
                    "candidate_count":len(candidates),"evidence":"See content_owners for actual references"}
            if problem:
                guid_mismatch.append({"objgen":e["objgen"],"property_number":number,"Mail_GUID":mail_guid,**problem})
    if not msg:
        emit_if_unseen(report,"na","MAIL_MESSAGE_GUID_MATCH_DPM_XMP","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_Message structure elements."})
    elif guid_mismatch:
        emit_if_unseen(report,"fail","MAIL_MESSAGE_GUID_MATCH_DPM_XMP",
                       "Mail_Message GUID attributes contradict DPM/XMP or their actual content owners",
                       {"actual":guid_mismatch,"unavailable":guid_unavailable,"content_owners":content_guid_rows})
    elif guid_unavailable:
        emit_if_unseen(report,"warn","MAIL_MESSAGE_GUID_MATCH_DPM_XMP",
                       "Mail_Message GUID consistency with its content owners remains unavailable",
                       {"state":"unverified","assessment":"unavailable","actual":guid_unavailable})
    else:
        emit_if_unseen(report,"ok","MAIL_MESSAGE_GUID_MATCH_DPM_XMP",
                       "Mail_Message GUID attributes match their connected DPart email identities and XMP where present",
                       {"count":len(msg),"content_owners":content_guid_rows})
    if guid_unavailable:
        report.warn("VALIDATION_INCOMPLETE","Message content/GUID ownership remains unavailable",
                    {"rules":["MAIL_MESSAGE_GUID_MATCH_DPM_XMP"],"assessment":"unavailable"})

    for code, elems_list, tag, expected in (
        ("MAIL_MESSAGE_ROLEMAP_DOCUMENT", msg, "Mail_Message", "Document"),
        ("MAIL_CONTENTGROUP_ROLEMAP_PART", cgroup, "Mail_ContentGroup", "Part"),
        ("MAIL_CONTENTSET_ROLEMAP_ART", cset, "Mail_ContentSet", "Art"),
    ):
        actual=rolemap.get(tag,"")
        if elems_list:
            emit_if_unseen(report,"ok" if actual == expected else "fail",code,
                           f"{tag} role map is {expected}" if actual == expected else f"{tag} must role map to {expected}",
                           {"actual":actual,"expected":expected})
        else:
            emit_if_unseen(report,"na",code,"Rule not applicable for this PDF",{"reason":f"Not applied because the PDF does not contain {tag} structure elements."})
    for code, elems_list, tag in (
        ("MAIL_FIELD_ROLEMAP_APPROPRIATE", fields, "Mail_Field"),
        ("MAIL_FIELDNAME_ROLEMAP_APPROPRIATE", fieldnames, "Mail_FieldName"),
        ("MAIL_FIELDVALUE_ROLEMAP_APPROPRIATE", fieldvalues, "Mail_FieldValue"),
    ):
        if elems_list:
            actual,error=StructureRoles(struct_root,False).role(tag)
            if error==StructureRoles.LIMIT:
                emit_if_unseen(report,"warn",code,error,{"assessment":"unavailable"})
                continue
            if actual:
                emit_if_unseen(report,"ok",code,f"{tag} has a PDF 1.7 role map",{"actual":actual})
            else:
                emit_if_unseen(report,"fail",code,f"{tag} must have an appropriate PDF 1.7 role map",{"actual":actual})
        else:
            emit_if_unseen(report,"na",code,"Rule not applicable for this PDF",{"reason":f"Not applied because the PDF does not contain {tag} structure elements."})

    if cgroup:
        missing=[e["objgen"] for e in cgroup if "Mail_ContentSetType" not in userprops_by_name(e)]
        if missing:
            emit_if_unseen(report,"warn","MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED","Mail_ContentGroup elements should carry Mail_ContentSetType attributes",{"actual":missing})
        else:
            emit_if_unseen(report,"ok","MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED","Mail_ContentGroup elements carry Mail_ContentSetType attributes",{"count":len(cgroup)})
    else:
        emit_if_unseen(report,"na","MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentGroup structure elements."})
    cgroup_types=[{"objgen":e["objgen"],"property_number":number,"value":value}
                  for e in cgroup for number,value in userprop_occurrences(e,"Mail_ContentSetType")]
    uncertain_group_attributes=[e for e in cgroup if e["attribute_errors"]
                                and not userprop_occurrences(e,"Mail_ContentSetType")]
    if not cgroup:
        emit_if_unseen(report,"na","MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT","Rule not applicable for this PDF",{"reason":"No Mail_ContentGroup structure elements are present."})
    elif not cgroup_types and not uncertain_group_attributes:
        emit_if_unseen(report,"na","MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT","No optional group type attribute is supplied",{"reason":"Resolved group attributes do not contain the exact Mail_ContentSetType name."})
    else:
        mismatched=[]
        unavailable=[]
        group_elements={e["objgen"]:e for e in cgroup}
        unavailable.extend({"objgen":e["objgen"],"reason":"Group attribute resolution is incomplete",
                            "errors":e["attribute_errors"]} for e in uncertain_group_attributes)
        xmp_type_tags={qn(NS_PDFMAILMETA,name) for name in ("Mail_ContentSetType","ContentSetType")}
        xmp_unbound=xroot is None or any(node.tag in xmp_type_tags for node in xroot.iter())
        group_contexts={}
        for element in cgroup:
            if not userprop_occurrences(element,"Mail_ContentSetType"):
                continue
            content=content_guids[element["objgen"]]
            candidates,uncertain=content_candidates(content,"dpm_content_set_types","unknown_content_set_type")
            group_contexts[element["objgen"]]=candidates
            if uncertain:
                unavailable.append({"objgen":element["objgen"],"reason":"Connected DPart type evidence is unavailable or ambiguous"})
            unavailable.extend(dict(detail,objgen=element["objgen"]) for detail in content["unavailable"])
            if xmp_unbound:
                unavailable.append({"objgen":element["objgen"],"comparison":"XMP",
                                    "reason":"XMP is unreadable or supplied candidate type properties have no reviewed group binding"})
        for item in cgroup_types:
            element=group_elements[item["objgen"]]
            value=item["value"]
            if not isinstance(value,pikepdf.String):
                unavailable.append({"objgen":item["objgen"],"property_number":item["property_number"],
                                    "reason":"Group type is not a PDF string; see USERPROPS_TYPE"})
                continue
            try:
                value=str(value)
            except (UnicodeError,ValueError,pikepdf.PdfError):
                unavailable.append({"objgen":item["objgen"],"property_number":item["property_number"],
                                    "reason":"Group type text cannot be decoded",
                                    "value":observed_pdf_value(value)})
                continue
            candidates=group_contexts[item["objgen"]]
            if candidates is not None and value not in candidates:
                mismatched.append({"objgen":item["objgen"],"property_number":item["property_number"],
                    "value":value,"matching_all_known_references":sorted(candidates)[:25],
                    "candidate_count":len(candidates)})
        details={"count":len(cgroup_types),"actual":mismatched,"unavailable":unavailable,
                 "content_owners":[content_guids[e["objgen"]] for e in cgroup]}
        if mismatched:
            emit_if_unseen(report,"fail","MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT",
                           "Supplied group types contradict their connected DPart Content Sets",details)
        elif unavailable:
            emit_if_unseen(report,"warn","MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT",
                           "Supplied group type correspondence remains unavailable",dict(details,state="unverified",assessment="unavailable"))
        else:
            emit_if_unseen(report,"ok","MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT",
                           "Supplied group types exactly match connected DPart types; no candidate XMP type fields are supplied",details)
        if unavailable:
            report.warn("VALIDATION_INCOMPLETE","Content Group type correspondence remains unavailable",
                        {"rules":["MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT"],"assessment":"unavailable"})
    if cset:
        missing=[]
        unavailable=[]
        for element in cset:
            if userprop_occurrences(element,"Mail_Subtype"):
                continue
            if element["attribute_errors"]:
                unavailable.append({"objgen":element["objgen"],"errors":element["attribute_errors"]})
            else:
                missing.append(element["objgen"])
        details={"count":len(cset),"actual":missing,"unavailable":unavailable}
        if missing or unavailable:
            if unavailable:
                details.update(state="unverified",assessment="unavailable")
            emit_if_unseen(report,"warn","MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED",
                           "Mail_ContentSet elements should carry Mail_Subtype attributes" if missing else
                           "Content Set subtype recommendation could not be fully assessed",details)
        else:
            emit_if_unseen(report,"ok","MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED",
                           "Mail_ContentSet elements carry Mail_Subtype attributes",details)
    else:
        emit_if_unseen(report,"na","MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentSet structure elements."})
    cset_subtypes=[{"objgen":e["objgen"],"property_number":number,"value":value}
                   for e in cset for number,value in userprop_occurrences(e,"Mail_Subtype")]
    uncertain_set_attributes=[e for e in cset if e["attribute_errors"]
                              and not userprop_occurrences(e,"Mail_Subtype")]
    if not cset:
        emit_if_unseen(report,"na","MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT","Rule not applicable for this PDF",{"reason":"Not applied because the PDF does not contain Mail_ContentSet structure elements."})
    elif not cset_subtypes and not uncertain_set_attributes:
        emit_if_unseen(report,"na","MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT","Rule not applicable for this PDF",{"reason":"Resolved Content Set attributes do not contain the exact Mail_Subtype name."})
    else:
        mismatched=[]
        unavailable=[{"objgen":e["objgen"],"reason":"Content Set attribute resolution is incomplete",
                      "errors":e["attribute_errors"]} for e in uncertain_set_attributes]
        set_contexts={}
        subtype_signature=lru_cache(maxsize=4096)(media_type_signature)
        for element in cset:
            if not userprop_occurrences(element,"Mail_Subtype"):
                continue
            content=content_guids[element["objgen"]]
            candidates,uncertain=content_candidates(content,"dpm_subtypes","unknown_subtype",subtype_signature)
            set_contexts[element["objgen"]]=candidates
            if uncertain:
                unavailable.append({"objgen":element["objgen"],"reason":"Connected DPart subtype evidence is unavailable or ambiguous"})
            unavailable.extend(dict(detail,objgen=element["objgen"]) for detail in content["unavailable"])
        for item in cset_subtypes:
            if not isinstance(item["value"],pikepdf.String):
                unavailable.append({"objgen":item["objgen"],"property_number":item["property_number"],
                                    "reason":"Subtype is not a PDF string; see USERPROPS_TYPE"})
                continue
            try:
                value=str(item["value"])
            except (UnicodeError,ValueError,pikepdf.PdfError):
                unavailable.append({"objgen":item["objgen"],"property_number":item["property_number"],
                                    "reason":"Subtype text cannot be decoded",
                                    "value":observed_pdf_value(item["value"])})
                continue
            signature=subtype_signature(value)
            candidates=set_contexts[item["objgen"]]
            if candidates is not None and signature not in candidates:
                mismatched.append({"objgen":item["objgen"],"property_number":item["property_number"],"value":value,
                                   "matching_all_known_media_signatures":sorted(candidates)[:25],"candidate_count":len(candidates)})
        details={"count":len(cset_subtypes),"actual":mismatched,"unavailable":unavailable,
                 "content_owners":[content_guids[e["objgen"]] for e in cset]}
        if mismatched:
            emit_if_unseen(report,"fail","MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT",
                           "Supplied Mail_Subtype properties contradict their connected DPart Content Sets",details)
        elif unavailable:
            emit_if_unseen(report,"warn","MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT",
                           "Some supplied Mail_Subtype values cannot be compared",dict(details,state="unverified",assessment="unavailable"))
        else:
            emit_if_unseen(report,"ok","MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT",
                           "Every supplied Mail_Subtype property matches connected DPart metadata",details)
        if unavailable:
            report.warn("VALIDATION_INCOMPLETE","Some active subtype properties could not be compared",
                        {"rules":["MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT"],"assessment":"unavailable"})

    for code, elems_list, wanted_parent in (
        ("MAIL_FIELD_NESTING_RECOMMENDED", fields, "Mail_ContentSet"),
        ("MAIL_FIELDNAME_NESTING_RECOMMENDED", fieldnames, "Mail_Field"),
        ("MAIL_FIELDVALUE_NESTING_RECOMMENDED", fieldvalues, "Mail_Field"),
    ):
        if not elems_list:
            emit_if_unseen(report,"na",code,"Rule not applicable for this PDF",{"reason":f"Not applied because the PDF does not contain {code.split('_NESTING_')[0].title().replace('_','_')} structure elements."})
            continue
        bad=[e["objgen"] for e in elems_list if not has_ancestor_type(e,wanted_parent)]
        if bad:
            emit_if_unseen(report,"warn",code,f"Elements should be nested below {wanted_parent}",{"actual":bad})
        else:
            emit_if_unseen(report,"ok",code,f"Elements are nested below {wanted_parent}",{"count":len(elems_list)})

def check_fonts(pdf,report):
    return _pdf_rules.check_fonts(_pdf_rule_context(pdf, report), fonts_for=collect_all_fonts)

def check_xmp_dependent_unchecked(report,xmp_bytes=b""):
    return _xmp_rules.check_xmp_dependent_unchecked(_xmp_rules_dependencies(), report, xmp_bytes)

def build_validation_context(pdf, xroot, eapdf_conf, pdfa_part, efiles, afiles, source_bindings=None):
    source_files=[f for f in afiles if f["afrelationship"]=="Source"]
    source_summaries=summarize_source_emails(afiles) if xroot is not None else []
    source_owner_unassigned=bool(source_files) and bool(source_bindings) and any(
        source is None for source in source_bindings.values())
    if source_bindings:
        source_summaries=[source for source in source_bindings.values() if source is not None]
    source_summary=source_summaries[0] if source_summaries else None
    struct_root=dict_get(pdf.Root,"/StructTreeRoot",None)
    outlines_root=dict_get(pdf.Root,"/Outlines",None)
    _dpart_root, _dpart_rootnode, dpart_nodes = get_dpart_nodes(pdf)
    leaf_nodes = [item for item in dpart_nodes if item.get("start") is not None or item.get("end") is not None]
    custom_types=set()
    userprops_present=False
    if struct_root is not None:
        k=dict_get(struct_root,"/K",None)
        root_elem=None
        if is_pdf_array(k) or isinstance(k,list):
            if len(k)==1:
                root_elem=k[0]
        else:
            root_elem=k
        elems=[]
        if root_elem is not None:
            walk_struct_elems(root_elem, elems, None,class_map=dict_get(struct_root,"/ClassMap",None))
            custom=[e for e in elems if e["type"].startswith("Mail_")]
            custom_types={e["type"] for e in custom}
            for elem in custom:
                if parse_userproperties(elem["attrs"]):
                    userprops_present=True
                    break
    assets=flatten_assets(xroot) if xroot is not None else []
    attachments=flatten_attachments(xroot) if xroot is not None else []
    records=flatten_email_records(xroot) if xroot is not None else []
    fonts=collect_all_fonts(pdf)
    has_email_attachments=represented_email_attachment_presence(xroot,afiles,source_bindings)
    embedded_candidates=embedded_file_candidates(pdf,efiles,afiles)
    info_object=dict_get(pdf.trailer,"/Info",None)
    info_keys=({safe_name(key) for key in info_object.keys() if dict_get(info_object,key,None) is not None} if isinstance(info_object,pikepdf.Dictionary)
               else set() if info_object is None else None)
    return {
        "docinfo_keys":info_keys,
        "pdf_version":str(pdf.pdf_version),
        "catalog_version":safe_name(dict_get(pdf.Root,"/Version",None)),
        "has_email_attachments":has_email_attachments,
        "font_count":len(fonts),
        "missing_font_count":sum(not font["embedded"] for font in fonts),
        "associated_file_count":len(afiles)+len(collect_marked_content_af_specs(pdf)),
        "embedded_candidate_count":len(embedded_candidates),
        "native_candidate_count":sum(file.get("afrelationship") not in ("Source","Alternative") for file in embedded_candidates),
        "attachment_checksum_count":sum("CheckSum" in attachment for attachment in attachments),
        "attachment_messageid_count":sum("Message-ID" in attachment for attachment in attachments),
        "embedded_checksum_count":sum(bool(file.get("checksum_parameter_present")) for file in embedded_candidates),
        "asset_numbermessages_count":sum("NumberMessages" in asset for asset in assets),
        "generic_asset_count":sum(norm_text(asset.get("ContentType", "")).lower() in GENERIC_BAD_MIME for asset in assets),
        "generic_source_count":sum(norm_text(source.get("subtype", "")).lower() in GENERIC_BAD_MIME for source in source_files),
        "source_raw_fields":{key for source in source_summaries for key,value in source.items() if value and key in ("raw_to","raw_from","raw_cc","raw_bcc","subject","in_reply_to","sent_raw")} | {key for source in source_summaries for key in source.get("native_present_fields",set())},
        "source_has_valid_date":any(source.get("sent_norm") for source in source_summaries),
        "source_has_invalid_date":any(source_date_present(source) and not source.get("sent_norm")
                                      and not source.get("sent_normalization_unavailable") for source in source_summaries),
        "message_properties":{key for record in records for key,value in record.items() if value},
        "raw_address_properties":{prop for prop in ("Raw-To","Raw-From","Raw-Cc","Raw-Bcc")
                                  if xroot is not None and message_xmp_property_present(xroot,prop)},
        "identity_node_count":sum(item["start"] is None and item["end"] is None and any(key in item["dpm"] for key in ("Mail_GUID","Mail_MessageID")) for item in dpart_nodes),
        "messageid_node_count":sum("Mail_MessageID" in item["dpm"] for item in dpart_nodes),
        "rendering_node_count":sum(safe_name(item["dpm"].get("Mail_ContentSetType")) in ("BodyRendering","AttachmentRendering") for item in leaf_nodes),
        "xmp_readable": xroot is not None,
        "pdfa_part": pdfa_part,
        "eapdf_conf": eapdf_conf or "",
        "mail_profile": eapdf_conf in ("s","si","m","mi"),
        "container_profile": eapdf_conf in ("c","ci"),
        "single_message_profile": eapdf_conf in ("s","si"),
        "multi_message_profile": eapdf_conf in ("m","mi"),
        "isolated_profile": bool(eapdf_conf and eapdf_conf.endswith("i")),
        "source_optional_profile": eapdf_conf in ("si","mi","ci"),
        "source_file_count": len(source_files),
        "source_owner_unassigned":source_owner_unassigned,
        "inspectable_source": source_summary is not None,
        "source_summary": source_summary,
        "asset_count": len(assets),
        "attachment_xmp_count": len(attachments),
        "has_collection": dict_get(pdf.Root,"/Collection",None) is not None,
        "has_outlines": outlines_root is not None,
        "content_set_count": len(leaf_nodes),
        "known_nonbody_content_sets":declared_content_sets_are_nonbody(pdf,dpart_nodes),
        "has_struct_tree": struct_root is not None,
        "custom_types": custom_types,
        "userprops_present": userprops_present,
    }

def profile_label(ctx):
    conf=ctx["eapdf_conf"] or "unknown"
    mapping={
        "s":"single-message",
        "si":"single-message isolated",
        "m":"multi-message",
        "mi":"multi-message isolated",
        "c":"container",
        "ci":"container isolated",
        "unknown":"unknown"
    }
    return f"{conf} ({mapping.get(conf, 'unknown')})"

def structure_snapshot(ctx):
    bits=[
        f"profile={profile_label(ctx)}",
        f"pdfa_part={ctx['pdfa_part'] if ctx['pdfa_part'] is not None else 'unknown'}",
        f"xmp_readable={ctx['xmp_readable']}",
        f"source_af={ctx['source_file_count']}",
        f"inspectable_rfc822_source={ctx['inspectable_source']}",
        f"xmp_assets={ctx['asset_count']}",
        f"xmp_attachments={ctx['attachment_xmp_count']}",
        f"collection={ctx['has_collection']}",
        f"struct_tree={ctx['has_struct_tree']}",
    ]
    if ctx["custom_types"]:
        bits.append("mail_tags=" + ",".join(sorted(ctx["custom_types"])))
    return "; ".join(bits)

def na_reason_for_code(code, ctx):
    snapshot=structure_snapshot(ctx)
    if code in {"DPM_EMAIL_CONTENT_SET_OWNERSHIP","DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE","EMAIL_XMP_CARDINALITY",
                "MESSAGE_CORE_FIELDS_SOURCE_REQUIRED","MESSAGE_METADATA_MATCH_SOURCE","ATTACHMENT_XMP_INVENTORY_RECOMMENDED"} and ctx["eapdf_conf"] and not ctx["mail_profile"]:
        return "This predicate is assessed in the owning email PDF, not a collection wrapper."
    if code in {"EMBEDDED_SIZE_RECOMMENDED","EMBEDDED_MD5_RECOMMENDED","EMBEDDED_MEDIATYPE_PARAMETERS_TYPE",
                "EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED","SOURCE_MEDIATYPE_PARAMETERS_MATCH"} and not ctx["embedded_candidate_count"]:
        return "No embedded stream activates this parameter predicate."
    if code in {"EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED","SOURCE_MEDIATYPE_PARAMETERS_MATCH"}:
        if ctx["container_profile"] or not ctx["native_candidate_count"]:
            return "No native attachment originating in an email is present for this original-parameter comparison."
    if code in {"ATTACHMENT_XMP_SIZE_MATCH","ATTACHMENT_XMP_CHECKSUM_MATCH","ATTACHMENT_XMP_FILENAME_MATCH_SOURCE",
                "ATTACHMENT_XMP_MESSAGE_ID_MATCH","ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED"}:
        if not ctx["attachment_xmp_count"]:
            return "No attachment metadata record activates this predicate."
    if code=="ATTACHMENT_XMP_CHECKSUM_MATCH" and not ctx["attachment_checksum_count"]:
        return "No optional attachment CheckSum is supplied."
    if code=="ATTACHMENT_XMP_MESSAGE_ID_MATCH" and not ctx["attachment_messageid_count"]:
        return "No optional attachment Message-ID is supplied."
    if code=="ASSET_NUMBERMESSAGES_MATCH_RENDERED" and not ctx["asset_numbermessages_count"]:
        return "No optional source-asset NumberMessages is supplied."
    if code=="ATTACHMENT_XMP_INVENTORY_RECOMMENDED" and ctx["isolated_profile"] and not ctx["source_file_count"]:
        return "Original attachment inventory is deliberately omitted in this isolated profile."
    schema_fields={"MESSAGE_SUBJECT_XMP":"Subject","MESSAGE_INREPLYTO_XMP":"In-Reply-To","MESSAGE_SIZE_XMP":"SizeInBytes",
                   "MESSAGE_ATTACHMENT_COUNT_XMP":"NumberAttachments","MESSAGE_TO_XMP":"To","MESSAGE_FROM_XMP":"From",
                   "MESSAGE_CC_XMP":"Cc","MESSAGE_BCC_XMP":"Bcc"}
    if code in schema_fields and schema_fields[code] not in ctx["message_properties"]:
        return "This optional metadata value is not supplied; source-conditioned presence has its own rule."
    structure_rule=(code.startswith(("MAIL_MESSAGE_","MAIL_CONTENT","MAIL_FIELD","USERPROPS_"))
                    or code in {"STRUCTTREE_ROOT_DOCUMENT","ROLEMAPNS_PRESENT","ROLEMAP_MAIL_TAGS",
                                "STRUCTURE_CORRESPONDENCE","LOGICAL_TS32005_DECLARATION",
                                "CUSTOM_TAG_MARKED_SEQUENCE_ONLY","CUSTOM_TAG_OPERAND_MATCH",
                                "HEADER_FIELDS_TAGGED_RECOMMENDED","CONTENT_SET_MAIL_CONTENTSET_TAGGED",
                                "MULTI_EMAIL_DOCUMENT_STRUCTURE"})
    if code=="DPM_BODY_MEDIA_TYPE_MATCH_SOURCE" and ctx.get("known_nonbody_content_sets"):
        return "All declared Content Sets have known non-body types and valid page ranges; no BodyRendering DPart is declared."
    if code=="HEADER_FIELDS_TAGGED_RECOMMENDED" and not ctx["custom_types"]:
        return "No custom EA-PDF tags activate the conditional header-tagging recommendation."
    if structure_rule and not ctx["has_struct_tree"]:
        return "The document has no optional logical structure to which this structure rule applies."
    profile_groups={
        "single_message_profile":{"PROFILE_EMAIL_COUNT_SINGLE","DPM_INTERMEDIATE_COUNT_SINGLE","MAIL_MESSAGE_COUNT_SINGLE"},
        "multi_message_profile":{"PROFILE_EMAIL_COUNT_MULTI","DPM_INTERMEDIATE_COUNT_MULTI","MAIL_MESSAGE_COUNT_MULTI","PROFILE_PAGEMODE_MULTI","PROFILE_NFSM_MULTI"},
        "container_profile":{"DPM_INTERMEDIATE_COUNT_CONTAINER","DPM_CONTAINER_CONTENTSET_TYPES","MAIL_MESSAGE_COUNT_CONTAINER","PROFILE_PAGEMODE_CONTAINER","PROFILE_NFSM_CONTAINER","COLLECTION_CHILD_VALIDATION"},
        "mail_profile":{"MESSAGE_SENT_XMP","MESSAGE_SENT_FORMAT","MESSAGE_ID_XMP","MAIL_GUID_XMP","EMAIL_XMP_CONTAINER","MAIL_GUID_UNIQUE","MESSAGE_SOURCE_COVERAGE","DPM_BODY_MEDIA_TYPE_MATCH_SOURCE"},
    }
    for condition,codes in profile_groups.items():
        if code in codes and ctx["eapdf_conf"] and not ctx[condition]:
            return f"This rule does not apply to profile {profile_label(ctx)}. Context: {snapshot}."
    if code in {"PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS","PROFILE_NFSM_SINGLE_NO_ATTACHMENTS",
                "PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS","PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS"}:
        if ctx["eapdf_conf"] and (not ctx["single_message_profile"] or
                (ctx["has_email_attachments"] is not None and code.endswith("_WITH_ATTACHMENTS") != ctx["has_email_attachments"])):
            return f"This single-message attachment branch does not apply to this profile and attachment count. Context: {snapshot}."
    if code=="COLLECTION_EXTENSIONS" and (ctx["pdf_version"]=="2.0" or ctx["catalog_version"]=="2.0"):
        return "The Adobe collection extension is required only for PDF 1.7, not PDF 2.0."
    if code=="FONTS_NONE" and ctx["font_count"]:
        return "Fonts were found; the no-font diagnostic does not apply."
    if code=="FONTS_MISSING" and ctx["font_count"] and not ctx["missing_font_count"]:
        return "All discovered fonts are embedded; the missing-font diagnostic does not apply."
    if code=="AF_IN_NAME_TREE" and not ctx["associated_file_count"]:
        return "No catalog or marked-content Associated File specifications require name-tree registration."
    if code in {"EMBEDDED_STREAM","EMBEDDED_SIZE_MATCH","EMBEDDED_MD5_MATCH","EMBEDDED_MD5_BINARY_FORMAT"} and not ctx["embedded_candidate_count"]:
        return "No embedded file specifications require stream or payload-parameter inspection."
    if code=="EMBEDDED_MD5_BINARY_FORMAT" and not ctx["embedded_checksum_count"]:
        return "No embedded stream declares an optional CheckSum parameter whose binary form requires inspection."
    if code=="SOURCE_MIME_GENERIC" and ctx["source_file_count"] and not ctx["generic_source_count"]:
        return "No embedded Source asset declares a discouraged generic media type."
    if code=="ASSET_CONTENTTYPE_GENERIC" and ctx["asset_count"] and not ctx["generic_asset_count"]:
        return "No source-asset XMP record declares a discouraged generic media type."
    if code in {"DPM_MAIL_GUID_PRESENT","DPM_MAIL_GUID_MATCH"} and not ctx["identity_node_count"]:
        return "There are no intermediate email-identity DParts; profile cardinality is checked separately."
    if code=="DPM_MAIL_MESSAGEID_MATCH" and not ctx["messageid_node_count"]:
        return "No DPart declares an optional Mail_MessageID to compare."
    if code=="DPM_SUBTYPE_REQUIRED" and not ctx["rendering_node_count"]:
        return "No BodyRendering or AttachmentRendering content set requires a Mail_Subtype."
    if code=="MAIL_MESSAGE_CHILDREN" and "Mail_Message" not in ctx["custom_types"]:
        return "There are no Mail_Message elements whose children require inspection."
    if code=="MAIL_CONTENTSET_CHILDREN" and "Mail_ContentSet" not in ctx["custom_types"]:
        return "There are no Mail_ContentSet elements whose immediate children require inspection."
    source_condition_unknown=ctx.get("source_owner_unassigned",False)
    if code in {"MESSAGE_SOURCE_COVERAGE","XMP_CREATEDATE_SEMANTICS"} and not ctx["inspectable_source"] and not source_condition_unknown:
        return "No inspectable retained source is available for this source comparison."
    if code=="XMP_CREATEDATE_SEMANTICS" and not ctx["source_has_valid_date"] and not source_condition_unknown:
        return "No retained source has a normalized sent date for the creation-date comparison."
    if code=="MESSAGE_RAW_SENT_REQUIRED_IF_INVALID" and not ctx["source_has_invalid_date"] and not source_condition_unknown:
        return "No inspected source has an unnormalizable Date header requiring Raw-Sent."
    if code=="MESSAGE_RAW_SENT_OMIT_IF_VALID" and not ctx["source_has_valid_date"] and not source_condition_unknown:
        return "No inspected source has a valid Date header to which this recommendation applies."
    optional_raw_addresses={"MESSAGE_RAW_TO_XMP":"Raw-To","MESSAGE_RAW_FROM_XMP":"Raw-From",
                            "MESSAGE_RAW_CC_XMP":"Raw-Cc","MESSAGE_RAW_BCC_XMP":"Raw-Bcc"}
    if code in optional_raw_addresses and optional_raw_addresses[code] not in ctx.get("raw_address_properties",ctx["message_properties"]):
        return "This optional raw address property is not supplied; unrelated original headers do not require it."
    optional_headers={
        "MESSAGE_CC_XMP":("raw_cc","Cc"), "MESSAGE_CC_FOAF":("raw_cc","Cc"),
        "MESSAGE_BCC_XMP":("raw_bcc","Bcc"), "MESSAGE_BCC_FOAF":("raw_bcc","Bcc"),
        "MESSAGE_INREPLYTO_XMP":("in_reply_to","In-Reply-To"),
    }
    if code in optional_headers:
        source_key,xmp_key=optional_headers[code]
        if source_key not in ctx["source_raw_fields"] and xmp_key not in ctx["message_properties"] and not source_condition_unknown:
            return "Neither inspected source nor email XMP supplies this optional header."
    if code=="XMP_MISSING" and ctx["xmp_readable"]:
        return f"Not applied because document XMP was present and readable, so the missing-XMP failure does not apply. Context: {snapshot}."
    if code=="XMP_PARSE" and ctx["xmp_readable"]:
        return f"Not applied because document XMP parsed successfully, so the XMP-parse failure does not apply. Context: {snapshot}."
    if code in ("PDFA_XMP_UNCHECKED","EAPDF_XMP_UNCHECKED","XMP_KEYWORDS_UNCHECKED"):
        if ctx["xmp_readable"]:
            return f"Not applied because XMP metadata was readable, so the unchecked fallback rule does not apply. Context: {snapshot}."
    if code=="PROFILE_RULES_SKIPPED" and ctx["eapdf_conf"]:
        return f"Not applied because EA-PDF conformance was available as {profile_label(ctx)}, so profile-specific checks were executed. Context: {snapshot}."
    if code=="DOCINFO_KEYWORDS" and ctx["pdfa_part"]!=3:
        return f"Not applied because the Document Information Keywords requirement applies only to PDF/A-3-based EA-PDF files, and this PDF declares PDF/A-{ctx['pdfa_part'] if ctx['pdfa_part'] is not None else 'unknown'}. Context: {snapshot}."
    if code in ("DOCINFO_TITLE_MATCH","DOCINFO_PRODUCER_MATCH","DOCINFO_AUTHOR_MATCH","DOCINFO_SUBJECT_MATCH","DOCINFO_CREATOR_MATCH","DOCINFO_CREATIONDATE_MATCH","DOCINFO_MODDATE_MATCH"):
        if ctx["pdfa_part"]!=3:
            return f"Not applied because this consistency crosswalk check applies only to PDF/A-3 files, and this PDF declares PDF/A-{ctx['pdfa_part'] if ctx['pdfa_part'] is not None else 'unknown'}. Context: {snapshot}."
        field={"DOCINFO_TITLE_MATCH":"Title","DOCINFO_PRODUCER_MATCH":"Producer",
               "DOCINFO_AUTHOR_MATCH":"Author","DOCINFO_SUBJECT_MATCH":"Subject",
               "DOCINFO_CREATOR_MATCH":"Creator","DOCINFO_CREATIONDATE_MATCH":"CreationDate",
               "DOCINFO_MODDATE_MATCH":"ModDate"}[code]
        keys=ctx.get("docinfo_keys")
        if keys is not None and field not in keys:
            return f"Not applied because optional Document Info {field} is absent; no corresponding value is supplied for this crosswalk. Context: {snapshot}."
    if code in ("PROFILE_COLLECTION","COLLECTION_REQUIRED","COLLECTION_EXTENSIONS","COLLECTION_D_FORBIDDEN","COLLECTION_VIEW_FORBIDDEN","COLLECTION_NAVIGATOR_FORBIDDEN","COLLECTION_SCHEMA_PRESENT","COLLECTION_SCHEMA_FIELD","COLLECTION_SCHEMA_E_FALSE"):
        if not ctx["container_profile"]:
            return f"Not applied because this rule applies only to EA-PDF container profiles c/ci, and this PDF is profile {profile_label(ctx)}. Context: {snapshot}."
    if code=="COLLECTION_FORBIDDEN" and ctx["container_profile"]:
        return f"Not applied because this rule applies only to non-container profiles, and this PDF is profile {profile_label(ctx)}. Context: {snapshot}."
    if code in ("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID","MESSAGE_RAW_SENT_OMIT_IF_VALID","MESSAGE_TO_XMP","MESSAGE_FROM_XMP","MESSAGE_CC_XMP","MESSAGE_BCC_XMP","MESSAGE_TO_FOAF","MESSAGE_FROM_FOAF","MESSAGE_CC_FOAF","MESSAGE_BCC_FOAF","MESSAGE_RAW_TO_XMP","MESSAGE_RAW_FROM_XMP","MESSAGE_RAW_CC_XMP","MESSAGE_RAW_BCC_XMP","MESSAGE_SUBJECT_XMP","MESSAGE_INREPLYTO_XMP","MESSAGE_SIZE_XMP","MESSAGE_ATTACHMENT_COUNT_XMP","ATTACHMENT_XMP_CONTENTTYPE_MATCH"):
        if not ctx["mail_profile"]:
            return f"Not applied because this rule applies only to EA-PDF mail profiles s/si/m/mi, and this PDF is profile {profile_label(ctx)}. Context: {snapshot}."
        if not ctx["inspectable_source"] and not source_condition_unknown:
            return f"Not applied because this source-to-XMP comparison needs an inspectable embedded RFC822 source email, and this PDF has {ctx['source_file_count']} Source AF file(s) but none is inspectable as RFC822 by the validator. Context: {snapshot}."
    if code in ("ATTACHMENT_XMP_FILENAME","ATTACHMENT_XMP_SIZE_PRESENT","ATTACHMENT_XMP_SIZE_TYPE","ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","ATTACHMENT_XMP_CONTENTTYPE","ATTACHMENT_XMP_CHECKSUM_FORM","ATTACHMENT_XMP_SIZE_MATCH","ATTACHMENT_XMP_CONTENTTYPE_MATCH"):
        if ctx["attachment_xmp_count"]==0:
            return f"Not applied because the PDF does not contain any XMP attachment records to validate. Context: {snapshot}."
    if code in {"ATTACHMENT_XMP_SIZE_MATCH","ATTACHMENT_XMP_CHECKSUM_MATCH"} and ctx["container_profile"]:
        return "Collection attachment payload/source comparisons execute in the owning contained email PDFs; collection members are not native copies of the attachments inside them."
    if code=="ATTACHMENT_XMP_SIZE_MATCH" and not ctx["inspectable_source"]:
        return f"No corresponding native payload or inspectable original source is available for this bounded attachment comparison. A separate native copy is not inferred as universally required. Context: {snapshot}."
    if code in ("ASSET_XMP_FIELDS","ASSET_SIZE_TYPE","ASSET_NUMBERMESSAGES_TYPE","ASSET_CHECKSUM_FORM","ASSET_CONTENTTYPE_GENERIC","ASSET_CONTENTTYPE","ASSET_MATCH","ASSET_MD5_MATCH","ASSET_SIZE_MATCH","ASSET_CONTENTTYPE_MATCH"):
        if ctx["asset_count"]==0:
            return f"Not applied because the PDF does not contain any XMP source asset records to validate. Context: {snapshot}."
    if code=="SOURCE_WITHOUT_XMP" and ctx["source_optional_profile"] and ctx["source_file_count"]==0:
        return f"Not applied because this source-optional profile may omit the embedded source email, and this file contains no Source AF to compare against asset XMP. Context: {snapshot}."
    if code=="SOURCE_WITHOUT_XMP" and ctx["source_file_count"]==0:
        return f"Not applied because the PDF does not contain any embedded Source associated files. Context: {snapshot}."
    if code in ("SOURCE_MIME_TYPE","SOURCE_MIME_GENERIC") and ctx["source_file_count"]==0:
        return f"Not applied because the PDF does not contain any embedded Source associated files. Context: {snapshot}."
    if code=="SOURCE_AF" and ctx["source_optional_profile"] and ctx["source_file_count"]==0:
        return f"Not applied because this source-optional profile may omit the embedded source email, and this file contains no Source AF. Context: {snapshot}."
    if code=="SOURCE_MIME_NONE" and ctx["source_file_count"]>0:
        return f"Not applied because Source associated files were present ({ctx['source_file_count']}), so the no-source diagnostic does not apply. Context: {snapshot}."
    if code in ("ASSET_XMP","ASSET_XMP_FIELDS","ASSET_SIZE_TYPE","ASSET_NUMBERMESSAGES_TYPE","ASSET_CHECKSUM_FORM","ASSET_CONTENTTYPE_GENERIC","ASSET_CONTENTTYPE","ASSET_MATCH","ASSET_MD5_MATCH","ASSET_SIZE_MATCH","ASSET_CONTENTTYPE_MATCH") and ctx["source_optional_profile"] and ctx["source_file_count"]==0 and ctx["asset_count"]==0:
        return f"Not applied because this source-optional profile may omit the embedded source email, and this file contains neither Source AF entries nor source-asset XMP records. Context: {snapshot}."
    if code in ("SOURCE_AF","SOURCE_WITHOUT_XMP","ASSET_XMP") and ctx["isolated_profile"]:
        return f"Not applied because this non-isolated source-asset rule does not apply to isolated profile {profile_label(ctx)}. Context: {snapshot}."
    if code=="SOURCE_IN_ISOLATED" and not ctx["isolated_profile"]:
        return f"Not applied because this rule applies only to isolated profiles, and this PDF is profile {profile_label(ctx)}. Context: {snapshot}."
    if code in ("MAIL_MESSAGE_COUNT_SINGLE","MAIL_MESSAGE_COUNT_MULTI","MAIL_MESSAGE_COUNT_CONTAINER","PROFILE_EMAIL_COUNT_SINGLE","PROFILE_EMAIL_COUNT_MULTI"):
        if not ctx["eapdf_conf"]:
            return f"Not applied because EA-PDF conformance is unavailable, so profile cardinality cannot be specialized. Context: {snapshot}."
    if code=="CONTENT_SET_COUNT" and ctx["content_set_count"]==0:
        return f"Not applied because the PDF does not define any DPart leaf content sets. Context: {snapshot}."
    if code=="CONTENT_SET_NONBLANK" and ctx["content_set_count"]==0:
        return f"Not applied because the PDF does not define any DPart leaf content sets whose pages could be checked for visible content. Context: {snapshot}."
    if code=="CONTENT_SET_OUTLINE_REF" and ctx["content_set_count"]==0:
        return f"Not applied because the PDF does not define any DPart leaf content sets whose first page could be checked against the outline tree. Context: {snapshot}."
    if code=="CONTENT_SET_OUTLINE_REF" and not ctx["has_outlines"]:
        return f"Not applied because the PDF does not contain a Document Catalog Outlines entry. Context: {snapshot}."
    if code in ("DPM_INTERMEDIATE_COUNT_SINGLE","DPM_INTERMEDIATE_COUNT_MULTI","DPM_INTERMEDIATE_COUNT_CONTAINER") and not ctx["eapdf_conf"]:
        return f"Not applied because EA-PDF conformance is unavailable, so profile-specific DPM cardinality cannot be specialized. Context: {snapshot}."
    if code=="DPM_ATTACHMENTLIST_FORBIDDEN" and not ctx["container_profile"]:
        return f"Not applied because AttachmentList DPM restrictions apply only to container profiles, and this PDF is profile {profile_label(ctx)}. Context: {snapshot}."
    if code=="MAIL_CONTENTGROUP_CHILDREN" and "Mail_ContentGroup" not in ctx["custom_types"]:
        return f"Not applied because the logical structure does not contain any Mail_ContentGroup elements. Present custom tags: {', '.join(sorted(ctx['custom_types'])) or 'none'}. Context: {snapshot}."
    if code=="MAIL_FIELD_CHILDREN" and "Mail_Field" not in ctx["custom_types"]:
        return f"Not applied because the logical structure does not contain any Mail_Field elements. Present custom tags: {', '.join(sorted(ctx['custom_types'])) or 'none'}. Context: {snapshot}."
    if code=="MAIL_FIELDVALUE_WITH_NAME" and "Mail_FieldValue" not in ctx["custom_types"]:
        return f"Not applied because the logical structure does not contain any Mail_FieldValue elements. Present custom tags: {', '.join(sorted(ctx['custom_types'])) or 'none'}. Context: {snapshot}."
    if code in ("USERPROPS_NAME_ASCII","USERPROPS_TYPE","USERPROPS_TAG_SCOPE") and not ctx["userprops_present"]:
        return f"Not applied because the logical structure does not use any UserProperties entries. Context: {snapshot}."
    if code=="ASSET_NUMBERMESSAGES_TYPE" and not ctx["asset_numbermessages_count"]:
        return f"Not applied because no XMP asset record in this PDF declares NumberMessages. Context: {snapshot}."
    if code=="ATTACHMENT_XMP_CONTAINER" and ctx["attachment_xmp_count"]==0 and not ctx["inspectable_source"]:
        return f"Not applied because the PDF has no XMP attachment records and no inspectable embedded RFC822 source email to establish whether they are expected. Context: {snapshot}."
    return None

def finalize_rule_matrix(report, ctx):
    missing=[]
    for code in ALL_RULE_CODES:
        if code in report.seen_codes or code=="VALIDATION_INCOMPLETE":
            continue
        reason=na_reason_for_code(code, ctx)
        if reason is not None:
            report.na(code, "Rule not applicable for this PDF", {"reason": reason})
        elif RULE_TEXT.get(code,{}).get("validation_scope",{}).get("check",{}).get("strength")=="SHOULD":
            report.warn(code,"Recommendation assessment is unavailable",{"state":"unverified","advisory":True})
        else:
            missing.append(code)
            report.warn(code, "Rule applicability or execution has not been established",
                        {"state":"unverified","reason":"No result or explicit non-applicability decision was recorded."})
    if missing:
        report.warn("VALIDATION_INCOMPLETE","Some rules have no verified result or applicability decision",{"rules":missing})
    elif "VALIDATION_INCOMPLETE" not in report.seen_codes:
        report.na("VALIDATION_INCOMPLETE","No check reported incomplete validation",
                  {"reason":"All known checks produced a result or an explicit non-applicability decision."})


def check_required_email_metadata(root_xml,conformance,report):
    return _xmp_rules.check_required_email_metadata(_xmp_rules_dependencies(), root_xml, conformance, report)

def dpart_level_name_assessment(value):
    """Keep Name/NMTOKEN-only and undecodable interpretations unavailable."""
    if not isinstance(value,pikepdf.Name):
        return "invalid","NodeNameList members must be PDF names"
    try:
        text=bytes(value)[1:].decode("utf-8")
    except UnicodeError:
        return "unavailable","Cannot assess XML spelling through the recommended UTF-8 name interpretation"
    # XML1.0 Fifth Edition productions [4], [4a], [5] and [7]. A Name is
    # unambiguously valid under either reading of the PDF wording 'Name token'.
    start=r"A-Z_a-z:\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u02FF\u0370-\u037D\u037F-\u1FFF\u200C-\u200D\u2070-\u218F\u2C00-\u2FEF\u3001-\uD7FF\uF900-\uFDCF\uFDF0-\uFFFD\U00010000-\U000EFFFF"
    extra=r"\-.0-9\u00B7\u0300-\u036F\u203F-\u2040"
    if re.fullmatch(fr"[{start}][{start}{extra}]*",text):
        return "valid",None
    if text and re.fullmatch(fr"[{start}{extra}]+",text):
        return "unavailable","Label is an XML NMTOKEN but not an XML Name; the PDF Name-token interpretation remains under review"
    return "invalid","Label contains characters disallowed by both XML Name and NMTOKEN"

def inspect_dpm_value_graph(dpm,state):
    """Inspect each reachable data container once, with bounded work and no tree policy."""
    errors=[]
    name_errors=[]
    unavailable=[]
    frames=[(iter([(None,dpm)]),False,[],0)]
    while frames:
        iterator,dictionary_entry,parent_path,depth=frames[-1]
        try:
            key,value=next(iterator)
        except StopIteration:
            frames.pop()
            continue
        except (UnicodeError,ValueError,pikepdf.PdfError):
            unavailable.append({"path":parent_path,"reason":"DPM dictionary entries could not be decoded"})
            state["unavailable_values"]=True
            frames.pop()
            continue
        if state["items"]>=MAX_TREE_ITEMS or depth>MAX_TREE_DEPTH:
            unavailable.append({"path":parent_path,"reason":"DPM value traversal exceeded configured depth/item limits"})
            state["limited"]=True
            state["unavailable_values"]=True
            break
        state["items"]+=1
        if dictionary_entry and value is None:
            continue  # Null dictionary entries are absent, unlike null array items.
        path=parent_path
        if key is not None:
            try:
                display=str(key).encode("utf-8","backslashreplace").decode("utf-8") if dictionary_entry else key
            except UnicodeError:
                display="<undecodable key>"
            path=parent_path+[display]
        if dictionary_entry:
            try:
                name=key if isinstance(key,pikepdf.Name) else pikepdf.Name(key)
                status,reason=dpart_level_name_assessment(name)
            except (UnicodeError,TypeError,ValueError):
                status,reason="unavailable","DPM key spelling could not be assessed"
            detail={"path":path,"reason":reason}
            if status=="invalid":errors.append(detail)
            elif status=="unavailable":unavailable.append(detail)
        if isinstance(value,pikepdf.Stream) or value is None:
            errors.append({"path":path,"actual_type":"stream" if value is not None else "null",
                           "reason":"DPM values cannot be streams or null array members"})
        elif isinstance(value,pikepdf.Dictionary) or is_pdf_array(value):
            identity=pdf_object_identity(value)
            if identity in state["seen"]:
                continue
            # Keep direct wrappers alive so their Python identities cannot be reused.
            state["seen"][identity]=value
            try:
                entries=iter(value.items()) if isinstance(value,pikepdf.Dictionary) else iter(enumerate(value))
                frames.append((entries,isinstance(value,pikepdf.Dictionary),path,depth+1))
            except (UnicodeError,ValueError,pikepdf.PdfError):
                unavailable.append({"path":path,"reason":"DPM container entries could not be decoded"})
                state["unavailable_values"]=True
        elif isinstance(value,pikepdf.Name):
            try:
                bytes(value)[1:].decode("utf-8")
            except UnicodeError:
                name_errors.append({"path":path,"actual":observed_pdf_value(value),
                                    "reason":"DPM name values must have valid decoded UTF-8"})
        elif not isinstance(value,(pikepdf.String,bool,int,float,Decimal)):
            errors.append({"path":path,"actual_type":type(value).__name__,"reason":"Unsupported DPM value type"})
    return errors,name_errors,unavailable

def check_dpart_object_structure(dpart_root,root_node,nodes,report):
    return _structure_rules.check_dpart_object_structure(_structure_rules_dependencies(), dpart_root, root_node, nodes, report)

class ChildValidationContext:
    def __init__(self,include_pdfa=False):
        self.include_pdfa=include_pdfa
        self.cache={}
        self.active=set()
        self.count=0
        self.total_bytes=0
        self.max_depth=max(1,int(os.environ.get("VALIDATOR_CHILD_MAX_DEPTH","8")))
        self.max_count=max(1,int(os.environ.get("VALIDATOR_CHILD_MAX_COUNT","250000")))
        self.max_bytes=max(1,int(os.environ.get("VALIDATOR_CHILD_MAX_BYTES",str(64*1024*1024*1024))))
        self.temporary_root=Path(os.environ.get("VALIDATOR_CHILD_TEMP_ROOT",str(VERAPDF_MOUNT_ROOT)))

def child_rendered_source_proof(result):
    """Only completed child validation can contribute source-bound message identities."""
    if result.get("summary",{}).get("status")!="PASS" or result.get("pdfa",{}).get("status")!="PASS":
        return None
    counts={item.get("details",{}).get("count") for item in result.get("items",[])
            if item["code"]=="EMAIL_XMP_CARDINALITY" and item["level"]=="PASS" and isinstance(item.get("details"),dict)}
    if len(counts)!=1 or not isinstance(next(iter(counts)),int) or next(iter(counts))<1:
        return None
    messages={}
    for item in result.get("items",[]):
        if item["code"]!="MESSAGE_SOURCE_COVERAGE" or item["level"]!="PASS":
            continue
        details=item.get("details") or {};guid=details.get("Mail_GUID");checksum=details.get("source_md5");size=details.get("source_size")
        if not isinstance(guid,str) or not guid or not isinstance(checksum,str) or not re.fullmatch(r"[0-9a-fA-F]{32}",checksum) or type(size) is not int or size<0:
            continue
        key=(checksum.lower(),size)
        if guid in messages and messages[guid]!=key:
            return None
        messages[guid]=key
        if len(messages)>MAX_TREE_ITEMS:
            return None
    return messages if len(messages)==next(iter(counts)) else None

def check_collection_asset_counts(xroot,afiles,report):
    return _embedding_rules.check_collection_asset_counts(_embedding_rules_dependencies(), xroot, afiles, report)

def check_collection_children(efiles,eapdf_conf,report,context,depth,source_assets=(),related_streams=()):
    if eapdf_conf not in ("c","ci"):
        return
    checked=0
    proof={"complete":True,"messages":{}}
    report._collection_count_proof=proof
    for embedded in efiles:
        if embedded.get("objgen") in source_assets or embedded.get("ef_objgen") in related_streams:
            continue
        data=embedded.get("bytes",b"")
        candidate=embedded.get("collection_item",False)
        is_pdf=embedded.get("subtype","").lower()=="application/pdf" or data.startswith(b"%PDF-")
        candidate=candidate or is_pdf
        if not candidate:
            proof["complete"]=False
            continue
        checked+=1
        name=embedded.get("ufilename") or embedded.get("filename") or embedded.get("tree_name")
        digest=sha256hex(data)
        context.count+=1
        context.total_bytes+=len(data)
        if depth>=context.max_depth or context.count>context.max_count or context.total_bytes>context.max_bytes:
            proof["complete"]=False
            report.warn("VALIDATION_INCOMPLETE","Collection child validation exceeded its configured depth, count, or byte limit",{"child":name})
            break
        if digest in context.active:
            proof["complete"]=False
            report.fail("COLLECTION_CHILD_VALIDATION","Recursive collection child detected",{"child":name})
            continue
        result=context.cache.get(digest)
        if result is None:
            context.active.add(digest)
            try:
                context.temporary_root.mkdir(parents=True,exist_ok=True,mode=0o700)
                with tempfile.TemporaryDirectory(prefix=".eapdf-child-",dir=context.temporary_root) as folder:
                    path=Path(folder)/"message.pdf"
                    path.write_bytes(data)
                    if context.include_pdfa:
                        result=validate_one(str(path),write_html=False,child_context=context,depth=depth+1)
                    else:
                        child_report=validate(str(path),child_context=context,depth=depth+1)
                        result={"summary":child_report.summary(),"items":child_report.items,"pdfa":{"status":"SKIP"}}
            except Exception as error:
                proof["complete"]=False
                report.warn("COLLECTION_CHILD_VALIDATION","Unable to complete contained email PDF validation",{
                    "child":name,"error":str(error),"error_type":type(error).__name__,
                    "assessment":"unavailable","result_available":result is not None})
                report.warn("VALIDATION_INCOMPLETE","A contained email PDF check encountered an operational error",{
                    "child":name,"assessment":"unavailable"})
                if result is None:
                    continue
            finally:
                context.active.remove(digest)
            context.cache[digest]=result
        child_proof=child_rendered_source_proof(result)
        if child_proof is None or len(proof["messages"])+(len(child_proof) if child_proof else 0)>MAX_TREE_ITEMS or any(guid in proof["messages"] for guid in child_proof or {}):
            proof["complete"]=False
        else:
            proof["messages"].update(child_proof)
        details={"child":name,"sha256":digest,"status":result["summary"]["status"],
                 "pdfa_status":result["pdfa"]["status"],
                 "pdfa":{key:value for key,value in result["pdfa"].items() if key in
                         {"status","message","profile_name","failed_rules","failed_checks","exit_code","stderr"}},
                 "findings":[item for item in result["items"] if item["level"] in ("FAIL","WARN")]}
        if result["summary"]["status"]=="FAIL":
            report.fail("COLLECTION_CHILD_VALIDATION","Contained email PDF failed validation",details)
        elif result["summary"]["status"]!="PASS" or result["pdfa"]["status"]!="PASS":
            report.warn("COLLECTION_CHILD_VALIDATION","Contained email PDF validation is incomplete",details)
            report.warn("VALIDATION_INCOMPLETE","A contained email PDF has incomplete validation",{"child":name})
        else:
            report.ok("COLLECTION_CHILD_VALIDATION","Contained email PDF passes PDF/A and EA-PDF checks",details)
    if not checked:
        proof["complete"]=False
        report.fail("COLLECTION_CHILD_VALIDATION","Collection contains no identifiable email PDF members",{"checked":checked,"files":embedded_file_observations(efiles)})

@bounded_validation
def validate_pdf(pdf,report,child_context=None,depth=0,source_file_sets=None,pdfa=None):
    record_verapdf_delegations(report,pdfa)
    check_iso32005_upstream(pdf,report,pdfa)
    report.ok("OPEN","Opened PDF successfully",{"file":str(report.path),"pdf_version":str(pdf.pdf_version),"pages":len(pdf.pages)})
    check_pdf_version(pdf,report)
    check_pages(pdf,report)
    if not report.delegates("OUTPUT_INTENT"):
        check_output_intents(pdf,report)
    _,outline_pages=check_outlines(pdf,report)
    xmp_bytes=read_metadata_stream(pdf)
    if not report.delegates("XMP_UTF8"):
        check_xmp_utf8(pdf,report)
    xroot,nsmap,_=parse_xmp(xmp_bytes,report)
    if xroot is not None and any((node.text or "").strip() == "INCOMPLETE"
            for node in xroot.iter("{https://thinkbox.digital/ns/eapdf/conversion/1.0/}status")):
        report.warn("VALIDATION_INCOMPLETE", "The creator recorded missing source information. See the conversion warnings in the PDF.",
                    {"assessment": "unavailable"})
    pdfa_part=None
    pdfa_conf=""
    eapdf_conf=""
    if xroot is not None:
        report.ok("PDFA_XMP_UNCHECKED","PDF/A identification metadata checks were applied because XMP metadata was readable",{"part":first_xmp_value(xroot,NS_PDFAID,"part"),"conformance":first_xmp_value(xroot,NS_PDFAID,"conformance")})
        report.ok("EAPDF_XMP_UNCHECKED","EA-PDF identification metadata checks were applied because XMP metadata was readable",{"version":first_xmp_value(xroot,NS_PDFMAILID,"version"),"rev":first_xmp_value(xroot,NS_PDFMAILID,"rev"),"conformance":first_xmp_value(xroot,NS_PDFMAILID,"conformance")})
        report.ok("XMP_KEYWORDS_UNCHECKED","XMP keyword checks were applied because XMP metadata was readable",{"Keywords":first_xmp_value(xroot,NS_PDF,"Keywords")})
        pdfa_part,pdfa_conf=check_pdfa_xmp(xroot,report)
        eapdf_conf=check_eapdf_xmp(xroot,nsmap,report)
        check_keywords(pdf,xroot,pdfa_part,report)
        check_docinfo_consistency(pdf,xroot,pdfa_part,report)
    else:
        check_xmp_dependent_unchecked(report,xmp_bytes)
    efiles,afiles=check_embedded_files(pdf,xroot,eapdf_conf,pdfa_part,report)
    related_streams,supplements=check_source_file_sets(pdf,xroot,afiles,report,source_file_sets)
    check_collection_content(pdf,efiles,afiles,eapdf_conf,report,related_streams,supplements)
    check_catalog_rules(pdf,report,eapdf_conf,efiles,afiles,xroot)
    check_collection_rules(pdf,eapdf_conf,report)
    check_dpart_and_content_sets(pdf,xroot,eapdf_conf,report,outline_pages)
    check_logical_structure(pdf,eapdf_conf,report)
    check_file_attachment_contents(pdf,report)
    check_structure_correspondence(pdf,report)
    af_streams={file["ef_objgen"] for file in afiles}
    related_assets=[file for identity,file in related_streams.items() if identity not in af_streams]
    check_asset_metadata(xroot,efiles,afiles,eapdf_conf,report,related_assets)
    check_required_email_metadata(xroot,eapdf_conf,report)
    if xroot is not None and eapdf_conf in {"s","si","m","mi"}:
        native_records=flatten_email_records(xroot)
        native_scopes=native_attachment_owner_scopes(pdf,native_records)
        guid_by_record={id(record):guid_value(record.get("Mail_GUID","")) for record in native_records}
        report._native_attachment_files=embedded_file_candidates(pdf,efiles,afiles)
        for file in report._native_attachment_files:
            scope=native_scopes.get(file.get("objgen"))
            file["_scoped_email_guids"]={guid_by_record[key] for key in scope} if scope is not None else None
    check_message_metadata(xroot,afiles,eapdf_conf,report)
    check_profile_rules(pdf,efiles,afiles,eapdf_conf,pdfa_part,report,xroot)
    check_standard_xmp_semantics(xroot,afiles,report)
    check_source_media_types(afiles,eapdf_conf,report)
    check_additional_rule_coverage(pdf, xroot, xmp_bytes, efiles, afiles, eapdf_conf, pdfa_part, report)
    check_fonts(pdf,report)
    source_assets={f["objgen"] for f in afiles if f["afrelationship"]=="Source"} | {f["objgen"] for f in supplements}
    check_collection_children(efiles,eapdf_conf,report,child_context or ChildValidationContext(),depth,source_assets,related_streams)
    if eapdf_conf in {"c","ci"}:
        check_collection_asset_counts(xroot,afiles,report)
    finalize_rule_matrix(report, build_validation_context(pdf, xroot, eapdf_conf, pdfa_part, efiles, afiles,
                                                        getattr(report,"_email_source_summaries",None)))
    return report

def validate(path,child_context=None,depth=0,source_file_sets=None,pdfa=None):
    report=Report(path)
    try:
        with pikepdf.Pdf.open(path) as pdf:
            return validate_pdf(pdf,report,child_context,depth,source_file_sets,pdfa)
    except Exception as error:
        code="VALIDATION_ERROR" if "OPEN" in report.seen_codes else "OPEN"
        report.fail(code,"Unable to complete PDF validation",{"error":str(error)})
        return report


def classify_verification_spec(items):
    levels = {item["code"]: item for item in items}
    conf_item = levels.get("EAPDF_CONFORMANCE_VALUE")
    conf_value = ""
    if conf_item and isinstance(conf_item.get("details"), dict):
        conf_value = str(conf_item["details"].get("actual", conf_item["details"].get("value", ""))).strip().lower()
    if conf_value:
        return f"1{conf_value}"
    return ""

def print_text(report, pdfa=None):
    print("File:",report.path)
    print("Status:",combined_validation_status(report.summary(), pdfa))
    print("Scope:",VALIDATION_SCOPE)
    if pdfa is not None:
        print("PDF/A First Pass:", pdfa.get("status",""), "-", pdfa.get("message",""))
        if pdfa.get("profile_name"):
            print("PDF/A Profile:", pdfa["profile_name"])
    print("Pass:",report.summary()["pass"],"Warn:",report.summary()["warn"],"Fail:",report.summary()["fail"],"N/A:",report.summary()["na"])
    print("Rules:",f"{report.summary()['rule_total']} total",
          "Pass:",report.summary()["rule_pass"],
          "Warn:",report.summary()["rule_warn"],
          "Fail:",report.summary()["rule_fail"],
          "N/A:",report.summary()["rule_na"])
    levels=("FAIL","WARN","PASS","N/A")
    for level in levels:
        print()
        print(level)
        for item in report.items:
            if item["level"]!=level: continue
            print(" ",item["code"],"-",item["message"])
            if item["details"] is not None:
                try:
                    print("   ",json.dumps(item["details"],ensure_ascii=False,sort_keys=True))
                except Exception:
                    print("   ",str(item["details"]))

def collect_pdf_inputs(path_arg):
    path=Path(path_arg)
    if path.is_dir():
        return sorted(str(p) for p in path.glob("*.pdf"))
    return [str(path)]

def validate_one(path, write_html=True, html_path=None, child_context=None, depth=0, source_file_sets=None):
    pdfa=run_verapdf(path)
    report=validate(path,child_context or ChildValidationContext(include_pdfa=True),depth,source_file_sets,pdfa=pdfa)
    eapdf_summary=report.summary()
    overall_status=combined_validation_status(eapdf_summary, pdfa)
    summary=dict(eapdf_summary)
    summary["verification_spec"]=classify_verification_spec(report.items)
    summary["eapdf_status"]=eapdf_summary["status"]
    summary["pdfa_status"]=pdfa.get("status")
    summary["status"]=overall_status
    summary["validation_scope"]=VALIDATION_SCOPE
    html_output=None
    if write_html:
        html_output=str(write_html_report(report, path, html_path, pdfa))
    return {
        "summary": summary,
        "pdfa": pdfa,
        "items": report.items,
        "html_output": html_output,
    }

def print_batch_text(results):
    total_pass=sum(r["summary"]["pass"] for r in results)
    total_warn=sum(r["summary"]["warn"] for r in results)
    total_fail=sum(r["summary"]["fail"] for r in results)
    total_na=sum(r["summary"]["na"] for r in results)
    total_rule_pass=sum(r["summary"]["rule_pass"] for r in results)
    total_rule_warn=sum(r["summary"]["rule_warn"] for r in results)
    total_rule_fail=sum(r["summary"]["rule_fail"] for r in results)
    total_rule_na=sum(r["summary"]["rule_na"] for r in results)
    total_rule_count=sum(r["summary"]["rule_total"] for r in results)
    failed_files=sum(1 for r in results if r["summary"]["status"]=="FAIL")
    incomplete_files=sum(1 for r in results if r["summary"]["status"]=="INCOMPLETE")
    pdfa_pass=sum(1 for r in results if r.get("pdfa",{}).get("status")=="PASS")
    pdfa_fail=sum(1 for r in results if r.get("pdfa",{}).get("status")=="FAIL")
    pdfa_other=sum(1 for r in results if r.get("pdfa",{}).get("status") not in {"PASS","FAIL"})
    print("Files:",len(results))
    print("Status:",batch_validation_status(results))
    print("Failed files:",failed_files,"Incomplete files:",incomplete_files)
    print("PDF/A First Pass:","Pass:",pdfa_pass,"Fail:",pdfa_fail,"Other:",pdfa_other)
    print("Pass:",total_pass,"Warn:",total_warn,"Fail:",total_fail,"N/A:",total_na)
    print("Rules:",total_rule_count,"Pass:",total_rule_pass,"Warn:",total_rule_warn,"Fail:",total_rule_fail,"N/A:",total_rule_na)
    print()
    for result in results:
        summary=result["summary"]
        pdfa=result.get("pdfa",{})
        print(f"{summary['status']} {summary['file']} pass={summary['pass']} warn={summary['warn']} fail={summary['fail']} na={summary['na']} rules={summary['rule_total']} rule_pass={summary['rule_pass']} rule_warn={summary['rule_warn']} rule_fail={summary['rule_fail']} rule_na={summary['rule_na']}")
        print(" PDF/A:", pdfa.get("status",""), "-", pdfa.get("profile_name",""), "-", pdfa.get("message",""))
        if result["html_output"] is not None:
            print(" HTML report:",result["html_output"])

def run_batch(inputs, jobs, write_html, html_path):
    executor_used="process"
    try:
        with ProcessPoolExecutor(max_workers=jobs) as ex:
            results=list(ex.map(validate_one, inputs, [write_html]*len(inputs), [html_path]*len(inputs)))
        return results, executor_used
    except PermissionError:
        executor_used="thread"
        with ThreadPoolExecutor(max_workers=jobs) as ex:
            results=list(ex.map(validate_one, inputs, [write_html]*len(inputs), [html_path]*len(inputs)))
        return results, executor_used

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("pdf", help="PDF file or directory containing PDFs")
    ap.add_argument("--jobs", type=int, default=max(1, os.cpu_count() or 1), help="number of worker processes for directory validation")
    ap.add_argument("--json",action="store_true")
    ap.add_argument("--html", nargs="?", const="", default=None, help="write a single-file HTML report to the given path; omit the path to use validation/<pdf>.<id>.validation.html")
    ap.add_argument("--no-html", action="store_true", help="do not write the HTML validation report")
    args=ap.parse_args()
    inputs=collect_pdf_inputs(args.pdf)
    if not inputs:
        print("No PDF files found for validation.",file=sys.stderr)
        sys.exit(2)
    if os.environ.get("EAPDF_VALIDATION_WORKER") != "1":
        from validation_worker import run_cli
        code, output, errors, reason = run_cli(Path(__file__).resolve(), sys.argv[1:])
        if errors: sys.stderr.write(errors.decode("utf-8", "replace"))
        if reason is None:
            sys.stdout.write(output.decode("utf-8", "replace")); sys.exit(code)
        results = []
        for path in inputs:
            report = Report(path); report.warn("VALIDATION_INCOMPLETE", reason, {"assessment": "unavailable"})
            pdfa = _verapdf_default_result(path, "ERROR", reason)
            summary = dict(report.summary(), pdfa_status="ERROR", eapdf_status="INCOMPLETE", status="INCOMPLETE",
                           verification_spec="", validation_scope=VALIDATION_SCOPE)
            html_output = str(write_html_report(report, path, (args.html or None) if len(inputs)==1 else None, pdfa)) if not args.no_html else None
            results.append({"summary": summary, "pdfa": pdfa, "items": report.items, "html_output": html_output})
        if args.json:
            value = {key:results[0][key] for key in ("summary", "pdfa", "items")} if len(results)==1 else {
                "summary":{"input":args.pdf,"files":len(results),"status":"INCOMPLETE","incomplete_files":len(results)}, "results":results}
            print(json.dumps(value, ensure_ascii=False, indent=2))
        else: print_batch_text(results)
        sys.exit(2)
    if len(inputs)==1:
        result=validate_one(inputs[0], write_html=not args.no_html, html_path=args.html or None)
        if args.json:
            print(json.dumps({"summary":result["summary"],"pdfa":result["pdfa"],"items":result["items"]},ensure_ascii=False,indent=2,sort_keys=False))
        else:
            report=Report(result["summary"]["file"])
            report.items=result["items"]
            report.counts["PASS"]=result["summary"]["pass"]
            report.counts["WARN"]=result["summary"]["warn"]
            report.counts["FAIL"]=result["summary"]["fail"]
            report.counts["N/A"]=result["summary"]["na"]
            print_text(report, result.get("pdfa"))
            if result["html_output"] is not None:
                print()
                print("HTML report:", result["html_output"])
        sys.exit(0 if result["summary"]["status"]=="PASS" else 2)
    jobs=min(max(1, args.jobs), max(1, int(os.environ.get("VALIDATOR_MAX_CONCURRENT_JOBS", "2"))))
    results,executor_used=run_batch(inputs, jobs, not args.no_html, None)
    if args.json:
        print(json.dumps({
            "summary": {
                "input": args.pdf,
                "files": len(results),
                "executor": executor_used,
                "pass_files": sum(1 for r in results if r["summary"]["status"]=="PASS"),
                "fail_files": sum(1 for r in results if r["summary"]["status"]=="FAIL"),
                "incomplete_files": sum(1 for r in results if r["summary"]["status"]=="INCOMPLETE"),
                "pdfa_pass_files": sum(1 for r in results if r.get("pdfa",{}).get("status")=="PASS"),
                "pdfa_fail_files": sum(1 for r in results if r.get("pdfa",{}).get("status")=="FAIL"),
                "pdfa_other_files": sum(1 for r in results if r.get("pdfa",{}).get("status") not in {"PASS","FAIL"}),
                "pass": sum(r["summary"]["pass"] for r in results),
                "warn": sum(r["summary"]["warn"] for r in results),
                "fail": sum(r["summary"]["fail"] for r in results),
                "na": sum(r["summary"]["na"] for r in results),
                "rule_pass": sum(r["summary"]["rule_pass"] for r in results),
                "rule_warn": sum(r["summary"]["rule_warn"] for r in results),
                "rule_fail": sum(r["summary"]["rule_fail"] for r in results),
                "rule_na": sum(r["summary"]["rule_na"] for r in results),
                "rule_total": sum(r["summary"]["rule_total"] for r in results),
                "status": batch_validation_status(results),
            },
            "files": results,
        },ensure_ascii=False,indent=2,sort_keys=False))
    else:
        print("Executor:", executor_used)
        print_batch_text(results)
    sys.exit(0 if all(r["summary"]["status"]=="PASS" for r in results) else 2)

if __name__=="__main__":
    main()
