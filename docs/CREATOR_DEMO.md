# Creator proof of concept

Current examples are organized by input format in [the example index](../examples/README.md). Each format has six profiles and a validation manifest; the dated checkpoint below describes an earlier set.

The creator has reached a viable beta checkpoint for common email files on the tested Linux setup.

## Supported proof-of-concept setup

The current build is checked on Linux with Java 21 and `/usr/bin/prlimit`
(provided by util-linux). The desktop app also needs a graphical session.
Use the two runnable JARs in `dist/`; fonts, the color profile and notices are
bundled. The standalone Java validator includes its validation engines. Python is used
by the separate example-regeneration script, rather than ordinary email creation.

Rebuild from the project root without filling the project with working copies:

```bash
mvn -o -Deapdf.build.directory=/tmp/eapdf-build package
```

This offline command requires the pinned Maven dependencies to be cached.
On a first build, omit `-o` to let Maven retrieve them. The resulting CLI JAR
is `/tmp/eapdf-build/emailtopdfa-1.6.0-beta.7.jar`; the GUI JAR adds `-gui` before
`.jar`. `dist/creator-build.json` records the delivered files and checks.
Public redistribution still needs the branding and dependency distribution
review described in `docs/THIRD_PARTY_LICENSES.md`.

## Start the app

From the project folder:

```bash
java -jar dist/emailtopdfa-gui.jar
```

1. Open **Local Email**.
2. Choose an email file or folder. Browse starts with EML, MSG, PST and MBOX files; All Files is still available.
3. Choose an output folder.
4. Choose an output type. The explanation below the menu says what is kept. The default makes one PDF per email. Archives need at least two emails. Collections store separate PDFs inside one file and need a PDF reader with an attachments panel.
5. Click **Convert Local Input**.
6. When **Finished** appears, click **Open output folder**.

The Summary tab shows created files and warnings. Details keeps the full log. **Stop** ends the conversion and shows **Stopped**; you can then start again. Closing the window during conversion waits for it to stop. Existing output stays intact when a conversion is stopped. If conversion fails, the Summary and error dialog show the reason, including failures reported as Java exceptions. Details keeps the full log.

## Try the demo files

Start with `dist/demo/Weekly project review.pdf`. This two-page example combines a reply address, a logo, international text, a table, revised deadline, lettered steps and original attachments. Recreate it from `dist/demo/inputs/Weekly project review.eml`.

The same email and Agenda are also saved as `weekly-review-archive.pdf` and `weekly-review-collection.pdf` in `dist/demo/`. Their two-email input folder is `dist/demo/inputs/Weekly review collection/`. Attachment pages show their email’s subject and sent date. Finding aids and file lists remain plain.

