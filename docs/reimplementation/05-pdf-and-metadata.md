# PDF and metadata construction

This is an implementation map, with explicit replacement requirements where current behavior is defective. Consult EA-PDF §9 and the ISO PDF object definitions for exact normative types and constraints.

## Object graph

```text
Trailer -> Catalog
  /Version /2.0
  /Pages -> page tree
  /Metadata -> UTF-8 XMP stream
  /OutputIntents -> sRGB ICC profile
  /ViewerPreferences -> DisplayDocTitle + NonFullScreenPageMode
  /Outlines -> content-set first-page destinations
  /DPartRoot -> DPartRootNode -> message nodes -> content-set leaves
  /StructTreeRoot -> Document -> Mail_Message -> Mail_ContentSet
  /Names /EmbeddedFiles -> source and auxiliary file specifications
  /AF -> associated file specifications, also in EmbeddedFiles
  /Collection -> schema/folders/sort (c/ci only)

Page
  /Contents -> normalized compressed stream
  /Resources -> embedded fonts/images/forms
  /DPart -> owning content-set leaf
  /StructParents -> ParentTree lookup key
  /Annots -> links and file-attachment annotations

FileAttachment annotation
  /FS -> file specification -> /EF /F and /UF -> embedded stream
  /AP /N -> visible badge appearance
  /Rect, /F, /Contents, /StructParent
```

Collection containers have FrontMatter DPM and no message identity nodes; their logical structure likewise has no `Mail_Message`. Their message pages reside in child PDFs. Non-isolated native email attachments are stored as annotation-only file specifications without adding them to the catalog AF/name-tree lists. Setting an `AFRelationship` field alone is not the same as referencing the file through an AF array.

## Page rendering

Default email layout is US Letter with 0.65 inch margins and an 8 px body inset. Text uses ArchiveFont with explicit configured fallbacks, nominally 11 pt. The header contains From, raw/display Date, To, optional Cc/Bcc, Subject, optional Message-ID, folder and archive context. Source-file and source-attachment listings follow the body. Native attachment notice pages are appended to the same BodyRendering set. Branding is drawn on the final page with PDFBox; multipage context can be drawn at the bottom of pages.

The current renderer canonicalizes via Jsoup, removes scripts, refresh metadata, external stylesheets/base tags and unsupported namespace constructs. It scopes accepted CSS selectors beneath `#email-root`, selects print rules, reports unsupported CSS and overrides fonts. HTTP asset references are normalized but network loading is disabled. Only `data:` URIs and exact entries in a job-owned trusted resource map may load. CID files are rewritten to hashed temporary file URLs with in-memory registered bytes. Source hyperlinks remain links; plain URL-like body text is also linkified, with `www.` given an HTTP target. No URL is fetched or activated during conversion.

OpenHTMLtoPDF produces initial pages. A failed primary render retries a plain-text XHTML rendering and records a reduced-rendering warning. This fallback can lose images, links and formatting. R1 identifies a separate silent image-only replacement before rendering. Font coverage warns about unavailable glyphs; generated PDFBox text uses `?` where no font can encode a character. Complex scripts, right-to-left order and shaping need explicit acceptance, not just font presence.

A replacement should return a `RenderedMessage` with pages, semantic tree, required-field text anchors, image/link inventory, content-set descriptors and fidelity diagnostics. Avoid inferring image placement from a blank band or attaching a body-success label solely because header text exists.

## Document parts

`PdfDocumentParts` creates indirect DPartRoot, DPartRootNode, message and content-set dictionaries. Its object allocator tracks existing/new object keys to avoid collisions when documents are loaded/imported.

```text
Catalog /DPartRoot -> indirect dictionary /Type /DPartRoot
  /DPartRootNode -> indirect DPart with /Parent back to DPartRoot
    /DParts [[message-node-1 message-node-2 ...]]

message-node:
  /Type /DPart
  /Parent -> root node
  /DPM << /Mail_GUID (uuid:...) /Mail_MessageID (<optional-id>) >>
  /DParts [[content-set-leaf ...]]

content-set-leaf:
  /Type /DPart
  /Parent -> message node (or root for front matter)
  /Start -> first page
  /End -> last page, omitted for a single-page set
  /DPM <<
    /Mail_ContentSetType /BodyRendering
    /Mail_Subtype /text#2Fhtml
    /Mail_Desc (Message body)
  >>
```

Every page points back to its owning leaf with `/DPart`. Ranges are inclusive and contiguous in this implementation. FrontMatter leaves omit Mail_Subtype and message identity. `s/si` has one message node; `m/mi` has at least two; `c/ci` has none. Outlines target every content set's first page using a page/FitH destination. Enhanced accessibility adds structure destinations while retaining legacy page fallbacks.

Internal code often records segment page numbers as one-based, while document APIs use zero-based indices. Define one convention at the port's PDF boundary and convert exactly once.

