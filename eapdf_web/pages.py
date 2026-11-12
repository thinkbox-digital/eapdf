"""Web page templates; requests, authentication and execution stay in the app."""
from dataclasses import dataclass
from typing import Callable
import html


@dataclass(frozen=True)
class ValidationPageContext:
    version: str
    retention_seconds: int
    url: Callable
    summary: Callable
    pdfa_table: Callable
    comparison_table: Callable


@dataclass(frozen=True)
class GeneratorPageContext:
    version: str
    retention_seconds: int
    url: Callable
    output_directories: Callable
    generation_result: Callable


def render_generate_page(local_result=None, local_error=None, *, context):
    local_error_html = f"<div class='error'>{html.escape(local_error)}</div>" if local_error else ""
    local_result_html = context.generation_result(local_result)
    output_buttons = "".join(
        f"<button type='button' class='dir-choice{' active' if path == 'out' else ''}' data-dir='{html.escape(path)}'>{html.escape(path)}</button>"
        for path in context.output_directories()
    )
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>EA-PDF Generator {html.escape(context.version)}</title>
  <style>
    :root {{
      --bg: #f4efe6;
      --panel: #fffdf8;
      --border: #d8c8b0;
      --text: #1f1d1a;
      --muted: #675b4f;
      --accent: #0f6b8f;
      --fail: #912018;
      --warn: #8a6300;
      --pass: #1f6f43;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: Georgia, "Times New Roman", serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(15,107,143,0.10), transparent 28rem),
        linear-gradient(180deg, #f8f4ec 0%, var(--bg) 100%);
    }}
    .page {{
      max-width: 1880px;
      margin: 0 auto;
      padding: 0.4rem 0.24rem 1rem;
      display: grid;
      gap: 0.75rem;
    }}
    .hero, .panel, .gen-result {{
      background: rgba(255,255,255,0.8);
      border: 1px solid var(--border);
      border-radius: 18px;
      padding: 0.75rem 0.9rem;
      box-shadow: 0 12px 34px rgba(82, 66, 47, 0.08);
      backdrop-filter: blur(8px);
    }}
    .hero {{
      position: relative;
    }}
    h1 {{
      margin: 0 0 0.12rem;
      font-size: clamp(1.05rem, 3vw, 2.45rem);
      line-height: 1.08;
    }}
    .page-link {{
      position: absolute;
      top: 0.8rem;
      right: 0.9rem;
      font-size: 0.76rem;
      font-weight: 700;
      color: var(--accent);
      text-decoration: none;
      letter-spacing: 0.04em;
    }}
    .lede {{
      margin: 0;
      color: var(--muted);
      max-width: 64rem;
    }}
    .panel h2 {{
      margin: 0 0 0.22rem;
      font-size: 1.15rem;
    }}
    .panel p {{
      margin: 0 0 0.6rem;
      color: var(--muted);
      line-height: 1.3;
    }}
    form {{
      display: grid;
      gap: 0.8rem;
    }}
    .field-grid {{
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.7rem;
    }}
    .field {{
      display: grid;
      gap: 0.18rem;
    }}
    label {{
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
      font-weight: 700;
    }}
    input[type=file], input[type=text], input[type=number], select {{
      width: 100%;
      border: 1px solid #ccb89e;
      border-radius: 10px;
      background: rgba(255,255,255,0.92);
      padding: 0.45rem 0.5rem;
      font: inherit;
      color: var(--text);
    }}
    .checkbox {{
      display: flex;
      align-items: center;
      gap: 0.45rem;
      font-size: 0.9rem;
      color: var(--text);
    }}
    .checkbox input {{
      margin: 0;
    }}
    .dropzone {{
      border: 2px dashed var(--accent);
      border-radius: 14px;
      padding: 0.7rem;
      background: #fcfffe;
    }}
    .section-title {{
      font-size: 0.74rem;
      text-transform: uppercase;
      letter-spacing: 0.1em;
      color: var(--muted);
      font-weight: 700;
      margin-bottom: 0.2rem;
    }}
    .picker-row {{
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.7rem;
      align-items: start;
    }}
    .picker-card {{
      border: 1px solid #e1d5c5;
      border-radius: 14px;
      background: rgba(255,255,255,0.78);
      padding: 0.7rem;
      display: grid;
      grid-auto-rows: max-content;
      gap: 0.35rem;
      align-content: start;
      align-self: start;
      height: auto;
      min-height: 0;
    }}
    .picker-button {{
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 0;
      border-radius: 999px;
      background: var(--accent);
      color: white;
      padding: 0.62rem 1rem;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
      text-decoration: none;
      width: fit-content;
      max-width: 100%;
      min-height: 0;
      height: auto;
      white-space: nowrap;
      align-self: start;
      flex: 0 0 auto;
    }}
    .picker-card > .picker-button {{
      align-self: start;
      justify-self: start;
    }}
    .hidden-file-input {{
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      border: 0;
    }}
    .picker-status {{
      font-size: 0.82rem;
      color: var(--muted);
      line-height: 1.25;
      min-height: 2.4em;
    }}
    .dir-picker-value {{
      font-size: 0.84rem;
      color: var(--text);
      font-weight: 700;
      word-break: break-word;
    }}
    .dir-picker-panel {{
      display: none;
      position: absolute;
      top: calc(100% + 0.45rem);
      left: 0;
      right: 0;
      z-index: 10;
      border: 1px solid #e1d5c5;
      border-radius: 12px;
      background: rgba(255,255,255,0.9);
      padding: 0.55rem;
      gap: 0.45rem;
      box-shadow: 0 16px 28px rgba(82, 66, 47, 0.12);
    }}
    .dir-picker-panel.open {{
      display: grid;
    }}
    .dir-choice-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 0.45rem;
    }}
    .dir-choice {{
      border: 1px solid #ccb89e;
      border-radius: 10px;
      background: rgba(255,255,255,0.96);
      color: var(--text);
      padding: 0.42rem 0.52rem;
      font: inherit;
      font-size: 0.82rem;
      text-align: left;
      cursor: pointer;
      width: 100%;
    }}
    .dir-choice.active {{
      border-color: var(--accent);
      box-shadow: inset 0 0 0 1px var(--accent);
      background: #eef7fb;
    }}
    .output-picker-card {{
      position: relative;
    }}
    .generate-actions {{
      display: flex;
      justify-content: flex-start;
    }}
    .hint {{
      margin: 0.1rem 0 0;
      color: var(--muted);
      font-size: 0.84rem;
    }}
    .action-button {{
      justify-self: start;
      border: 0;
      border-radius: 999px;
      background: var(--accent);
      color: white;
      padding: 0.62rem 1rem;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
    }}
    .error {{
      margin-top: 0.2rem;
      background: #fff2f0;
      color: var(--fail);
      border: 1px solid #efc1bb;
      border-radius: 12px;
      padding: 0.65rem 0.8rem;
    }}
    .gen-result {{
      display: grid;
      gap: 0.55rem;
    }}
    .gen-result-head {{
      display: flex;
      flex-wrap: wrap;
      gap: 0.55rem;
      align-items: center;
      font-size: 0.92rem;
      font-weight: 700;
    }}
    .summary-status.fail {{ color: var(--fail); font-weight: 700; }}
    .summary-status.pass {{ color: var(--pass); font-weight: 700; }}
    .summary-status.warn, .summary-status.incomplete {{ color: var(--warn); font-weight: 700; }}
    .gen-command {{
      font-size: 0.82rem;
      color: var(--muted);
      word-break: break-word;
    }}
    .generated-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 0.55rem;
    }}
    .generated-card {{
      border: 1px solid #e1d5c5;
      border-radius: 12px;
      background: rgba(255,255,255,0.86);
      padding: 0.55rem 0.65rem;
      display: grid;
      gap: 0.25rem;
    }}
    .generated-name a {{
      color: var(--text);
      text-decoration: none;
      font-weight: 700;
    }}
    .generated-meta {{
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
      font-weight: 700;
    }}
    .generated-line {{
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem 0.5rem;
      font-size: 0.8rem;
    }}
    .gen-log-grid {{
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.65rem;
    }}
    .detail-block {{
      border: 1px solid #e6dac9;
      border-radius: 8px;
      background: #fdfaf5;
      overflow: hidden;
    }}
    .detail-block summary {{
      cursor: pointer;
      list-style: none;
      padding: 0.26rem 0.38rem;
      background: #f6eee1;
      font-weight: 700;
      font-size: 0.72rem;
    }}
    .detail-resizer {{
      max-height: 22rem;
      overflow: auto;
    }}
    pre, code {{
      font-family: "SFMono-Regular", Consolas, monospace;
    }}
    pre {{
      margin: 0;
      padding: 0.5rem 0.55rem;
      white-space: pre-wrap;
      word-break: break-word;
      font-size: 0.74rem;
      line-height: 1.2;
    }}
    .gen-note {{
      color: var(--muted);
      font-size: 0.84rem;
    }}
    .version-chip {{
      display: inline-flex;
      align-items: center;
      padding: 0.16rem 0.5rem;
      border: 1px solid var(--border);
      border-radius: 999px;
      background: rgba(15,107,143,0.08);
      color: var(--muted);
      font-size: 0.8rem;
      margin-left: 0.4rem;
      vertical-align: middle;
    }}
    @media (max-width: 980px) {{
      .gen-log-grid, .field-grid, .picker-row {{
        grid-template-columns: 1fr;
      }}
    }}
  </style>
