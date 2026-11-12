"""Basic PDF/version/catalog/font rules with explicit borrowed validation state.

These contexts do not own or close documents. The root facade supplies current
object helpers and policy callbacks, keeping public monkey-patching semantics.
"""
from dataclasses import dataclass
from typing import Any, Callable
import re
import pikepdf


@dataclass(frozen=True)
class PdfObjects:
    get: Callable
    observe: Callable
    name: Callable
    normalize: Callable


@dataclass(frozen=True)
class PdfRuleContext:
    pdf: Any
    report: Any
    objects: PdfObjects


def check_pdf_version(context, *, declared_version, effective_version):
    hdr,rv=declared_version(context.pdf)
    root_ver=context.objects.get(context.pdf.Root,"/Version",None)
    effective=effective_version(hdr,rv)
    allowed_versions=("1.7","2.0")
    if hdr in allowed_versions:
        context.report.ok("PDF_HEADER_VERSION_READ","PDF header version allowed",{"header_version":hdr})
    else:
        context.report.fail("PDF_HEADER_VERSION_READ","PDF header version must be 1.7 or 2.0",{"header_version":hdr,"expected_versions":list(allowed_versions)})
    if root_ver is not None and not isinstance(root_ver,pikepdf.Name):
        context.report.fail("PDF_CATALOG_VERSION","Catalog /Version must be a PDF name",{
            "actual":context.objects.observe(root_ver),"is_pdf_name":False,"header_version":hdr})
    elif root_ver is not None and re.fullmatch(r"[0-9]\.[0-9]",rv) and effective in allowed_versions:
        context.report.ok("PDF_CATALOG_VERSION","Catalog /Version is a PDF version name with an allowed effective version",{
            "catalog_version":rv,"header_version":hdr,"effective_version":effective})
    elif root_ver is not None:
        context.report.fail(
            "PDF_CATALOG_VERSION",
            "Catalog /Version is malformed or yields an unsupported effective version",
            {"catalog_version":rv,"header_version":hdr,"effective_version":effective},
        )
    elif hdr in allowed_versions:
        context.report.ok(
            "PDF_CATALOG_VERSION",
            "Catalog /Version absent, but header provides an allowed PDF version",
            {"catalog_version":"","header_version":hdr},
        )
    else:
        context.report.fail(
            "PDF_CATALOG_VERSION",
            "Catalog /Version absent and header does not provide an allowed PDF version",
            {"catalog_version":"","header_version":hdr},
        )
    if effective in allowed_versions:
        context.report.ok("PDF_EFFECTIVE_VERSION","Effective PDF version allowed",{"header_version":hdr,"catalog_version":rv,"effective_version":effective})
    else:
        context.report.fail("PDF_EFFECTIVE_VERSION","Effective PDF version must be 1.7 or 2.0",{"header_version":hdr,"catalog_version":rv,"effective_version":effective})


def check_pages(context):
    n=len(context.pdf.pages)
    if n>0: context.report.ok("PAGE_COUNT","Document has pages",{"pages":n})
    else: context.report.fail("PAGE_COUNT","Document has no pages",{"pages":n})


def check_output_intents(context):
    oi=context.objects.get(context.pdf.Root,"/OutputIntents",None)
    if oi is None or len(oi)==0:
        context.report.fail("OUTPUT_INTENT","Missing OutputIntent",{"OutputIntents":context.objects.observe(oi)})
        return
    good=False
    details=[]
    for x in oi:
        d={"S":context.objects.name(context.objects.get(x,"/S",None)),"OutputConditionIdentifier":context.objects.normalize(str(context.objects.get(x,"/OutputConditionIdentifier",""))),"Info":context.objects.normalize(str(context.objects.get(x,"/Info","")))}
        details.append(d)
        if d["S"]=="GTS_PDFA1": good=True
    if good: context.report.ok("OUTPUT_INTENT","OutputIntent present",details)
    else: context.report.fail("OUTPUT_INTENT","OutputIntent present but missing /S /GTS_PDFA1",details)


def check_catalog_rules(context,eapdf_conf=None,efiles=None,afiles=None,root_xml=None, *, navigation_mode):
    root=context.pdf.Root
    if context.objects.get(root,"/PieceInfo",None) is None:
        context.report.ok("CATALOG_PIECEINFO","Catalog PieceInfo absent",{"PieceInfo":None})
    else:
        context.report.fail("CATALOG_PIECEINFO","Catalog PieceInfo must not be present",{"PieceInfo":context.objects.observe(root.PieceInfo)})
    vp=context.objects.get(root,"/ViewerPreferences",None)
    if vp is None:
        context.report.fail("VIEWER_PREFS","ViewerPreferences missing",{"ViewerPreferences":""})
    else:
        context.report.ok("VIEWER_PREFS","ViewerPreferences present",{"ViewerPreferences":context.objects.observe(vp)})
        ddt=context.objects.get(vp,"/DisplayDocTitle",None)
        if ddt is True:
            context.report.ok("DISPLAY_DOCTITLE","ViewerPreferences DisplayDocTitle is true",{"actual":context.objects.observe(ddt)})
        else:
            context.report.fail("DISPLAY_DOCTITLE","ViewerPreferences DisplayDocTitle must be true",{"actual":context.objects.observe(ddt)})
    page_mode=context.objects.name(context.objects.get(root,"/PageMode",None))
    nfsm=context.objects.name(context.objects.get(vp,"/NonFullScreenPageMode",None)) if vp is not None else ""
    expected_mode = navigation_mode(eapdf_conf, efiles, afiles, root_xml)
    if page_mode:
        context.report.ok("PAGEMODE_PRESENT","PageMode present",{"PageMode":page_mode,"NonFullScreenPageMode":nfsm})
    else:
        details = {"PageMode":"","NonFullScreenPageMode":nfsm}
        if expected_mode:
            details["expected"] = expected_mode
            if eapdf_conf:
                details["profile"] = eapdf_conf
        context.report.fail("PAGEMODE_PRESENT","PageMode missing",details)
    if vp is not None:
        if nfsm:
            context.report.ok("NONFULLSCREENPAGEMODE_PRESENT","ViewerPreferences NonFullScreenPageMode present",{"NonFullScreenPageMode":nfsm})
        else:
            details = {"NonFullScreenPageMode":""}
            if expected_mode:
                details["expected"] = expected_mode
                if eapdf_conf:
                    details["profile"] = eapdf_conf
            context.report.fail("NONFULLSCREENPAGEMODE_PRESENT","ViewerPreferences NonFullScreenPageMode missing",details)


def check_fonts(context, *, fonts_for):
    fonts=fonts_for(context.pdf)
    if not fonts:
        context.report.warn("FONTS_NONE","No fonts found in page resources",{"count":len(fonts),"fonts":fonts})
        return
    if all(context.report.delegates(code) for code in ("FONTS_EMBEDDED","FONTS_MISSING","FONTS_ALL_EMBEDDED")):
        return
    embedded=[f for f in fonts if f["embedded"]]
    missing=[f for f in fonts if not f["embedded"]]
    if embedded:
        context.report.ok("FONTS_EMBEDDED","Embedded fonts found",{"count":len(embedded),"fonts":embedded})
    if missing:
        context.report.fail("FONTS_MISSING","Some fonts are not embedded",{"count":len(missing),"fonts":missing[:25]})
    else:
        context.report.ok("FONTS_ALL_EMBEDDED","All discovered fonts are embedded",{"count":len(fonts),"fonts":fonts})