Table 8 DPM subtype is the **selected source body type and parameters**; R4 records that the current implementation emits only the base type. PDF name delimiters need hexadecimal escaping. This is different from an embedded-file stream Subtype, which must contain only the bare type with parameters stored separately.

## XMP contract

Required project namespaces/prefixes and other used namespaces:

| Prefix | URI |
| --- | --- |
| `pdfmail` | `http://www.pdfa.org/eapdf/` |
| `pdfmailid` | `http://www.pdfa.org/eapdf/ns/id/` |
| `pdfmailmeta` | `http://www.pdfa.org/eapdf/ns/meta/` |
| `rdf` | `http://www.w3.org/1999/02/22-rdf-syntax-ns#` |
| `foaf` | `http://xmlns.com/foaf/0.1/` |
| `pdfd` | `https://pdfa.org/declarations/` |
| `pdfaid` | `http://www.aiim.org/pdfa/ns/id/` |
| `dc` | `http://purl.org/dc/elements/1.1/` |
| `pdf` | `http://ns.adobe.com/pdf/1.3/` |
| `xmp` | `http://ns.adobe.com/xap/1.0/` |

Use UTF-8 XMP with a proper RDF model. XML-sensitive text/attributes must be escaped; source bytes remain unchanged. Stable prefix requirements apply to the EA-PDF schemas. The current writer emits multiple RDF Description blocks and an XMP packet wrapper; those byte/layout choices are not semantic invariants.

Document-level properties:

| Property | Current value/shape |
| --- | --- |
| `dc:creator` | Sequence whose first item identifies `EmailToPDFA <version>`, not sender |
| `dc:format` | `application/pdf` |
| `dc:title`, `dc:description` | Alt text with `xml:lang="x-default"`; subject/archive description |
| `pdf:Producer` | Application + renderer/PDF library identification |
| `pdf:Keywords` | Includes `EA-PDF` |
| `xmp:CreateDate`, `ModifyDate`, `MetadataDate` | Conversion UTC timestamp; not sent time |
| `xmp:CreatorTool` | Application version |
| `pdfaid:part`, `rev`, `conformance` | `4`, `2020`, `F` |
| `pdfmailid:version`, `rev`, `conformance` | `1`, `2024`, one of `s/si/m/mi/c/ci` |
| `pdfd:declarations` | Bag of structured claims; `conformsTo=https://pdfa.org/declarations#iso32005`, nested claimBy/claimDate data |

The document Info dictionary is removed for these PDF/A-4 outputs. Set a genuine sRGB output intent with embedded profile; do not merely label arbitrary ICC bytes “sRGB.” Independent validation must check the final file.

Message metadata uses `pdfmailmeta:email/rdf:Bag/rdf:li` structured resources. Fields are names in the `pdfmailmeta` namespace:

```xml
<pdfmailmeta:email>
  <rdf:Bag>
    <rdf:li rdf:parseType="Resource">
      <pdfmailmeta:Subject>Example</pdfmailmeta:Subject>
      <pdfmailmeta:Sent>2026-10-17T10:00:00Z</pdfmailmeta:Sent>
      <pdfmailmeta:Mail_GUID>uuid:00000000-0000-4000-8000-000000000001</pdfmailmeta:Mail_GUID>
      <pdfmailmeta:messageid>&lt;example@example.org&gt;</pdfmailmeta:messageid>
      <pdfmailmeta:NumberAttachments>0</pdfmailmeta:NumberAttachments>
      <pdfmailmeta:From>
        <rdf:Seq>
          <rdf:li><rdf:Description foaf:mbox="mailto:alice@example.org" foaf:name="Alice"/></rdf:li>
        </rdf:Seq>
      </pdfmailmeta:From>
    </rdf:li>
  </rdf:Bag>
</pdfmailmeta:email>
```

This fragment omits the surrounding namespace declarations and other fields for readability. The current writer's spelling is lower-case `messageid`; preserve the specification's corresponding linkage and test interoperability instead of blindly renaming it. Address groups To/From/Cc/Bcc use ordered FOAF agent descriptions. Raw address fields are separately retained. Sent and GUID are required; emit nonempty optional core fields, SizeInBytes where meaningful, NumberAttachments when applicable and an unordered string Bag for In-Reply-To. Original `References` survives source reconstruction but is not currently emitted by `appendReplyMetadata`.

`pdfmailmeta:assets` is a Bag of source descriptions with `Filename`, `SizeInBytes`, `ContentType`, `Checksum` and optional `NumberMessages`. Source asset checksum spelling is `Checksum`. `pdfmailmeta:attachments` is a Bag of attachment descriptions with `Filename`, stored `SizeInBytes`, raw `Content-Type`, owner `Mail_GUID` (or permissible Message-ID linkage), optional `CheckSum` and additional description. Attachment checksum spelling is `CheckSum`. Values describe the actual stored bytes; isolated originals' hashes/sizes belong in separate omission provenance.

## Embedded files and annotations

