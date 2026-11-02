# V16 — exact filename and native annotation evidence

Table 19 keeps the original case-sensitive filename as text and permits a
separate stored PDF filename. Association now preserves decoded PDF UF/F text
and original MIME parameter text, including significant spaces. UF retains its
existing precedence. Display cleanup, path stripping and control-character
replacement cannot establish filename equality. Independent stored/source
payload checksums still support permitted aliases; unresolved associations
remain INCOMPLETE.

Python get_filename strips quoted outer spaces. Source summaries now retain the
untrimmed parsed filename/name parameter, including RFC 2231 values, alongside
the existing display filename. Native File Attachment annotation matching also
excludes AFRelationship Alternative under section 7.5; a converted
representation does not stand in for the native attachment annotation required
by section 9.3.

Eight exact-byte probes independently pass PDF/A and upstream ISO. The baseline
misreports six outcomes: one matching repeated-space name was INCOMPLETE; three
sanitized/trimmed name associations were PASS; a source-contradicted filename and
an Alternative-only body annotation were PASS. Current results are respectively
PASS, three INCOMPLETE cases, and the two exact declared failures.

Final frozen inputs are in `.eapdf-verification-v16` and runtime-inputs.zip.
[Evidence](evidence.json) verifies 389 unit passes/33 skips, all eight PDF probes,
twelve generated normal/accessibility profiles, 141 custom-only fixture
comparisons and twelve actual CLI/isolated-worker checks. Only the six reviewed
custom outcomes change. All 133 legacy fixture bytes and custom outcomes remain
unchanged; their upstream results retain the separately bounded V14/V15 gates.

Initial failures are retained. The first implementation exposed the need to
preserve UF precedence and compatibility with direct helper callers whose mock
file-spec name is supplied only on annotation.filename; real PDF associations
always use the retained raw values. Creator/JAR, public CLI and debug behavior
are unchanged. Eleven expert/dependency questions and global occurrence/owner
assignment remain open; this pass is not standards-completeness certification.
