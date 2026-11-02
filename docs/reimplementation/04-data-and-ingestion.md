# Data model and ingestion algorithms

## Current logical records

`Models.java` is the current implementation contract; it is package-private and mutable, not a serialized public format.

| Record/group | Fields and meaning |
| --- | --- |
| `MailAgent` | Display `name` and unprefixed `email`; FOAF output adds `mailto:` |
| Source identity | `sourceType`, `sourcePath`, `sourceFilename`, `sourceMime`, `sourceBytes`; source-associated file may own bytes after staging |
| Mail identity | Optional original `messageId`; independent required `mailGuid` as `uuid:<random UUID>`; caller `archiveGlobalId` and `archiveEmails` |
| Headers | `subject`; display `from/to/cc/bcc`; original/fallback `rawFrom/rawTo/rawCc/rawBcc`; agent lists; `inReplyTo` and `references` |
| Time | `rawDateHeader`; display `date`; normalized offset `sentXmp` |
| Membership | Display `folderPath` plus explicit `folderMemberships`; not necessarily one folder per message |
| Body | `textBody/htmlBody/rtfBody`; selected `bodyMime`; `contentSetDescription`; staged body paths |
| Payload status | `payloadStaged`, source-embedding policy, attachment count, associated files |
| Rendering/evidence | Ephemeral header marker, content width, trusted render-asset map, debug log and conversion warnings |
| `AssociatedFile` | Original filename, detected MIME and raw Content-Type, AF relationship/token, bytes or data path + stored size, description, MD5/SHA-256, CID, inline/attachment/embed flags |
| `BodyChoice` | Text and HTML candidates, selected MIME, unsupported flag |

An associated file can be a source message, native attachment or inline asset. `emailAttachment` controls counts and required native annotations; `inline` controls rendering/association policy. A content ID does not always mean “not an email attachment.” Zero-byte native files are still represented by notice-page annotations even though some catalog helper lists skip empty data.

Current `AssociatedFile.size()` is an integer and `data()` may read a staged file wholly back into memory. A replacement needs 64-bit byte counts, streaming readers, and separate original/stored payload descriptors. The proposed [schema](message.schema.json) is a new interchange contract that supplies these distinctions; it is not what the existing CLI reads.

## Normalization invariants

1. Preserve original source bytes before any interpretation. Treat extraction/reconstruction as a derivation with parent source identity, not as an unchanged original.
2. Preserve raw field values separately from parsed/display fields. Existing readers sometimes reconstruct missing raw values; record that provenance in a replacement.
3. Generate GUIDs independently of Message-ID, subject or filename. Duplicate/missing Message-IDs must not merge records.
4. Normalize text newlines for rendering and remove XML-invalid code points, without modifying preserved bytes.
5. Preserve original attachment names and full raw media type. Produce safe PDF/disk names through a separate mapping.
6. Compute MD5 for required format fixity and SHA-256 for stronger operational identity. Neither a GUID nor MD5 is authentication.
7. Define source attachment count from MIME/MAPI attachment semantics, separately from inline render assets and generated finding aids.
8. Require a verifiable normalized sent date before creating message output. The current implementation rejects missing/unrecoverable dates; it may preserve a recoverable invalid raw date with normalized Sent and warning. Never invent conversion time as message sent time.

Java normalizes parsed dates using the process default timezone; conversion timestamps use UTC. Parsing may normalize source zone spelling. Preserve the instant, raw spelling and original offset where available for cross-language reproducibility. Do not identify equality by a particular timestamp display string.

## EML and Gmail raw messages

Read bytes under the message-size limit. Parse RFC message with MIME-aware decoding. Read subject; raw From/To/Cc/Bcc/Date/Message-ID; repeated In-Reply-To/References. Prefer parsed agents; on failure use lenient header parsing. Create a Source associated file containing exactly the input bytes, media type `message/rfc822`, original filename and both hashes.

Body selection in the current reader:

```text
if Content-Disposition is attachment: do not select as body
if encrypted multipart or enveloped S/MIME: emit visible no-decryption notice + warning
if text/plain: select decoded text and bare type
if text/html: select decoded HTML and bare type
if multipart/related:
    use the child matching start Content-ID; otherwise the first child
    fail if an explicit root cannot be found
if multipart/alternative:
    visit candidates in order; choose the last supported nonempty candidate
    retain a plain text candidate as fallback
    use an unsupported notice only when no supported candidate exists
if another multipart:
    concatenate supported sections in source order
otherwise:
    for unnamed non-CID content, emit unsupported-body notice + warning
```

Fill missing text from HTML stripping and missing HTML from escaped pre-wrapped text. Current `bodyMime` loses parameters (R4); the replacement should preserve the selected original part's full type and identify synthesized display variants separately. MIME depth, part counts and decoded bytes need budgets before recursion/decoding (R8).