For each embedded payload, produce a file specification with safe `/F` and Unicode `/UF`, description, relationship, and `/EF` referencing an embedded stream. The current implementation sets both `/F` and `/UF` to the same stream. Stream `/Subtype` is the bare media type; `/Mail_MediaTypeParameters` retains the raw source parameters where needed. `/Params` includes actual uncompressed Size, ModDate and a **16-byte binary MD5 string**, commonly serialized as a PDF hex string. XMP MD5 is 32 hexadecimal characters. Do not embed the ASCII hex text as 32 checksum bytes.

Catalog associated files must also be in the EmbeddedFiles name tree, using unique keys. Non-isolated Source bytes are original EML/MSG or derived mailbox EML as described in the ingestion document. Native attachments are separately embedded in file-attachment annotations on notice pages; they are not catalog attachments under the current policy. Annotation flags allow display/printing; the rectangle is fully inside CropBox; the appearance stream draws the badge. The annotation contains an explicit human-readable description and filename. All annotations must remain correctly owned by the logical structure when tagging is used.

For isolated native attachments, embed a zero-byte stream with size 0, empty-payload MD5 parameter and omission text. Omit original-payload XMP CheckSum; retain original size/hash as provenance. Keep attachment counts and inline images. Isolation must not silently convert missing data into an apparently intact payload.

Source links are initially emitted as synthetic `https://eapdf.local/attachment/...` URLs keyed by relationship/type/content hash. After embedding they are rewritten to file-attachment annotations. A replacement should bind links by explicit internal identifiers instead of depending on synthetic public-looking URLs surviving rendering.

## Logical structure

Always construct one `Document` root when tagging. Message content uses `Mail_Message`, optional `Mail_ContentGroup`, `Mail_ContentSet`, and header `Mail_Field`, `Mail_FieldName`, `Mail_FieldValue`. Current roles map to Document, Part, Art, TR, TD and TD respectively. Custom namespace URI is `https://pdfa.org/ns/ea-pdf/mail-1`; the standard PDF 2.0 namespace is `http://iso.org/pdf2/ssn`; legacy roles use `http://iso.org/pdf/ssn`. RoleMap and RoleMapNS must agree with the applicable standard mappings.

Header markers are random temporary HTML title strings, used to identify renderer-produced field elements, then removed/replaced. Preserve the renderer's semantic subtree where possible. Imported pages and tree objects must refer to the same cloned pages. Allocate/remap StructParents/StructParent keys, attach MCID/MCR and annotation OBJR links, and build a correct number-tree ParentTree. Every content reference must resolve in both directions; no duplicate MCID within a content scope or orphan lookup entry is acceptable.

Message UserProperties carry Mail_GUID and optional Mail_MessageID. Content sets carry type and selected subtype. Standard headings/tables/images/links are retained more richly with the accessibility option. Generated unmarked text/images are wrapped with standard elements; annotations get Link/Annot owners. Complete inclusion rules, reading order and image descriptions still need validation (R7 and TS 32005 gap). A declaration or `/MarkInfo /Marked true` cannot establish accessibility conformance.

## Collections and finding aids

`/Collection` uses `/View /T`, no `/D`, sort/order metadata and a schema. Required fields/subtypes are To:S, From:S, Sent:D, Subject:S, Message-ID:S, Cc:S, Bcc:S, Size:Size and Attachments:N. Fields set `/E false`. Extra FileName, Messages and `adobe:Order` fields are implementation conveniences. Sent Collection Item values must be PDF dates, not human display strings.

The folder tree uses IDs and Parent/Child/Next links. Name-tree membership keys follow `<folder-id>filename`; the file specification uses the filename without that membership prefix. Root ID 0 holds ungrouped sources/auxiliary data. Per-message child names contain a sanitized subject, six-digit ordinal and child profile suffix. Source and auxiliary names need the same strict collection validation (R2). Current top-level folder names flatten original hierarchical display strings into safe names; this is not a complete reconstruction of an arbitrary mailbox folder hierarchy.

Finding aids use landscape Letter pages, wrapped/truncated rows, header fields and message totals. Archives have page links from row number/subject to message starts. `addFindingAidCollectionLinks` is currently empty: collection rows rely on the attachments panel and do not open children. Spreadsheet generation/embedding is separately configured. The copied `Finding_Aid.pdf` is saved before final EA-PDF decoration; do not treat it as an independently conforming EA-PDF child.

PREMIS is built before final PDF save, optionally embedded for non-isolated outputs, and written as a sidecar. Collections aggregate child object/event links. Its current XML structures and success semantics require R5 correction; a rewrite should validate PREMIS XSD and distinguish conversion, validation and publication events.

## Publication boundary

Save a temporary file beside the destination, run the creator gate, force file contents to disk and replace the destination atomically when supported. Current fallback is a non-atomic replacement if the filesystem does not support atomic move; parent-directory durability is not forced. PDF and subsequent PREMIS/XLSX sidecars are individually published, not one transaction. A replacement should publish a job manifest last, with accepted artifact hashes, and retain/recover failed intermediate states according to policy.