| Example | Input | Saved result |
|---|---|---|
| Email with original text and CSV attachments | `dist/demo/inputs/Quarterly status.eml` | `dist/demo/Quarterly status.pdf` |
| Email with original PDF and image attachments | `dist/demo/inputs/Project handover.eml` | `dist/demo/Project handover.pdf` |
| Dates kept on one line in an email table | `dist/demo/inputs/Review dates.eml` | `dist/demo/Review dates.pdf` |
| Attached phone photo with saved rotation | `dist/demo/inputs/Phone photo.eml` | `dist/demo/Phone photo.pdf` |
| Readable supplied text when HTML needs external pictures | `dist/demo/inputs/Project approval.eml` | `dist/demo/Project approval.pdf` |
| Timezone offsets retained through a clock change | `dist/demo/inputs/Clock change check-ins/` | `dist/demo/check-in-collection.pdf` |
| Full names for attached Outlook emails | `dist/demo/inputs/Parent with forwarded email.msg` | `dist/demo/Forwarded email filename.pdf` |
| A colored signature divider aligned with the text | `dist/demo/inputs/Signature divider.eml` | `dist/demo/Signature divider.pdf` |
| Distinct long subjects in a plain collection index | `dist/demo/inputs/Long subject decisions/` | `dist/demo/long-subjects-collection.pdf` |
| The same long subjects in a combined archive | `dist/demo/inputs/Long subject decisions/` | `dist/demo/long-subjects-archive.pdf` |
| Column headings repeated across a long email table | `dist/demo/inputs/Weekly project table.eml` | `dist/demo/Weekly project table.pdf` |
| Top, middle and bottom alignment in email tables | `dist/demo/inputs/Table cell alignment.eml` | `dist/demo/Table cell alignment.pdf` |
| Email addresses shown once while real names are kept | `dist/demo/inputs/Review participants.eml` | `dist/demo/Review participants.pdf` |
| An everyday review email with a logo, table and attachments | `dist/demo/inputs/Weekly project review.eml` | `dist/demo/Weekly project review.pdf` |
| A section that begins below a floated image | `dist/demo/inputs/Text below image.eml` | `dist/demo/Text below image.pdf` |
| Space between a floated logo and email text | `dist/demo/inputs/Logo spacing.eml` | `dist/demo/Logo spacing.pdf` |
| A centered email background image | `dist/demo/inputs/Project banner.eml` | `dist/demo/Project banner.pdf` |
| Review steps numbered through shared CSS | `dist/demo/inputs/Numbered review.eml` | `dist/demo/Numbered review.pdf` |
| Underlined and crossed-out review text | `dist/demo/inputs/Review changes.eml` | `dist/demo/Review changes.pdf` |
| Deadline and status labels generated by CSS | `dist/demo/inputs/Project labels.eml` | `dist/demo/Project labels.pdf` |
| Readable styles beside an unsupported rule | `dist/demo/inputs/Shared email styles.eml` | `dist/demo/Shared email styles.pdf` |
| Paragraph alignment requested by CSS | `dist/demo/inputs/Paragraph alignment.eml` | `dist/demo/Paragraph alignment.pdf` |
| Visible text with a color override | `dist/demo/inputs/Text color.eml` | `dist/demo/Text color.pdf` |
| Email with a PowerPoint attachment | `in/Agenda and timeline slides.eml` | Generated locally; not distributed |
| HTML, table, inline image and international text | Local demonstration input; not distributed | Generated locally; not distributed |
| Three-page email with 44 numbered items | `dist/demo/inputs/Project review notes.eml` | `dist/demo/Long email.pdf` |
| Full attachment names, including a name that wraps | `dist/demo/inputs/Attachment review.eml` | `dist/demo/Attachment review.pdf` |
| A block image with text below | `dist/demo/inputs/Block image.eml` | `dist/demo/Block image.pdf` |
| A logo at the size requested by CSS | `dist/demo/inputs/Logo size.eml` | `dist/demo/Logo size.pdf` |
| A large heading and small reference line | `dist/demo/inputs/Text sizes.eml` | `dist/demo/Text sizes.pdf` |
| White text on a dark email background | `dist/demo/inputs/Colored email.eml` | `dist/demo/Colored email.pdf` |
| Continued steps and lettered sections | `dist/demo/inputs/Review steps.eml` | `dist/demo/Review steps.pdf` |
| A Times-style email letter | `dist/demo/inputs/Project review letter.eml` | `dist/demo/Serif letter.pdf` |
| Columns using a shared font rule | `dist/demo/inputs/Shared font report.eml` | `dist/demo/Shared font rules.pdf` |
| International text with a strong font rule | `dist/demo/inputs/Font rules.eml` | `dist/demo/Font rules.pdf` |
| HTML columns requesting a fixed-width font | `dist/demo/inputs/HTML columns.eml` | `dist/demo/HTML columns.pdf` |
| Columns separated by tabs | `dist/demo/inputs/Tabbed task list.eml` | `dist/demo/Tabbed task list.pdf` |
| Table cells with their intended gaps | `dist/demo/inputs/Project cards.eml` | `dist/demo/Project cards.pdf` |
| A complete long build-report path | `dist/demo/inputs/Build report.eml` | `dist/demo/Build report.pdf` |
| A supplied reply address | `dist/demo/inputs/Reply address.eml` | `dist/demo/Reply address.pdf` |
| Plain-text columns aligned with spaces | `dist/demo/inputs/Weekly task list.eml` | `dist/demo/Weekly task list.pdf` |
| A logo placed to the right of text | `dist/demo/inputs/Right aligned logo.eml` | `dist/demo/Right aligned logo.pdf` |
| A complete phone screenshot | `dist/demo/inputs/Phone screenshot.eml` | `dist/demo/Phone screenshot.pdf` |
| A centered table | `dist/demo/inputs/Centered table.eml` | `dist/demo/Centered table.pdf` |
| A full-width table with percentage columns | `dist/demo/inputs/Weekly review.eml` | `dist/demo/Weekly review.pdf` |
| A long document URL | `dist/demo/inputs/Project decisions.eml` | `dist/demo/Long URL.pdf` |
| An exported filename without spaces | `dist/demo/inputs/Project export.eml` | `dist/demo/Long filename.pdf` |
| Chinese subjects and source filenames | `dist/demo/inputs/International/` | `dist/demo/International email 1.pdf` and `International email 2.pdf` |
| Outlook message with an attachment | `in/HtmlSampleEmailWithAttachment.msg` | Generated locally; not distributed |
| A real Outlook reply thread | `in/2019.10.10a additional comments and questions for DEP.msg` | `dist/demo/Outlook thread.pdf` |
| Outlook RTF body and attachment | `in/RtfSampleEmailWithAttachment.msg` | `dist/demo/RTF email.pdf` |
| Small PST | `src/test/resources/eapdf/email/attached-message.pst` | Seven PDFs generated locally |
| One archive PDF | Local demonstration input; not distributed | Generated locally; not distributed |
| Folder collection | The same input folder | Generated locally; not distributed |

For the Agenda example, the **Body** and **Attachments** bookmarks identify its sections. The original EML is available in the document attachments panel. File lists show plain filenames, with one attachment per line.

In **Long email.pdf**, space is reserved below the email text for the full-width footer banner. All 44 numbered items remain readable. In **Attachment review.pdf**, the long name wraps onto two lines and the next attachment has its own row. The body-page file list also puts one attachment on each line.

