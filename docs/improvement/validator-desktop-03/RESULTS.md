# Java/V85 comparison results

Compared **808 parent PDFs**, **67 matched embedded-file reports**, and all **225 rule codes**.

**The Java port remains incomplete. Overall PASS is disabled.**

Counts below are comparisons against V85, not independent proof of which engine correctly applies every requirement.

| Comparison | Initial Java | Repaired Java |
| --- | ---: | ---: |
| Rule/file pairs with V85 FAIL but no Java FAIL | 187 | 55 |
| Rule/file pairs with Java FAIL but no V85 FAIL | 416 | 55 |
| Rule codes with at least one missed failure | 43 | 29 |
| Rule codes with at least one Java-only failure | 55 | 27 |
| Matching PDF/A results and failing rule identifiers | 873 | 873 |
| Matching selected ISO results and failing rule identifiers | 875 | 875 |
| Matching PDF/A object locations and error text | 875 | 875 |
| Matching selected ISO object locations and error text | 875 | 875 |
| Uncaught comparison execution errors | 0 | 0 |
| Caught Java inspection errors | 2 | 0 |
| Parent files with different checked-child inventories | 0 | 0 |

The comparison includes the deliberate INCOMPLETE guard and diagnostic/applicability differences. A matching maximum severity does not establish matching checks; item-level severity counts are retained in the full evidence.

The two finding-aid examples are ordinary PDF/A files, not separately declared EA-PDF files. Their EA-PDF failures are expected in this corpus.

## Remaining priorities

1. Complete attachment-annotation ownership and the permitted original-source association checks.
2. Finish raw-header/date comparisons and same-email source binding. Preserve unavailable evidence rather than inventing a match or silently using N/A.
3. Resolve remaining content-set/DPart ownership, structure attributes and conditional applicability.
4. Complete collection filename comparisons and remaining XMP value/container/qualifier checks.
5. Reconcile remaining per-rule diagnostics and rerun this corpus before enabling overall PASS.

These are port gaps; no creator or reader feature is proposed.

## Largest remaining failure differences

| Rule | V85 FAIL without Java FAIL | Java FAIL without V85 FAIL |
| --- | ---: | ---: |
| ATTACHMENT_ANNOTATIONS_PRESENT | 11 | 4 |
| MESSAGE_RAW_SENT_REQUIRED_IF_INVALID | 6 | 0 |
| MESSAGE_SIZE_XMP | 1 | 4 |
| MAIL_CONTENTSET_ART_ONLY | 0 | 4 |
| MESSAGE_TO_FOAF | 1 | 3 |
| ATTACHMENT_XMP_MATCH | 0 | 4 |
| DPM_BODY_MEDIA_TYPE_MATCH_SOURCE | 0 | 4 |
| COLLECTION_FILENAMES_UNIQUE_CASEFOLD | 3 | 0 |
| DPM_MAIL_MESSAGEID_MATCH | 1 | 2 |
| MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT | 0 | 3 |
| ASSET_MD5_MATCH | 1 | 2 |
| MESSAGE_RAW_FROM_XMP | 3 | 0 |
| STRUCTURE_CORRESPONDENCE | 3 | 0 |
| DOCINFO_TITLE_MATCH | 0 | 2 |
| PROFILE_EMAIL_COUNT_SINGLE | 0 | 2 |
| COLLECTION_FILENAMES_VALID | 2 | 0 |
| DPM_MAIL_GUID_MATCH | 0 | 2 |
| DPM_FRONTMATTER_REQUIRED_CONTAINER | 2 | 0 |
| LOGICAL_TS32005_DECLARATION | 1 | 1 |
| CONTENT_SET_MAIL_CONTENTSET_TAGGED | 0 | 2 |
| MAIL_MESSAGE_COUNT_CONTAINER | 0 | 2 |
| MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT | 0 | 2 |
| XMP_CREATEDATE | 0 | 2 |
| ASSET_CONTENTTYPE_MATCH | 2 | 0 |
| MESSAGE_RAW_CC_XMP | 2 | 0 |

