# Adobe extension reference review

Reviewed 2026-10-21: the [official Adobe publication](https://www.adobe.com/content/dam/cc1/en/devnet/pdf/pdfs/adobe_supplement_iso32000_1.pdf) identifies the June 2009 supplement, Edition 2.0, Acrobat SDK 9.1, base PDF1.7 and extension level5. All eight pages were inspected. Download SHA256: `480d11af588612c378015036e6485ee405ce55ec329021e99c06961f03ea6480`.

Section2 explains extension identifiers and cumulative extension support. Section3.2 changes how a reader handles an unmatched collection default-document value. EA-PDF9.5 independently prohibits that default-document entry and explicitly requires exactly the cited base/version values for PDF1.7 containers. Thus the supplement does not justify accepting level6 in the EA-PDF check. The V65 level5/level6 controls both pass PDF/A-3U; the retained EA-PDF assertion distinguishes them.

This resolves the exact publication identification gap for the EA-PDF collection reference. It does not certify every inherited ISO32000-1 predicate, viewer behavior, earlier Adobe extension, erratum, rendering feature or unrelated normative edition. No runtime change follows from this source review.