Long URLs and filenames now wrap without losing characters. Generated output filenames are shortened when needed to fit the filesystem; full subjects and original source filenames remain inside the PDFs. Local per-email conversions add a numbered suffix when a filename is taken, so earlier PDFs remain. Repeating a conversion creates a new PDF too. Page numbers sit below the footer artwork. The app's Summary also names completed archive and collection PDFs.

`demo-collection.pdf` contains three emails under Inbox and Projects; `weekly-review-collection.pdf` contains two. Its finding aid is a plain table with no opening controls. The index wraps ordinary addresses in full and preserves dashes and curly quotes. The separate finding-aid PDF and spreadsheet are saved beside the collection.

Filename lists have no links. EA-PDF's required native attachment annotations are retained separately; the body-page records are in the top margin, outside the lists. Outputs that omit originals retain their existing empty-placeholder policy. A standalone finding-aid sidecar is an index; the email PDFs are embedded in the collection, not in that sidecar.

The real Outlook thread now uses three pages, with all message text kept and no extra page for the source caption. Quoted replies keep their appearance and pass the structure check. The existing MSG header-comparison limit still applies.

Tall inline images are reduced to fit a single printed page while keeping their proportions. Original retained files keep full resolution. The phone screenshot example shows its complete red bottom bar.

Plain-text emails and preformatted/code blocks use bundled fixed-width letters to retain space-aligned columns. Tabs advance to the next eight-column stop. Headers and ordinary HTML keep their existing fonts.

Supplied Reply-To headers are shown below From, including international display names. The creator does not add a Reply-To address when the source has none.

Emails that request unavailable fonts use bundled replacements so their text stays visible. Common serif requests keep a Times-style appearance. Supported inline and shared fixed-width font requests keep columns aligned, including print rules and font inheritance. Original emails keep their original font instructions.

Legacy email background, text and existing link colors are retained, including white text on dark backgrounds. Source CSS can override these defaults.

## Use the command line

```bash
java -jar dist/emailtopdfa.jar --out out "in/Agenda and timeline slides.eml"
java -jar dist/emailtopdfa.jar --out /tmp/eapdf-demo --archive-profile c "in/enron-sample.mbox"
```

CLI arguments are unchanged.

A ready-made selection of PDFs and an app screenshot is in `dist/demo/`. The Agenda PDF retains the exact original email and PowerPoint bytes. Embedded files use lossless compression.

## What this build adds

- Separate body and attachment sections, with bookmarks.
- Required native attachment records sharing one retained file.
- Lossless compression of embedded files.
- Bundled fallback font for common Chinese text, including the HTML demo.
- A conversion record with the tool, time, output type and email count.
- Plain completion messages and an Open output folder button.
- A full-width footer banner with space reserved above it for email content.
- Full attachment names, with wrapping and room for following rows.
- Clean Stop, restart and window-close behavior, with specific error messages.
- Plain finding-aid tables and filename lists, without opening controls.
- Readable index addresses and correct dashes and curly quotes in generated text.
- One attached file per line.
- Correct proportions for inline images with a supplied width.

`dist/creator-build.json` identifies the delivered JARs and source files.

## Current limits

- This is a proof of concept for common inputs. Damaged or unusual mail is outside this cycle.
- The existing validator cannot fully compare headers from retained MSG files. Such a result is reported as incomplete, even when the PDF/A and structure checks pass.
- PST single-email output retains exported messages and native attachments by default (`s`). Choose `si` to omit originals explicitly.
- The finding aid and filename lists are intentionally plain text. Native attachment handling remains the viewer's responsibility.
- C95 live Gmail checks pass for four authorized emails, including a PDF attachment, single/archive/collection output and resume. Private review PDFs are in local verification records; large-mailbox acceptance remains open.
- These outputs are unsigned. Full accessibility certification and signing are outside the main demo work.

The long-subject collection also has `long-subjects-collection_finding_aid.pdf` and `long-subjects-collection_index.xlsx` beside it. Its printed index retains both final decisions without links.

For PST input, the default per-email output retains exported messages and native attachments. The app describes the selected preservation policy beside the profile menu.

The forwarded-email filename example keeps the full 200-character attachment name and unchanged attached-message bytes. It passes PDF/A and selected ISO checks. Its EA-PDF result retains the existing MSG source-header comparison limit, with no mandatory failures.

Finding-aid dates include the numeric offset of the displayed normalized time. Email headers retain the supplied source date. `check-in-collection_finding_aid.pdf` and `check-in-collection_index.xlsx` show the same two clock-change offsets. The main archive/collection and long-subject companions are refreshed with these complete dates.

Remote pictures are not fetched. When selected HTML contains only external pictures and no text, with no available offline image, the creator uses a supplied plain-text alternative. Original email bytes remain intact. Embedded, cached and data images retain their normal rendering.

The offline text fallback retains HTML when source styles declare generated words, so existing styled labels remain visible. The Project approval example still demonstrates the supplied plain-text fallback.
