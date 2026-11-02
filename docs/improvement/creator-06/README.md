# C06: MSG raw addresses and visible mailboxes

MSG raw-address XMP preferred reconstructed agent text over supplied transport
headers, losing original quotes and comments. Visible To/Cc/Bcc used MAPI
display labels alone even when the reader had the actual mailbox addresses.

Raw-From/To/Cc/Bcc now retain supplied nonblank transport text. Missing headers
keep the existing agent/display fallback. Visible recipient fields use the known
agent identities, with the existing label-only fallback when no mailbox is known.
The reader does not invent a mailbox for a display-only recipient.

The debugger captured `displayTo=Recipient`, the actual mailbox and the literal
transport values before editing, with no evaluation errors. Two of four
regressions failed: lost raw text and an absent visible mailbox. All four pass
after repair, including missing-header and display-only controls.

Verification: **185 Java tests pass**, no failures, errors or skips. Twelve
ordinary normal/accessibility profiles pass unchanged V85 EA-PDF, actual PDF/A
and the verified ISO profile. Six native MSG profile checks prove exact raw-XMP
text, visible known recipient addresses, selected body subtypes and unchanged
original MSG streams. Their PDF/A/ISO checks pass without mandatory failures.
Isolated EA-PDF profiles PASS; non-isolated MSG source comparisons remain
INCOMPLETE under V85's documented unsupported-source limit.

Only `MsgReader.java` changes in this batch. The native MSG inputs have separate
`address-inputs/` storage, and both raw-XMP and visible-text assertions run on
reopened actual PDFs and collection children. Validator code/rules, CLI arguments,
existing debug statements and saved examples are unchanged. The temporary remote
configuration and agent logpoint were removed. The source overlay and patch
separate this repair from C05 and later work.

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-06-build package
python3 -B docs/improvement/creator-06/verify-profiles.py
python3 -B docs/improvement/creator-06/verify-msg.py
```

Use the frozen C06 overlay on C05 to reproduce this checkpoint. Contradictory
transport/MAPI histories, arbitrary native-source fidelity, complete rendering,
accessibility and validator coverage remain open. Continuous creator work next
addresses flowed plain-text display.

[Evidence](evidence.json), [ordinary profiles](profile-results.json),
[native MSG profiles](msg-results.json), [runtime proof](before-tracepoints.json),
[incremental patch](MsgReader.java.patch).