</head>
<body>
  <main class="page">
    <section class="hero">
      <a class="page-link" href="{html.escape(context.url('index'))}">Open Validator</a>
      <h1>EA-PDF Generator <span class="version-chip">Version {html.escape(context.version)}</span></h1>
      <p class="lede">Generate EA-PDF files from .eml, .msg, .pst, or .mbox files using the current tracked release.</p>
      <p class="hint">Web results expire after {context.retention_seconds / 3600:g} hours. Download files you want to keep.</p>
    </section>

    <section class="panel">
      <h2>Local Generation</h2>
      <p>Use file selection for specific items or choose a whole folder. Archive profiles combine the selected messages into one output PDF.</p>
      <form method="post" action="{html.escape(context.url('generate_local_route'))}" enctype="multipart/form-data">
        <div>
          <div class="section-title">Convert</div>
          <div class="picker-row">
            <div class="picker-card">
              <label for="inputs" class="picker-button">Convert File</label>
              <input id="inputs" class="hidden-file-input" type="file" name="inputs" accept=".eml,.msg,.pst,.mbox,message/rfc822,application/vnd.ms-outlook" multiple>
              <div class="picker-status" id="inputs-status">No files selected.</div>
            </div>
            <div class="picker-card">
              <label for="input_folder" class="picker-button">Convert Directory</label>
              <input id="input_folder" class="hidden-file-input" type="file" name="input_folder" webkitdirectory directory multiple>
              <div class="picker-status" id="folder-status">No directory selected.</div>
            </div>
            <div class="picker-card output-picker-card">
              <input id="output_dir" name="output_dir" type="hidden" value="out">
              <button type="button" class="picker-button" id="output_dir_button">Output Directory</button>
              <div class="dir-picker-value" id="output_dir_value">out</div>
              <div class="dir-picker-panel" id="dir_picker_panel">
                <div class="dir-choice-grid">
                  {output_buttons}
                </div>
              </div>
              <div class="hint">Project folders only. Default is <code>out</code>.</div>
            </div>
          </div>
        </div>
        <div>
          <div class="section-title">Profile</div>
          <div class="field-grid">
            <div class="field">
              <label for="profile">EA-PDF Profile</label>
              <select id="profile" name="profile">
                <option value="1s">1s</option>
                <option value="1si">1si</option>
                <option value="1m">1m</option>
                <option value="1mi">1mi</option>
                <option value="1c">1c</option>
                <option value="1ci">1ci</option>
              </select>
            </div>
            <div class="field">
              <label for="archive_output">Archive Output Name</label>
              <input id="archive_output" name="archive_output" type="text" placeholder="Leave blank to derive from input name">
            </div>
            <div class="field">
              <label>&nbsp;</label>
              <div class="hint">Use <code>1s</code> or <code>1si</code> for single-message output. Use <code>1m</code> or <code>1mi</code> to combine selected messages into one multi-message archive PDF. Use <code>1c</code> or <code>1ci</code> to create a collection container with child EA-PDF files.</div>
            </div>
          </div>
        </div>
        <div>
          <div class="section-title">Generate</div>
          <div class="generate-actions">
            <button type="submit" class="action-button">Generate EA-PDF</button>
          </div>
        </div>
      </form>
      {local_error_html}
    </section>

    {local_result_html}
  </main>
  <script>
    (function () {{
      const inputs = document.getElementById('inputs');
      const folder = document.getElementById('input_folder');
      const inputsStatus = document.getElementById('inputs-status');
      const folderStatus = document.getElementById('folder-status');
      const profileSelect = document.getElementById('profile');
      const singleMessageProfiles = ['1s', '1si'];
      const defaultArchiveProfile = '1m';
      function selectedFilesContainPst() {{
        const fileLists = [];
        if (inputs && inputs.files) fileLists.push(Array.from(inputs.files));
        if (folder && folder.files) fileLists.push(Array.from(folder.files));
        return fileLists.some(function (files) {{
          return files.some(function (file) {{
            return (file.name || '').toLowerCase().endsWith('.pst');
          }});
        }});
      }}
      function updateProfileAvailability() {{
        if (!profileSelect) {{
          return;
        }}
        const pstSelected = selectedFilesContainPst();
        Array.from(profileSelect.options).forEach(function (option) {{
          if (!singleMessageProfiles.includes(option.value)) {{
            return;
          }}
          option.disabled = pstSelected;
        }});
        if (pstSelected && singleMessageProfiles.includes(profileSelect.value)) {{
          profileSelect.value = defaultArchiveProfile;
        }}
      }}
      if (inputs && inputsStatus) {{
        inputs.addEventListener('change', function () {{
          const count = inputs.files ? inputs.files.length : 0;
          inputsStatus.textContent = count ? `${{count}} file${{count === 1 ? '' : 's'}} selected.` : 'No files selected.';
          updateProfileAvailability();
        }});
      }}
      if (folder && folderStatus) {{
        folder.addEventListener('change', function () {{
          const count = folder.files ? folder.files.length : 0;
          if (!count) {{
            folderStatus.textContent = 'No directory selected.';
            updateProfileAvailability();
            return;
          }}
          const first = folder.files[0];
          const rel = first.webkitRelativePath || '';
          const top = rel ? rel.split('/')[0] : 'Selected folder';
          folderStatus.textContent = `${{top}} selected with ${{count}} item${{count === 1 ? '' : 's'}}.`;
          updateProfileAvailability();
        }});
      }}
      const dirInput = document.getElementById('output_dir');
      const dirButton = document.getElementById('output_dir_button');
      const dirValue = document.getElementById('output_dir_value');
      const dirPanel = document.getElementById('dir_picker_panel');
      const dirChoices = Array.from(document.querySelectorAll('.dir-choice'));
      if (dirButton && dirPanel && dirInput && dirValue) {{
        dirButton.addEventListener('click', function () {{
          dirPanel.classList.toggle('open');
        }});
        dirChoices.forEach(function (choice) {{
          choice.addEventListener('click', function () {{
            const value = choice.dataset.dir || 'out';
            dirInput.value = value;
            dirValue.textContent = value;
            dirChoices.forEach(function (item) {{ item.classList.remove('active'); }});
            choice.classList.add('active');
            dirPanel.classList.remove('open');
          }});
        }});
      }}
      updateProfileAvailability();
    }})();
  </script>
