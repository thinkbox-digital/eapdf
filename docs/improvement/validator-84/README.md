# V84: Subject encoding availability

Subject comparison requires an understood original decoding or exact preserved original text. A permissive repair of an invalid encoded-word cannot prove equality; a failed source decoding cannot prove a mismatch. Known supplied malformed encoded text is compared literally against an otherwise known original, so it cannot borrow a decoder repair. Required Subject presence and independent schema checks remain.

Strict header decoding now validates byte segments under their declared charset. This distinguishes an actual U+FFFD scalar in valid Unicode data from a replacement for unknown octets. Existing malformed-transfer and unknown-charset boundaries remain.

Ten native controls cover malformed Q/base64 payloads, unknown charset, exact literal preservation, valid decoding, known mismatches, a supplied malformed word, a real replacement scalar and required omission. Two silent passes and two unsupported failures become INCOMPLETE; one missed mismatch becomes FAIL. The source review uses EA-PDF section 7.3.1 and Tables 4/18 plus [RFC2047 section 6.3](https://www.rfc-editor.org/rfc/rfc2047.html#section-6.3).

Verification: 1144 unit tests (1104 passes, 40 skips); 766 fresh custom cases with all 756 prior hashes/statuses/failure sets unchanged; ten new and four positive collection PDF/A/ISO controls; CLI/worker agreement; twelve retained creator failures; sixteen offline browser checks. See [evidence.json](evidence.json).

Complete encoded-word placement/grammar, charset recovery, all normalization policies and full raw-header fidelity remain open. No creator changes or sample acceptance are claimed. The independent Table 19 filename-alternative proof is retained for V85.
