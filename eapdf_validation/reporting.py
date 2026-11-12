"""Validation report presentation, independent of PDF parsing and external engines.

The root validator supplies live callbacks at each call so its public imports and
monkey-patched reporting functions retain their existing behavior.
"""
from dataclasses import dataclass
from typing import Callable, Mapping
import json


@dataclass(frozen=True)
class HtmlContext:
    rule_text: Mapping
    scope: str
    default_pdfa: Callable
    combined_status: Callable
    format_details: Callable
    escape: Callable
    spec_references: Callable
    specification_context: Callable


def combined_validation_status(summary, pdfa):
    if summary.get("status") == "FAIL" or (pdfa or {}).get("status") == "FAIL":
        return "FAIL"
    if summary.get("status") != "PASS" or (pdfa or {}).get("status") != "PASS":
        return "INCOMPLETE"
    if not pdfa.get("ran") or pdfa.get("compliant") is not True or pdfa.get("profile_allowed") is not True:
        return "INCOMPLETE"
    return "PASS"


def batch_validation_status(results):
    statuses=[result["summary"]["status"] for result in results]
    if "FAIL" in statuses:
        return "FAIL"
    return "PASS" if statuses and all(status=="PASS" for status in statuses) else "INCOMPLETE"


def format_details(details):
    if details is None:
        return ""
    try:
        return json.dumps(details, ensure_ascii=False, indent=2, sort_keys=True)
    except Exception:
        return str(details)