</body>
</html>"""


def render_page(results=None, error=None, *, context):
    results = results or []
    results_html = ""
    if results:
        results_html = context.summary(results) + context.pdfa_table(results) + context.comparison_table(results)
    error_html = f"<div class='error'>{html.escape(error)}</div>" if error else ""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>EA-PDF Validator {html.escape(context.version)}</title>
  <style>
    :root {{
      --bg: #f4efe6;
      --panel: #fffdf8;
      --border: #d8c8b0;
      --text: #1f1d1a;
      --muted: #675b4f;
      --accent: #0f6b8f;
      --fail: #912018;
      --warn: #8a6300;
      --pass: #1f6f43;
      --na: #546275;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: Georgia, "Times New Roman", serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(15,107,143,0.10), transparent 28rem),
        linear-gradient(180deg, #f8f4ec 0%, var(--bg) 100%);
    }}
    .page {{
      max-width: 1940px;
      margin: 0 auto;
      padding: 0.35rem 0.2rem 1rem 0.08rem;
    }}
    .hero {{
      position: relative;
      background: rgba(255,255,255,0.72);
      border: 1px solid var(--border);
      border-radius: 20px;
      padding: 0.65rem 0.8rem;
      box-shadow: 0 12px 40px rgba(82, 66, 47, 0.08);
      backdrop-filter: blur(8px);
    }}
    h1 {{
      margin: 0 0 0.15rem;
      font-size: clamp(0.95rem, 3vw, 2.35rem);
      line-height: 1.05;
    }}
    .page-link {{
      position: absolute;
      top: 0.72rem;
      right: 0.85rem;
      font-size: 0.76rem;
      font-weight: 700;
      color: var(--accent);
      text-decoration: none;
      letter-spacing: 0.04em;
    }}
    .lede {{
      margin: 0 0 0.4rem;
      color: var(--muted);
      max-width: 58rem;
    }}
    form {{
      display: grid;
      gap: 0.45rem;
      margin-top: 0.35rem;
    }}
    .upload-box {{
      border: 2px dashed var(--accent);
      border-radius: 16px;
      padding: 0.55rem 0.7rem;
      background: #fcfffe;
    }}
    input[type=file] {{
      width: 100%;
      font: inherit;
    }}
    .hint {{
      margin: 0.12rem 0 0;
      color: var(--muted);
      font-size: 0.88rem;
    }}
    .action-button {{
      justify-self: start;
      border: 0;
      border-radius: 999px;
      background: var(--accent);
      color: white;
      padding: 0.6rem 0.95rem;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
    }}
    .error {{
      margin-top: 0.45rem;
      background: #fff2f0;
      color: var(--fail);
      border: 1px solid #efc1bb;
      border-radius: 12px;
      padding: 0.65rem 0.8rem;
    }}
    .results {{
      display: grid;
      gap: 0.7rem;
      margin-top: 0.45rem;
    }}
    .summary-strip {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 0.6rem;
    }}
    .summary-chip {{
      border: 1px solid var(--border);
      border-radius: 16px;
      background: rgba(255,255,255,0.8);
      padding: 0.65rem 0.8rem;
      box-shadow: 0 10px 28px rgba(82, 66, 47, 0.06);
    }}
    .summary-chip-label {{
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
    }}
    .summary-chip-name {{
      margin-top: 0.15rem;
      font-weight: 700;
      word-break: break-word;
      font-size: 0.92rem;
    }}
    .summary-chip-meta {{
      margin-top: 0.14rem;
      font-size: 0.72rem;
      color: var(--muted);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }}
    .summary-chip-line {{
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem 0.55rem;
      margin-top: 0.35rem;
      font-size: 0.82rem;
    }}
    .summary-line-label {{
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
      font-weight: 700;
    }}
    .summary-chip-pdfa {{
      margin-top: 0.3rem;
      font-size: 0.78rem;
      color: var(--muted);
      line-height: 1.2;
    }}
    .summary-status.fail {{ color: var(--fail); font-weight: 700; }}
    .summary-status.pass {{ color: var(--pass); font-weight: 700; }}
    .summary-status.warn {{ color: var(--warn); font-weight: 700; }}
    .summary-status.na {{ color: var(--na); font-weight: 700; }}
    .summary-status.skip {{ color: var(--muted); font-weight: 700; }}
    .summary-status.error {{ color: var(--fail); font-weight: 700; }}
    .pdfa-shell {{
      margin-bottom: 0.6rem;
    }}
    .pdfa-title {{
      padding: 0.45rem 0.55rem 0.15rem;
      font-size: 0.9rem;
      font-weight: 700;
    }}
    .table-controls {{
      padding: 0.38rem 0.45rem 0;
      background: rgba(255,255,255,0.72);
    }}
    .coverage-toggle {{
      display: inline-flex;
      align-items: center;
      gap: 0.42rem;
      font-size: 0.78rem;
      color: var(--muted);
      font-weight: 700;
    }}
    .coverage-toggle input {{
      margin: 0;
    }}
    .table-shell {{
      border: 1px solid var(--border);
      border-radius: 14px;
      background: rgba(255,255,255,0.84);
      box-shadow: 0 12px 34px rgba(82, 66, 47, 0.08);
      overflow: visible;
    }}
    .pdfa-row-label,
    .pdfa-cell,
    .pdfa-head,
    .pdfa-corner {{
      padding: 0.35rem 0.45rem !important;
      font-size: 0.78rem;
      line-height: 1.2;
      word-break: break-word;
      white-space: normal;
      background: rgba(255,255,255,0.92);
    }}
    .pdfa-detail-cell {{
      vertical-align: top;
    }}
    .pdfa-detail-note {{
      padding: 0.35rem 0.45rem;
      font-size: 0.76rem;
      line-height: 1.25;
      color: var(--muted);
    }}
    .pdfa-detail-block {{
      margin: 0.28rem 0.35rem;
    }}
    .pdfa-detail-resizer {{
      min-height: 2.6rem;
      max-height: 18rem;
    }}
    .pdfa-rule-summary {{
      padding: 0.35rem 0.42rem;
      border-bottom: 1px dashed #d8c8b0;
      font-size: 0.74rem;
      line-height: 1.24;
    }}
    .pdfa-rule-summary:last-child {{
      border-bottom: 0;
    }}
    .pdfa-rule-head {{
      font-weight: 700;
      margin-bottom: 0.12rem;
    }}
    .pdfa-rule-desc {{
      margin-bottom: 0.16rem;
    }}
    .pdfa-rule-meta {{
      color: var(--muted);
      margin-bottom: 0.16rem;
      font-size: 0.7rem;
    }}
    .pdfa-check-list {{
      margin: 0;
      padding-left: 1rem;
    }}
    .pdfa-check-list li {{
      margin: 0 0 0.2rem;
    }}
    .pdfa-check-message {{
      font-size: 0.72rem;
    }}
    .pdfa-check-context {{
      color: var(--muted);
      font-size: 0.68rem;
      font-family: "SFMono-Regular", Consolas, monospace;
      word-break: break-word;
    }}
    .pdfa-more-checks {{
      margin-top: 0.18rem;
      color: var(--muted);
      font-size: 0.68rem;
    }}
    .pdfa-row-label {{
      width: 11rem;
      background: #faf5ec;
      font-weight: 700;
    }}
    .pdfa-corner {{
      background: linear-gradient(180deg, #f8f1e3 0%, #efe3ce 100%);
      width: 11rem;
    }}
    .compare-table {{
      width: 100%;
      border-collapse: collapse;
      table-layout: fixed;
    }}
    .compare-table th,
    .compare-table td {{
      padding: 0;
      border: 1px solid #e1d5c5;
      vertical-align: top;
      text-align: left;
      background: rgba(255,255,255,0.92);
    }}
    .compare-table thead th {{
      position: sticky;
      top: 0;
      z-index: 3;
      background: linear-gradient(180deg, #f8f1e3 0%, #efe3ce 100%);
      box-shadow: inset 0 -1px 0 #d9c7ae;
    }}
    .compare-table .file-head {{
      padding: 0.45rem 0.45rem;
      width: 33.333%;
    }}
    .file-head-label {{
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
    }}
    .file-head-name {{
      margin-top: 0.15rem;
      font-weight: 700;
      word-break: break-word;
      font-size: 0.82rem;
    }}
    .file-head-meta {{
      margin-top: 0.1rem;
      font-size: 0.66rem;
      color: var(--muted);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }}
    .file-filter-label {{
      display: grid;
      gap: 0.18rem;
      margin-top: 0.35rem;
      font-size: 0.68rem;
      color: var(--muted);
    }}
    .file-filter-label span {{
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }}
    .file-filter {{
      width: 100%;
      border: 1px solid #ccb89e;
      border-radius: 8px;
      background: rgba(255,255,255,0.92);
      padding: 0.18rem 0.28rem;
      font: inherit;
      font-size: 0.72rem;
      color: var(--text);
    }}
    .rule-title-col {{
      background: #faf5ec;
      padding: 0;
    }}
    .rule-title-cell {{
      padding: 0.32rem 0.4rem 0.3rem;
    }}
    .rule-title-line {{
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.35rem;
    }}
    .requirement-index {{
      font-size: 0.68rem;
      font-weight: 700;
      color: var(--muted);
      margin-bottom: 0.12rem;
    }}
    .rule-badge {{
      display: inline-block;
      border: 1px solid #ccb89e;
      border-radius: 999px;
      background: #f6eee1;
      color: #7a684f;
      font-size: 0.58rem;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      padding: 0.08rem 0.28rem;
    }}
    .file-col {{
      width: 33.333%;
    }}
    .file-cell {{
      display: grid;
      gap: 0.28rem;
      padding: 0.35rem 0.35rem;
    }}
    .status {{
      display: inline-block;
      font-weight: 700;
      white-space: nowrap;
      padding: 0.12rem 0.34rem;
      border-radius: 999px;
      font-size: 0.72rem;
      border: 1px solid currentColor;
    }}
    .status.fail {{ color: var(--fail); background: #fff2f0; }}
    .status.warn {{ color: var(--warn); background: #fff8de; }}
    .status.pass {{ color: var(--pass); background: #edf9f1; }}
    .status.n-a {{ color: var(--na); background: #eef2f7; }}
    .reason {{
      font-size: 0.74rem;
      line-height: 1.2;
    }}
    .detail-block {{
      border: 1px solid #e6dac9;
      border-radius: 8px;
      background: #fdfaf5;
      overflow: hidden;
    }}
    .detail-block summary {{
      cursor: pointer;
      list-style: none;
      padding: 0.22rem 0.34rem;
      background: #f6eee1;
      font-weight: 700;
      font-size: 0.68rem;
    }}
    .detail-block summary::-webkit-details-marker {{
      display: none;
    }}
    .detail-resizer {{
      resize: vertical;
      overflow: auto;
      min-height: 3.6rem;
      max-height: 24rem;
    }}
    .detail-group + .detail-group {{
      border-top: 1px dashed #d8c8b0;
    }}
    .detail-label {{
      padding: 0.26rem 0.34rem 0.08rem;
      font-size: 0.58rem;
      font-weight: 700;
      letter-spacing: 0.12em;
      color: #7b6a56;
      text-transform: uppercase;
    }}
    .detail-rows {{
      padding: 0 0.34rem 0.22rem;
      display: grid;
      gap: 0.14rem;
    }}
    .detail-row {{
      display: block;
      border: 1px solid #eadfce;
      border-radius: 8px;
      background: rgba(255,255,255,0.45);
      padding: 0.12rem 0.24rem 0.14rem;
    }}
    .detail-inline {{
      color: #1f1d1a;
      word-break: break-word;
      white-space: pre-wrap;
      font-family: "SFMono-Regular", Consolas, monospace;
      font-size: 0.72rem;
      line-height: 1.08;
    }}
    .detail-key-inline {{
      color: #806f5b;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }}
    .detail-value-inline {{
      font-weight: 400;
    }}
    .detail-empty {{
      min-height: 0.4rem;
    }}
    pre {{
      margin: 0;
      padding: 0.4rem 0.45rem;
      white-space: pre-wrap;
      word-break: break-word;
      font-size: 0.7rem;
      font-family: "SFMono-Regular", Consolas, monospace;
      min-height: 4.25rem;
      height: 100%;
    }}
    code {{
      font-size: 0.72rem;
      font-family: "SFMono-Regular", Consolas, monospace;
      word-break: break-word;
    }}
    .spec-ref {{
      padding: 0.3rem 0.34rem;
    }}
    .spec-ref + .spec-ref {{
      border-top: 1px dashed #d8c8b0;
    }}
    .spec-meta {{
      font-size: 0.64rem;
      color: var(--muted);
      margin-bottom: 0.14rem;
    }}
    .spec-excerpt {{
      font-size: 0.7rem;
      line-height: 1.12;
    }}
    .spec-note {{
      margin-top: 0.32rem;
      padding-top: 0.26rem;
      border-top: 1px dashed #d8c8b0;
      font-size: 0.68rem;
      line-height: 1.12;
      color: #5f564b;
    }}
    .spec-summary-source {{
      margin-top: 0.45rem;
      padding-top: 0.38rem;
      border-top: 1px dashed #d8c8b0;
      font-size: 0.72rem;
      line-height: 1.18;
      color: #6a6156;
    }}
    .spec-label {{
      margin-bottom: 0.12rem;
      font-size: 0.58rem;
      font-weight: 700;
      letter-spacing: 0.12em;
      text-transform: uppercase;
      color: #7b6a56;
    }}
    .version-chip {{
      display: inline-flex;
      align-items: center;
      padding: 0.16rem 0.5rem;
      border: 1px solid var(--border);
      border-radius: 999px;
      background: rgba(15,107,143,0.08);
      color: var(--muted);
      font-size: 0.8rem;
      margin-left: 0.4rem;
      vertical-align: middle;
    }}
    @media (max-width: 960px) {{
      .page {{
        padding-inline: 0.08rem;
      }}
    }}
  </style>
</head>
<body>
  <main class="page">
    <section class="hero">
      <a class="page-link" href="{html.escape(context.url('generate_index'))}">Open Generator</a>
      <h1>EA-PDF Validation Workbench <span class="version-chip">Version {html.escape(context.version)}</span></h1>
      <p class="lede">Upload up to three PDF files and validate each one with a two-pass process: veraPDF first for PDF/A conformance, then EA-PDF-specific rules.</p>
      <p class="hint">Web results expire after {context.retention_seconds / 3600:g} hours. Reports can contain private email information.</p>
      <form method="post" action="{html.escape(context.url('validate_route'))}" enctype="multipart/form-data">
        <div class="upload-box">
          <input type="file" name="pdfs" accept="application/pdf,.pdf" multiple required>
          <p class="hint">Choose 1 to 3 PDF files. Each file gets its own full rule table. The filename banner row stays pinned while you scroll.</p>
        </div>
        <button type="submit">Validate PDFs</button>
      </form>
      {error_html}
    </section>
    <section class="results">{results_html}</section>
  </main>
  <script>
    (function () {{
      const filters = Array.from(document.querySelectorAll('.file-filter'));
      const rows = Array.from(document.querySelectorAll('.rule-row'));
      const showPdfaCovered = document.getElementById('show-pdfa-covered');
      if (!filters.length || !rows.length) return;

      function applyFilters() {{
        const activeFilters = filters.filter((filter) => filter.value !== 'ALL');
        const activeIndex = activeFilters.length ? activeFilters[0].dataset.fileIndex : null;
        const includePdfaCovered = showPdfaCovered ? showPdfaCovered.checked : false;

        filters.forEach((filter) => {{
          if (!activeIndex || filter.dataset.fileIndex === activeIndex) {{
            filter.disabled = false;
          }} else {{
            filter.disabled = true;
          }}
        }});

        rows.forEach((row) => {{
          let visible = true;
          if (!includePdfaCovered && row.dataset.pdfaCovered === 'true') {{
            visible = false;
          }}
          for (const filter of filters) {{
            if (filter.disabled) continue;
            const selected = filter.value;
            if (selected === 'ALL') continue;
            const idx = filter.dataset.fileIndex;
            const actual = row.dataset['file' + idx + 'Level'];
            if (actual !== selected) {{
              visible = false;
              break;
            }}
          }}
          row.style.display = visible ? '' : 'none';
          const titleRow = document.querySelector(`.rule-title-row[data-rule-code="${{row.dataset.ruleCode}}"]`);
          if (titleRow) titleRow.style.display = visible ? '' : 'none';
        }});
      }}

      filters.forEach((filter) => filter.addEventListener('change', applyFilters));
      if (showPdfaCovered) showPdfaCovered.addEventListener('change', applyFilters);
      applyFilters();
    }})();
  </script>
</body>
</html>
"""