The second traversal collects attachments. Preserve a complete `message/rfc822` part as one attached message; do not recursively flatten its files into the parent's attachment count. Other files qualify from disposition, filename, CID and non-body type. Record raw Content-Type including parameters. Base64/quoted-printable decoding defines the native bytes whose hashes/sizes are compared with stored payloads.

Gmail uses the same RFC-byte reader. It lists IDs and downloads `format=raw`, base64url-decodes the response, attaches original bytes and records all label names as memberships. Label selection supports exact IDs/names, an exact whole name containing commas, or comma-separated tokens. Multiple API label IDs follow Gmail's intersection behavior. `ALL_MAIL` removes the label constraint; the code does not set `includeSpamTrash`, so do not promise an export including those API-excluded areas.

The current downloader retries raw-message requests up to three attempts for 408/429/5xx and selected transport failures, with 250/500 ms waits. Listing, label/profile retrieval and OAuth have separate library behavior; there is no application-wide retry policy, snapshot isolation or tested live-Gmail completeness guarantee.

## Outlook MSG

Check OLE signature for `.msg`, with RFC-message fallback for mislabeled files. Read MAPI fields, address groups, Date/client-submit time, HTML/text/converted RTF and CID maps through the parser. Retain the original MSG bytes as `application/vnd.ms-outlook` Source. Missing byte-valued attachments are not silently invented.

Embedded message storage `__substg1.0_3701000D` is copied into a standalone compound file, with named-property storage and the standalone property-table header adjustment. The child MSG bytes are a derived complete attachment, not a byte-for-byte slice of the outer file. Test re-opening them with an independent MSG parser. ANSI property fallback currently assumes Windows-1252 in the extraction helper; additional code-page coverage needs fixtures.

## PST

Walk folders recursively and maintain folder paths. For each message, prefer transport-header metadata where available, recover missing recipient groups from MAPI, prefer SMTP addresses, and use the dedicated Internet Message-ID property with a warning if it differs from the header. Sent date comes from parseable raw header, then submission time, then delivery time.

Prefer source HTML, then plain text, then RTF conversion if neither exists. By-value and OLE attachments are read with limits. Embedded PST messages are recursively reconstructed as complete EML attachments, retaining nested attachments; depth beyond 32 is rejected. Unsupported methods fail explicitly.

The parent PST container is **not embedded unchanged**. `PayloadStore`/writer creates an EML representation from selected recovered fields/body/files. Header set, storage properties, deleted messages and original container bytes are not a full-fidelity PST round trip. EA-PDF §7.2 permits this export-before-conversion writer policy. The repair follow-up makes the limited export contract explicit in the README and PDF/source descriptions and enabled PREMIS. A port must preserve that provenance, or implement unchanged container retention plus derivation mapping when full mailbox preservation is required.

## MBOX

Use a binary line reader accepting CRLF/LF/CR. Recognize plausible `From ` envelope lines with weekday/date patterns. Read each message's headers through the empty separator. For `mboxcl/mboxcl2`, respect a validated nonnegative Content-Length and verify the next envelope boundary. `auto` selects length mode when that header exists; otherwise delimiter mode. In delimiter mode require a plausible following header line to reduce body-text false splits.

Unquote one leading `>` on body lines according to the variant: `mboxo` reverses `>From `; `mboxrd` reverses one level of `>+From `; `mboxcl2` does not unquote. Preserve other byte values via a lossless single-byte mapping. Emit individual EML files named `<mailbox-stem>_<six-digit-sequence>.eml`; recover X-Gmail-Labels or mailbox stem as folder display. The original MBOX, envelopes and quoting representation are not retained as one raw Source container.

Ambiguous separator-like body content and Content-Length variants require source-specific corpora. Do not promise auto-detection is perfect.

## Staging and Gmail checkpoints

Job staging writes each attachment's bytes once per identity within a record, stores sizes/paths, clears source bytes, and writes normalized text/HTML/optional RTF files. Body accessors reopen staged strings when required. Only the job owner deletes the payload directory; persistent Gmail staging is separate.

Gmail resume is opt-in through `EAPDF_GMAIL_RESUME_DIR`. Its directory key hashes account identity, options, output locations, font/ICC content, application version and effective configuration. A lock prevents simultaneous exports for that scope. Manifest version 1 stores the original selected-ID list, downloaded raw EML SHA-256/labels and completed output paths/sizes/SHA-256. Existing selected IDs are reused even if the live mailbox list changes. Changed/corrupt raw/output data triggers re-download/re-conversion. Aggregate archives are rebuilt from staged downloads; per-message completed outputs can be skipped. POSIX permissions are private where supported; retained mail persists until explicitly removed.

For the replacement, expose whether a resume scope is a fixed export snapshot or a fresh selection. Persist a validated manifest before marking outputs complete; record partial and cancelled states; enforce private storage on all supported platforms.