def render_html(report, pdfa=None, *, context):
    summary=report.summary()
    summary["status"]=context.combined_status(summary, pdfa)
    failures=[item for item in report.items if item["level"]=="FAIL"]
    warns=[item for item in report.items if item["level"]=="WARN"]
    passes=[item for item in report.items if item["level"]=="PASS"]
    nas=[item for item in report.items if item["level"]=="N/A"]

    def card(item):
        refs=context.spec_references(item["code"])
        spec_html=""
        if refs:
            blocks=[]
            for ref in refs:
                blocks.append(
                    "<div class='spec-block'>"
                    f"<div class='spec-meta'><strong>{context.escape(ref['spec'])}</strong> · {context.escape(ref['source'])}</div>"
                    "<p><strong>Reviewed summary — paraphrase, not a quotation:</strong></p>"
                    f"<p>{context.escape(ref['excerpt'])}</p>"
                    f"{context.specification_context(context.rule_text.get(item['code'], {}))}"
                    "</div>"
                )
            spec_html="".join(blocks)
        else:
            spec_html="<div class='spec-block'><div class='spec-meta'><strong>No mapped spec excerpt</strong></div><blockquote>This finding is currently treated as an operational diagnostic rather than a requirement cross-reference.</blockquote></div>"
        details=context.format_details(item["details"])
        details_html=f"<pre>{context.escape(details)}</pre>" if details else ""
        return (
            f"<section class='item {item['level'].lower()}'>"
            f"<div class='item-head'><span class='badge {item['level'].lower()}'>{item['level']}</span> "
            f"<code>{context.escape(item['code'])}</code></div>"
            "<h3>Finding</h3>"
            f"<p class='message'>{context.escape(item['message'])}</p>"
            f"{details_html}"
            "<h3>Specification</h3>"
            f"{spec_html}"
            "</section>"
        )

    pdfa = pdfa or context.default_pdfa(summary["file"])
    pdfa_badge = "pass" if pdfa["status"]=="PASS" else ("warn" if pdfa["status"]=="SKIP" else "fail")
    pdfa_html = (
        "<section class='section'>"
        "<h2>PDF/A First Pass (veraPDF)</h2>"
        f"<p><strong>Status:</strong> {context.escape(pdfa['status'])}</p>"
        f"<p><strong>Message:</strong> {context.escape(pdfa.get('message',''))}</p>"
        f"<p><strong>Profile:</strong> {context.escape(pdfa.get('profile_name','') or 'none')}</p>"
        f"<p><strong>Checks:</strong> passed={context.escape(str(pdfa.get('passed_checks',0)))} failed={context.escape(str(pdfa.get('failed_checks',0)))}</p>"
        "</section>"
    )
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>EA-PDF Validation Report</title>
  <style>
    :root {{
      --bg: #f4f0e8;
      --panel: #fffdf9;
      --text: #1f1a17;
      --muted: #6a5d53;
      --border: #d9cbbc;
      --fail: #8b1e1e;
      --warn: #9a6b00;
      --pass: #1f6a3a;
      --na: #5a6782;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: Georgia, "Times New Roman", serif;
      background:
        radial-gradient(circle at top left, #efe4d2 0, transparent 28%),
        linear-gradient(180deg, #f8f5ef 0%, var(--bg) 100%);
      color: var(--text);
    }}
    main {{ max-width: 1100px; margin: 0 auto; padding: 32px 20px 48px; }}
    .hero, .section, .item {{
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 16px;
      box-shadow: 0 10px 30px rgba(54, 33, 12, 0.06);
    }}
    .hero {{ padding: 24px; margin-bottom: 20px; }}
    .hero h1 {{ margin: 0 0 8px; font-size: 2rem; }}
    .hero p {{ margin: 6px 0; color: var(--muted); }}
    .status-line {{ display: flex; gap: 12px; flex-wrap: wrap; margin-top: 16px; }}
    .pill {{
      display: inline-block; padding: 8px 12px; border-radius: 999px;
      border: 1px solid var(--border); background: #f8f1e7; font-size: 0.95rem;
    }}
    .pill.fail {{ color: var(--fail); border-color: #e3b5b5; background: #fff1f1; }}
    .pill.warn {{ color: var(--warn); border-color: #ead6a1; background: #fff8df; }}
    .pill.pass {{ color: var(--pass); border-color: #bcd7c6; background: #effaf2; }}
    .pill.na {{ color: var(--na); border-color: #c8d1e6; background: #f2f5fb; }}
    .section {{ padding: 20px; margin-top: 20px; }}
    .section h2 {{ margin: 0 0 14px; font-size: 1.25rem; }}
    .item {{ padding: 18px; margin-top: 16px; }}
    .item-head {{ display: flex; gap: 10px; align-items: center; }}
    .badge {{
      display: inline-block; min-width: 56px; text-align: center; font-weight: 700;
      padding: 4px 8px; border-radius: 999px; color: white; font-size: 0.8rem;
    }}
    .badge.fail {{ background: var(--fail); }}
    .badge.warn {{ background: var(--warn); }}
    .badge.pass {{ background: var(--pass); }}
    .badge.na {{ background: var(--na); }}
    .message {{ margin: 12px 0; font-size: 1.02rem; }}
    pre {{
      background: #f7f3ed; border: 1px solid var(--border); border-radius: 12px;
      padding: 12px; overflow-x: auto; white-space: pre-wrap; word-break: break-word;
      font-family: "SFMono-Regular", Consolas, monospace; font-size: 0.9rem;
    }}
    .spec-block {{
      margin-top: 12px; padding: 12px 14px; border-left: 4px solid #b58d5a;
      background: #fcf7ef; border-radius: 10px;
    }}
    .spec-meta {{ color: var(--muted); font-size: 0.9rem; margin-bottom: 8px; }}
    blockquote {{ margin: 0; font-style: italic; color: #3b3027; }}
    .empty {{ color: var(--muted); margin: 0; }}
    @media (max-width: 700px) {{
      .hero h1 {{ font-size: 1.6rem; }}
    }}
  </style>
</head>
<body>
  <main>
    <section class="hero">
      <h1>EA-PDF Validation Report</h1>
      <p><strong>File:</strong> {context.escape(summary["file"])}</p>
      <p><strong>Status:</strong> {context.escape(summary["status"])}</p>
      <p><strong>Scope:</strong> {context.escape(context.scope)}</p>
      <div class="status-line">
        <span class="pill {'fail' if summary['fail'] else 'pass'}">Failures: {summary['fail']}</span>
        <span class="pill {'warn' if summary['warn'] else 'pass'}">Warnings: {summary['warn']}</span>
        <span class="pill pass">Passes: {summary['pass']}</span>
        <span class="pill na">N/A: {summary['na']}</span>
      </div>
      <div class="status-line">
        <span class="pill {'fail' if summary['rule_fail'] else 'pass'}">Rule Fail: {summary['rule_fail']}/{summary['rule_total']}</span>
        <span class="pill {'warn' if summary['rule_warn'] else 'pass'}">Rule Warn: {summary['rule_warn']}/{summary['rule_total']}</span>
        <span class="pill pass">Rule Pass: {summary['rule_pass']}/{summary['rule_total']}</span>
        <span class="pill na">Rule N/A: {summary['rule_na']}/{summary['rule_total']}</span>
      </div>
      <div class="status-line">
        <span class="pill {pdfa_badge}">PDF/A First Pass: {context.escape(pdfa['status'])}</span>
        <span class="pill">Profile: {context.escape(pdfa.get('profile_name','') or 'none')}</span>
      </div>
    </section>

    {pdfa_html}

    <section class="section">
      <h2>Failures</h2>
      {''.join(card(item) for item in failures) if failures else "<p class='empty'>No failures.</p>"}
    </section>

    <section class="section">
      <h2>Warnings</h2>
      {''.join(card(item) for item in warns) if warns else "<p class='empty'>No warnings.</p>"}
    </section>

    <section class="section">
      <h2>Passing Checks</h2>
      {''.join(card(item) for item in passes) if passes else "<p class='empty'>No passing checks recorded.</p>"}
    </section>

    <section class="section">
      <h2>Not Applicable</h2>
      {''.join(card(item) for item in nas) if nas else "<p class='empty'>No N/A checks recorded.</p>"}
    </section>
  </main>
</body>
</html>"""


def write_html_report(report, pdf_path, html_path=None, pdfa=None, *, derive_path, render):
    out=derive_path(pdf_path, html_path)
    out.write_text(render(report, pdfa), encoding="utf-8")
    return out