## Examples and protected references

| File | V85 | Java | V85 PDF/A | Java PDF/A |
| --- | --- | --- | --- | --- |
| examples/sample_1c.pdf | PASS | INCOMPLETE | PASS | PASS |
| examples/sample_1c_finding_aid.pdf | FAIL | FAIL | PASS | PASS |
| examples/sample_1ci.pdf | PASS | INCOMPLETE | PASS | PASS |
| examples/sample_1ci_finding_aid.pdf | FAIL | FAIL | PASS | PASS |
| examples/sample_1m.pdf | INCOMPLETE | INCOMPLETE | PASS | PASS |
| examples/sample_1mi.pdf | INCOMPLETE | INCOMPLETE | PASS | PASS |
| examples/sample_1s.pdf | PASS | INCOMPLETE | PASS | PASS |
| examples/sample_1si.pdf | PASS | INCOMPLETE | PASS | PASS |
| output/Agenda and timeline slides_000001.pdf | PASS | INCOMPLETE | PASS | PASS |
| output/bfo.pdf | FAIL | FAIL | PASS | PASS |

## All 225 rule codes

“Same level” counts maximum-severity agreement across parent and matched child reports. “Different item counts” includes differences caused by aggregation; the full evidence records the counts.

| Rule | Same level / comparisons | V85-only FAIL | Java-only FAIL | Different item counts |
| --- | ---: | ---: | ---: | ---: |
| EMBEDDED_STREAM | 873 / 875 | 0 | 0 | 696 |
| OUTPUT_INTENT | 873 / 875 | 0 | 0 | 2 |
| FONTS_NONE | 873 / 875 | 0 | 0 | 2 |
| FONTS_EMBEDDED | 873 / 875 | 0 | 0 | 2 |
| FONTS_MISSING | 873 / 875 | 0 | 0 | 2 |
| FONTS_ALL_EMBEDDED | 873 / 875 | 0 | 0 | 2 |
| PDF_HEADER_VERSION_READ | 873 / 875 | 0 | 0 | 2 |
| PDF_CATALOG_VERSION | 873 / 875 | 0 | 0 | 2 |
| PDF_EFFECTIVE_VERSION | 873 / 875 | 0 | 0 | 2 |
| PDFA_PART | 873 / 875 | 0 | 0 | 2 |
| PDFA_CONFORMANCE | 873 / 875 | 0 | 0 | 2 |
| DOCINFO_PRESENT | 873 / 875 | 0 | 0 | 2 |
| DOCINFO_TITLE_MATCH | 871 / 875 | 0 | 2 | 4 |
| DOCINFO_PRODUCER_MATCH | 873 / 875 | 0 | 0 | 2 |
| DOCINFO_AUTHOR_MATCH | 873 / 875 | 0 | 0 | 2 |
| DOCINFO_SUBJECT_MATCH | 873 / 875 | 0 | 0 | 2 |
| DOCINFO_CREATOR_MATCH | 873 / 875 | 0 | 0 | 2 |
| DOCINFO_CREATIONDATE_MATCH | 873 / 875 | 0 | 0 | 2 |
| DOCINFO_MODDATE_MATCH | 873 / 875 | 0 | 0 | 2 |
| DOCINFO_KEYWORDS | 873 / 875 | 0 | 0 | 3 |
| CATALOG_PIECEINFO | 873 / 875 | 0 | 0 | 2 |
| XMP_KEYWORDS | 873 / 875 | 0 | 0 | 2 |
| XMP_KEYWORDS_UNCHECKED | 873 / 875 | 0 | 0 | 2 |
| PAGEMODE_PRESENT | 873 / 875 | 0 | 0 | 2 |
| NONFULLSCREENPAGEMODE_PRESENT | 873 / 875 | 0 | 0 | 2 |
| PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS | 867 / 875 | 0 | 1 | 8 |
| PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS | 865 / 875 | 1 | 0 | 10 |
| PROFILE_PAGEMODE_MULTI | 871 / 875 | 0 | 0 | 4 |
| PROFILE_NFSM_SINGLE_NO_ATTACHMENTS | 867 / 875 | 0 | 1 | 8 |
| PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS | 865 / 875 | 1 | 0 | 10 |
| PROFILE_NFSM_MULTI | 871 / 875 | 0 | 0 | 4 |
| VIEWER_PREFS | 873 / 875 | 0 | 0 | 2 |
| DISPLAY_DOCTITLE | 873 / 875 | 0 | 0 | 2 |
| AF_RULE | 662 / 875 | 0 | 0 | 213 |
| SOURCE_AF | 873 / 875 | 0 | 0 | 2 |
| OUTLINES_PRESENT | 873 / 875 | 0 | 0 | 2 |
| DPARTROOT_PRESENT | 873 / 875 | 0 | 0 | 2 |
| CONTENT_SET_COUNT | 854 / 875 | 0 | 0 | 21 |
| CONTENT_SET_NONBLANK | 852 / 875 | 0 | 0 | 23 |
| CONTENT_SET_OUTLINE_REF | 854 / 875 | 0 | 0 | 21 |
| PROFILE_EMAIL_COUNT_SINGLE | 850 / 875 | 0 | 2 | 25 |
| PROFILE_EMAIL_COUNT_MULTI | 854 / 875 | 0 | 0 | 21 |
| PAGE_COUNT | 873 / 875 | 0 | 0 | 2 |
| OUTLINE_ITEMS_PRESENT | 873 / 875 | 0 | 0 | 2 |
| PAGE_DPART_PRESENT | 873 / 875 | 0 | 0 | 2 |
| TEXT_CONTENT_PDF_TEXT_OBJECTS | 873 / 875 | 0 | 0 | 2 |
| ATTACHMENT_ANNOTATIONS_PRESENT | 826 / 875 | 11 | 4 | 60 |
| PAGE_CONTEXT_PRESENT_RECOMMENDED | 785 / 875 | 0 | 0 | 858 |
| PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED | 768 / 875 | 0 | 0 | 875 |
| UNSPECIFIED_COLOR_SRGB_ASSUMED | 873 / 875 | 0 | 0 | 2 |
| CORE_FIELDS_TEXT_OBJECTS | 640 / 875 | 0 | 0 | 328 |
| ACTIONABLE_URLS_LINK_ANNOTATIONS | 871 / 875 | 0 | 0 | 6 |
| REFERENCED_ASSETS_AS_ASSOCIATED_FILES | 873 / 875 | 0 | 0 | 2 |
| REFERENCED_ASSETS_ASSOCIATED_TO_APPROPRIATE_OBJECT | 873 / 875 | 0 | 0 | 2 |
| EMBEDDEDFILES_PRESENT | 873 / 875 | 0 | 0 | 2 |
| EMBEDDED_FILESPEC_NO_PATH_COMPONENTS | 846 / 875 | 0 | 0 | 875 |
| EMBEDDED_SUBTYPE_PRESENT | 873 / 875 | 0 | 0 | 2 |
| EMBEDDED_SUBTYPE_NO_PARAMETERS | 873 / 875 | 0 | 0 | 2 |
| SOURCE_MEDIATYPE_PARAMETERS_MATCH | 668 / 875 | 0 | 0 | 692 |
| AF_IN_NAME_TREE | 873 / 875 | 0 | 0 | 2 |
| EMBEDDED_SUBTYPE_MATCH_XMP | 837 / 875 | 1 | 0 | 682 |
| EMBEDDED_SIZE | 718 / 875 | 0 | 0 | 157 |
| EMBEDDED_MD5 | 872 / 875 | 0 | 0 | 3 |
| PROFILE_PAGEMODE_CONTAINER | 871 / 875 | 0 | 0 | 4 |
| PROFILE_NFSM_CONTAINER | 871 / 875 | 0 | 0 | 4 |
| PROFILE_COLLECTION | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_REQUIRED | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_FORBIDDEN | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_EXTENSIONS | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_D_FORBIDDEN | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_VIEW_FORBIDDEN | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_NAVIGATOR_FORBIDDEN | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_SCHEMA_PRESENT | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_SCHEMA_FIELD | 873 / 875 | 0 | 0 | 2 |
| COLLECTION_SCHEMA_E_FALSE | 873 / 875 | 0 | 0 | 3 |
| COLLECTION_FILENAMES_VALID | 869 / 875 | 2 | 0 | 23 |
| COLLECTION_FILENAMES_UNIQUE_CASEFOLD | 868 / 875 | 3 | 0 | 23 |
| DPM_ROOTNODE_PRESENT | 873 / 875 | 0 | 0 | 2 |
| DPM_KEY_PREFIX | 873 / 875 | 0 | 0 | 2 |
| DPM_INTERMEDIATE_COUNT_SINGLE | 852 / 875 | 0 | 0 | 23 |
| DPM_INTERMEDIATE_COUNT_MULTI | 854 / 875 | 0 | 0 | 21 |
| DPM_INTERMEDIATE_COUNT_CONTAINER | 856 / 875 | 0 | 0 | 68 |
| DPM_MAIL_GUID_PRESENT | 854 / 875 | 0 | 0 | 21 |
| DPM_MAIL_GUID_MATCH | 844 / 875 | 0 | 2 | 31 |
| DPM_MAIL_MESSAGEID_MATCH | 851 / 875 | 1 | 2 | 24 |
| DPM_LEAF_REQUIRED | 873 / 875 | 0 | 0 | 856 |
| DPM_CONTENTSETTYPE_PRESENT | 872 / 875 | 0 | 1 | 3 |
| DPM_DESC_REQUIRED | 873 / 875 | 0 | 0 | 2 |
| DPM_SUBTYPE_REQUIRED | 854 / 875 | 0 | 0 | 21 |
| DPM_ATTACHMENTLIST_FORBIDDEN | 856 / 875 | 0 | 0 | 24 |
| MESSAGE_ID_XMP | 838 / 875 | 0 | 1 | 301 |
| MAIL_GUID_XMP | 868 / 875 | 0 | 0 | 807 |
| DPM_FRONTMATTER_REQUIRED_CONTAINER | 854 / 875 | 2 | 0 | 21 |
| DPM_SUBTYPE_MEDIA_TYPE_NAME | 854 / 875 | 0 | 0 | 173 |
| STRUCTTREE_PRESENT | 866 / 875 | 0 | 0 | 9 |
| STRUCTTREE_ROOT_DOCUMENT | 873 / 875 | 0 | 0 | 2 |
| ROLEMAPNS_PRESENT | 848 / 875 | 0 | 0 | 801 |
| LOGICAL_TS32005_DECLARATION | 871 / 875 | 1 | 1 | 4 |
| LOGICAL_STRUCTURE_EQUIVALENCE_RECOMMENDED | 873 / 875 | 0 | 0 | 2 |
| CUSTOM_CONTENTSETS_TAGGEDPDF_RECOMMENDED | 856 / 875 | 0 | 0 | 19 |
| MAIL_FIELD_NESTING_RECOMMENDED | 857 / 875 | 0 | 0 | 816 |
| MAIL_FIELDNAME_NESTING_RECOMMENDED | 857 / 875 | 0 | 0 | 816 |
| MAIL_FIELDVALUE_NESTING_RECOMMENDED | 857 / 875 | 0 | 0 | 816 |
| ROLEMAP_MAIL_TAGS | 873 / 875 | 0 | 0 | 801 |
| CUSTOM_TAG_MARKED_SEQUENCE_ONLY | 844 / 875 | 0 | 0 | 794 |
| CUSTOM_TAG_OPERAND_MATCH | 873 / 875 | 0 | 0 | 2 |
| HEADER_FIELDS_TAGGED_RECOMMENDED | 844 / 875 | 0 | 0 | 31 |
| MAIL_MESSAGE_CHILDREN | 857 / 875 | 0 | 0 | 18 |
| MAIL_CONTENTSET_COUNT | 873 / 875 | 0 | 0 | 2 |
| CONTENT_SET_MAIL_CONTENTSET_TAGGED | 866 / 875 | 0 | 2 | 9 |
| MAIL_FIELD_CHILDREN | 858 / 875 | 0 | 0 | 17 |
| MAIL_FIELDVALUE_WITH_NAME | 858 / 875 | 0 | 0 | 17 |
| USERPROPS_VALUES_UNMODIFIED | 157 / 875 | 0 | 0 | 736 |
| USERPROPS_NAME_ASCII | 858 / 875 | 0 | 0 | 17 |
| USERPROPS_TYPE | 858 / 875 | 0 | 0 | 17 |
| USERPROPS_TAG_SCOPE | 858 / 875 | 0 | 0 | 17 |
| MAIL_CONTENTSET_ART_ONLY | 864 / 875 | 0 | 4 | 159 |
| MULTI_EMAIL_DOCUMENT_STRUCTURE | 850 / 875 | 0 | 0 | 25 |
| MAIL_CONTENTGROUP_CHILDREN | 858 / 875 | 0 | 0 | 17 |
| MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE | 155 / 875 | 1 | 0 | 868 |
| MAIL_MESSAGE_COUNT_SINGLE | 856 / 875 | 0 | 0 | 19 |
| MAIL_MESSAGE_COUNT_MULTI | 857 / 875 | 0 | 0 | 18 |
| MAIL_MESSAGE_COUNT_CONTAINER | 853 / 875 | 0 | 2 | 22 |
| MAIL_FIELD_PROFILE_USE | 821 / 875 | 0 | 0 | 852 |
| MAIL_FIELDNAME_PROFILE_USE | 821 / 875 | 0 | 0 | 852 |
| MAIL_FIELDVALUE_PROFILE_USE | 821 / 875 | 0 | 0 | 852 |
| MAIL_MESSAGE_GUID_ATTRIBUTE | 857 / 875 | 0 | 0 | 166 |
| MAIL_MESSAGE_GUID_MATCH_DPM_XMP | 839 / 875 | 0 | 1 | 810 |
| MAIL_MESSAGE_ROLEMAP_DOCUMENT | 857 / 875 | 0 | 0 | 166 |
| MAIL_CONTENTGROUP_ROLEMAP_PART | 857 / 875 | 0 | 0 | 166 |
| MAIL_CONTENTSET_NESTED_UNDER_MESSAGE | 852 / 875 | 1 | 0 | 173 |
| MAIL_CONTENTSET_ROLEMAP_ART | 857 / 875 | 0 | 0 | 168 |
| MAIL_FIELD_ROLEMAP_APPROPRIATE | 857 / 875 | 0 | 0 | 816 |
| MAIL_FIELDNAME_ROLEMAP_APPROPRIATE | 857 / 875 | 0 | 0 | 816 |
| MAIL_FIELDVALUE_ROLEMAP_APPROPRIATE | 857 / 875 | 0 | 0 | 816 |
| MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED | 873 / 875 | 0 | 0 | 150 |
| MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT | 862 / 875 | 0 | 3 | 149 |
| MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED | 873 / 875 | 0 | 0 | 153 |
| MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT | 866 / 875 | 0 | 2 | 149 |
| XMP_TITLE | 869 / 875 | 0 | 0 | 6 |
| XMP_PRESENT | 873 / 875 | 0 | 0 | 2 |
| XMP_NS_PREFIX | 873 / 875 | 0 | 0 | 2 |
| XMP_CREATOR | 873 / 875 | 0 | 0 | 2 |
| XMP_PRODUCER | 873 / 875 | 0 | 0 | 2 |
| XMP_CREATEDATE | 871 / 875 | 0 | 2 | 4 |
| XMP_MISSING | 873 / 875 | 0 | 0 | 2 |
| XMP_PARSE | 873 / 875 | 0 | 0 | 2 |
| PDFA_XMP_UNCHECKED | 873 / 875 | 0 | 0 | 2 |
| EAPDF_XMP_UNCHECKED | 873 / 875 | 0 | 0 | 2 |
| XMP_CREATOR_SEMANTICS | 873 / 875 | 0 | 0 | 161 |
| XMP_PRODUCER_SEMANTICS | 873 / 875 | 0 | 0 | 161 |
| XMP_CREATEDATE_SEMANTICS | 873 / 875 | 0 | 0 | 161 |
| EAPDF_VERSION | 873 / 875 | 0 | 0 | 2 |
| EAPDF_REV | 873 / 875 | 0 | 0 | 2 |
| EAPDF_CONFORMANCE_VALUE | 873 / 875 | 0 | 0 | 2 |
| EAPDF_CONFORMANCE_I_LAST | 873 / 875 | 0 | 0 | 2 |
| PDFA3_EAPDF_XMP_EXTENSION_SCHEMA | 873 / 875 | 0 | 0 | 2 |
| SOURCE_WITHOUT_XMP | 858 / 875 | 0 | 0 | 178 |
| SOURCE_MIME_NONE | 663 / 875 | 0 | 0 | 212 |
| SOURCE_MIME_TYPE | 871 / 875 | 0 | 0 | 28 |
| ASSET_XMP | 872 / 875 | 1 | 0 | 117 |
| ASSET_XMP_FIELDS | 873 / 875 | 0 | 0 | 2 |
| ASSET_SIZE_TYPE | 871 / 875 | 0 | 0 | 5 |
| ASSET_NUMBERMESSAGES_TYPE | 873 / 875 | 0 | 0 | 2 |
| ASSET_CHECKSUM_FORM | 872 / 875 | 0 | 0 | 4 |
| ASSET_CONTENTTYPE | 872 / 875 | 0 | 0 | 3 |
| ASSET_MATCH | 873 / 875 | 0 | 0 | 2 |
| ASSET_MD5_MATCH | 868 / 875 | 1 | 2 | 21 |
| ASSET_SIZE_MATCH | 870 / 875 | 0 | 1 | 20 |
| ASSET_CONTENTTYPE_MATCH | 869 / 875 | 2 | 0 | 21 |
| ASSET_XMP_SKIPPED | 0 / 875 | 0 | 0 | 875 |
| ASSET_CONTENTTYPE_GENERIC | 245 / 875 | 0 | 0 | 663 |
| SOURCE_MIME_GENERIC | 236 / 875 | 0 | 0 | 663 |
| MESSAGE_TO_XMP | 833 / 875 | 1 | 0 | 176 |
| MESSAGE_TO_FOAF | 837 / 875 | 1 | 3 | 42 |
| MESSAGE_RAW_TO_XMP | 850 / 875 | 1 | 0 | 629 |
| MESSAGE_METADATA_SKIPPED | 52 / 875 | 0 | 0 | 823 |
| MESSAGE_FROM_XMP | 837 / 875 | 1 | 0 | 77 |
| MESSAGE_FROM_FOAF | 845 / 875 | 0 | 0 | 40 |
| MESSAGE_RAW_FROM_XMP | 847 / 875 | 3 | 0 | 631 |
| MESSAGE_SENT_XMP | 860 / 875 | 0 | 0 | 40 |
| MESSAGE_SENT_FORMAT | 867 / 875 | 0 | 1 | 618 |
| MESSAGE_SUBJECT_XMP | 842 / 875 | 1 | 0 | 50 |
| MESSAGE_SIZE_XMP | 845 / 875 | 1 | 4 | 34 |
| MESSAGE_ATTACHMENT_COUNT_XMP | 850 / 875 | 0 | 0 | 764 |
| MESSAGE_CC_XMP | 354 / 875 | 0 | 0 | 672 |
| MESSAGE_CC_FOAF | 323 / 875 | 0 | 0 | 552 |
| MESSAGE_RAW_CC_XMP | 851 / 875 | 2 | 0 | 30 |
| MESSAGE_BCC_XMP | 355 / 875 | 0 | 0 | 672 |
| MESSAGE_BCC_FOAF | 324 / 875 | 0 | 0 | 551 |
| MESSAGE_RAW_BCC_XMP | 851 / 875 | 2 | 0 | 29 |
| MESSAGE_INREPLYTO_XMP | 318 / 875 | 0 | 1 | 557 |
| MESSAGE_RAW_SENT_REQUIRED_IF_INVALID | 830 / 875 | 6 | 0 | 89 |
| MESSAGE_RAW_SENT_OMIT_IF_VALID | 835 / 875 | 0 | 0 | 78 |
| ATTACHMENT_XMP | 828 / 875 | 1 | 0 | 47 |
| ATTACHMENT_XMP_FILENAME | 871 / 875 | 0 | 0 | 4 |
| ATTACHMENT_XMP_SIZE_PRESENT | 871 / 875 | 0 | 0 | 4 |
| ATTACHMENT_XMP_SIZE_TYPE | 871 / 875 | 0 | 0 | 4 |
| ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID | 870 / 875 | 0 | 0 | 799 |
| ATTACHMENT_XMP_MATCH | 853 / 875 | 0 | 4 | 184 |
| ATTACHMENT_XMP_CONTENTTYPE | 871 / 875 | 0 | 0 | 4 |
| ATTACHMENT_XMP_CONTENTTYPE_MATCH | 861 / 875 | 1 | 0 | 14 |
| ATTACHMENT_XMP_CHECKSUM_FORM | 700 / 875 | 0 | 0 | 178 |
| OPEN | 875 / 875 | 0 | 0 | 0 |
| SOURCE_IN_ISOLATED | 873 / 875 | 0 | 0 | 2 |
| PROFILE_RULES_SKIPPED | 871 / 875 | 0 | 0 | 4 |
| MESSAGE_SOURCE_MATCH | 844 / 875 | 1 | 0 | 74 |
| STRUCTURE_CORRESPONDENCE | 870 / 875 | 3 | 0 | 5 |
| COLLECTION_CHILD_VALIDATION | 821 / 875 | 0 | 0 | 68 |
| VALIDATION_INCOMPLETE | 234 / 875 | 0 | 0 | 764 |
| DPART_OBJECT_STRUCTURE | 873 / 875 | 0 | 0 | 856 |
| DPM_NAME_TYPES | 873 / 875 | 0 | 0 | 855 |
| EMAIL_XMP_CONTAINER | 822 / 875 | 0 | 0 | 855 |
| MAIL_GUID_UNIQUE | 867 / 875 | 0 | 1 | 8 |
| EMBEDDED_MD5_BINARY_FORMAT | 873 / 875 | 0 | 0 | 2 |
| DPM_BODY_MEDIA_TYPE_MATCH_SOURCE | 824 / 875 | 0 | 4 | 165 |
| SOURCE_FILE_SET | 872 / 875 | 0 | 0 | 3 |
| COLLECTION_CONTENT_TYPES | 871 / 875 | 0 | 0 | 14 |
| XMP_UTF8 | 873 / 875 | 0 | 0 | 2 |
| MAIL_CONTENTSET_CHILDREN | 858 / 875 | 0 | 0 | 17 |
| DPM_CONTAINER_CONTENTSET_TYPES | 873 / 875 | 0 | 0 | 7 |
| ASSOCIATED_FILE_MODDATE | 872 / 875 | 1 | 0 | 3 |
| EMBEDDED_MEDIATYPE_RECONSTRUCTION | 873 / 875 | 0 | 0 | 2 |
| ISO32005_UPSTREAM | 873 / 875 | 0 | 0 | 2 |
| ANNOTATION_CONTENTS_REQUIRED | 872 / 875 | 0 | 1 | 3 |

## Evidence

- [Method, repairs and GUI references](README.md)
- [Hashes and build results](evidence.json)
- [Reviewed rule matrix with example paths](rules.json)
- [Complete repaired comparison, compressed JSON](comparison.json.gz)
- [Complete initial comparison, compressed JSON](baseline-comparison.json.gz)
