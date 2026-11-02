# Java/web comparison results

Compared **808 parent PDFs**, **67 matched embedded-file reports**, and all **213 active rule codes**.

**Full Java/web rule-outcome parity verified. Overall PASS is enabled when required comparisons complete.**

Counts below are comparisons against V85, not independent proof of which engine correctly applies every requirement.

| Comparison | Initial Java | Repaired Java |
| --- | ---: | ---: |
| Matching overall results | 628 | 875 |
| Matching rule/file outcomes | 182829 | 186375 |
| Rule/file pairs with V85 FAIL but no Java FAIL | 7 | 0 |
| Rule/file pairs with Java FAIL but no V85 FAIL | 9 | 0 |
| Rule codes with at least one missed failure | 7 | 0 |
| Rule codes with at least one Java-only failure | 9 | 0 |
| Matching PDF/A results and failing rule identifiers | 875 | 875 |
| Matching selected ISO results and failing rule identifiers | 875 | 875 |
| Matching PDF/A object locations and error text | 875 | 875 |
| Matching selected ISO object locations and error text | 875 | 875 |
| Uncaught comparison execution errors | 0 | 0 |
| Caught Java inspection errors | 0 | 0 |
| Parent files with different checked-child inventories | 0 | 0 |

Each rule outcome is compared in every parent and matched child report. Finding wording and occurrence counts can differ because observations are aggregated differently; the complete evidence retains item-level counts.

The comparison establishes parity with the existing web engine, not independent standards certification. Existing unavailable-evidence and inspection-budget limits remain.

## Remaining failure differences

| Rule | Web FAIL without Java FAIL | Java FAIL without web FAIL |
| --- | ---: | ---: |
| None | 0 | 0 |

## Examples and protected references

| File | V85 | Java | V85 PDF/A | Java PDF/A |
| --- | --- | --- | --- | --- |
| examples/sample_1c.pdf | PASS | PASS | PASS | PASS |
| examples/sample_1c_finding_aid.pdf | FAIL | FAIL | PASS | PASS |
| examples/sample_1ci.pdf | PASS | PASS | PASS | PASS |
| examples/sample_1ci_finding_aid.pdf | FAIL | FAIL | PASS | PASS |
| examples/sample_1m.pdf | INCOMPLETE | INCOMPLETE | PASS | PASS |
| examples/sample_1mi.pdf | INCOMPLETE | INCOMPLETE | PASS | PASS |
| examples/sample_1s.pdf | PASS | PASS | PASS | PASS |
| examples/sample_1si.pdf | PASS | PASS | PASS | PASS |
| output/Agenda and timeline slides_000001.pdf | PASS | PASS | PASS | PASS |
| output/bfo.pdf | FAIL | FAIL | PASS | PASS |

## All 213 active rule codes

“Same level” counts maximum-severity agreement across parent and matched child reports. “Different item counts” includes differences caused by aggregation; the full evidence records the counts.

| Rule | Same level / comparisons | V85-only FAIL | Java-only FAIL | Different item counts |
| --- | ---: | ---: | ---: | ---: |
| SOURCE_IN_ISOLATED | 875 / 875 | 0 | 0 | 0 |
| EAPDF_CONFORMANCE_VALUE | 875 / 875 | 0 | 0 | 0 |
| PDFA_PART | 875 / 875 | 0 | 0 | 0 |
| PDFA_CONFORMANCE | 875 / 875 | 0 | 0 | 0 |
| CONTENT_SET_COUNT | 875 / 875 | 0 | 0 | 0 |
| AF_RULE | 875 / 875 | 0 | 0 | 0 |
| SOURCE_AF | 875 / 875 | 0 | 0 | 0 |
| MESSAGE_SOURCE_MATCH | 875 / 875 | 0 | 0 | 38 |
| CORE_FIELDS_TEXT_OBJECTS | 875 / 875 | 0 | 0 | 0 |
| PAGE_CONTEXT_PRESENT_RECOMMENDED | 875 / 875 | 0 | 0 | 0 |
| LOGICAL_STRUCTURE_EQUIVALENCE_RECOMMENDED | 875 / 875 | 0 | 0 | 0 |
| ANNOTATION_CONTENTS_REQUIRED | 875 / 875 | 0 | 0 | 0 |
| ASSOCIATED_FILE_MODDATE | 875 / 875 | 0 | 0 | 0 |
| ATTACHMENT_XMP_MATCH | 875 / 875 | 0 | 0 | 161 |
| COLLECTION_CHILD_VALIDATION | 875 / 875 | 0 | 0 | 44 |
| COLLECTION_SCHEMA_FIELD | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_CONTENT_TYPES | 875 / 875 | 0 | 0 | 10 |
| DPM_KEY_PREFIX | 875 / 875 | 0 | 0 | 0 |
| EMBEDDED_STREAM | 875 / 875 | 0 | 0 | 694 |
| STRUCTURE_CORRESPONDENCE | 875 / 875 | 0 | 0 | 0 |
| PDF_HEADER_VERSION_READ | 875 / 875 | 0 | 0 | 0 |
| PDF_CATALOG_VERSION | 875 / 875 | 0 | 0 | 0 |
| PDF_EFFECTIVE_VERSION | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_PRESENT | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_TITLE_MATCH | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_PRODUCER_MATCH | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_AUTHOR_MATCH | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_SUBJECT_MATCH | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_CREATOR_MATCH | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_CREATIONDATE_MATCH | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_MODDATE_MATCH | 875 / 875 | 0 | 0 | 0 |
| DOCINFO_KEYWORDS | 875 / 875 | 0 | 0 | 1 |
| CATALOG_PIECEINFO | 875 / 875 | 0 | 0 | 0 |
| XMP_KEYWORDS | 875 / 875 | 0 | 0 | 0 |
| PAGEMODE_PRESENT | 875 / 875 | 0 | 0 | 0 |
| NONFULLSCREENPAGEMODE_PRESENT | 875 / 875 | 0 | 0 | 0 |
| PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS | 875 / 875 | 0 | 0 | 0 |
| PROFILE_NFSM_SINGLE_NO_ATTACHMENTS | 875 / 875 | 0 | 0 | 0 |
| PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS | 875 / 875 | 0 | 0 | 0 |
| PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS | 875 / 875 | 0 | 0 | 0 |
| PROFILE_PAGEMODE_MULTI | 875 / 875 | 0 | 0 | 0 |
| PROFILE_NFSM_MULTI | 875 / 875 | 0 | 0 | 0 |
| PROFILE_PAGEMODE_CONTAINER | 875 / 875 | 0 | 0 | 0 |
| PROFILE_NFSM_CONTAINER | 875 / 875 | 0 | 0 | 0 |
| VIEWER_PREFS | 875 / 875 | 0 | 0 | 0 |
| DISPLAY_DOCTITLE | 875 / 875 | 0 | 0 | 0 |
| SOURCE_FILE_SET | 875 / 875 | 0 | 0 | 0 |
| OUTLINES_PRESENT | 875 / 875 | 0 | 0 | 0 |
| DPARTROOT_PRESENT | 875 / 875 | 0 | 0 | 0 |
| PAGE_DPART_PRESENT | 875 / 875 | 0 | 0 | 0 |
| CONTENT_SET_NONBLANK | 875 / 875 | 0 | 0 | 0 |
| PAGE_COUNT | 875 / 875 | 0 | 0 | 0 |
| CONTENT_SET_OUTLINE_REF | 875 / 875 | 0 | 0 | 0 |
| OUTLINE_ITEMS_PRESENT | 875 / 875 | 0 | 0 | 0 |
| PROFILE_EMAIL_COUNT_SINGLE | 875 / 875 | 0 | 0 | 0 |
| PROFILE_EMAIL_COUNT_MULTI | 875 / 875 | 0 | 0 | 0 |
| DPART_OBJECT_STRUCTURE | 875 / 875 | 0 | 0 | 854 |
| TEXT_CONTENT_PDF_TEXT_OBJECTS | 875 / 875 | 0 | 0 | 0 |
| ATTACHMENT_ANNOTATIONS_PRESENT | 875 / 875 | 0 | 0 | 0 |
| PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED | 875 / 875 | 0 | 0 | 0 |
| UNSPECIFIED_COLOR_SRGB_ASSUMED | 875 / 875 | 0 | 0 | 0 |
| ACTIONABLE_URLS_LINK_ANNOTATIONS | 875 / 875 | 0 | 0 | 2 |
| REFERENCED_ASSETS_AS_ASSOCIATED_FILES | 875 / 875 | 0 | 0 | 0 |
| REFERENCED_ASSETS_ASSOCIATED_TO_APPROPRIATE_OBJECT | 875 / 875 | 0 | 0 | 0 |
| EMBEDDEDFILES_PRESENT | 875 / 875 | 0 | 0 | 0 |
| AF_IN_NAME_TREE | 875 / 875 | 0 | 0 | 0 |
| EMBEDDED_FILESPEC_NO_PATH_COMPONENTS | 875 / 875 | 0 | 0 | 846 |
| EMBEDDED_SUBTYPE_MATCH_XMP | 875 / 875 | 0 | 0 | 675 |
| SOURCE_MEDIATYPE_PARAMETERS_MATCH | 875 / 875 | 0 | 0 | 23 |
| EMBEDDED_MEDIATYPE_RECONSTRUCTION | 875 / 875 | 0 | 0 | 0 |
| SOURCE_MIME_GENERIC | 875 / 875 | 0 | 0 | 0 |
| ASSET_SIZE_MATCH | 875 / 875 | 0 | 0 | 15 |
| ASSET_CONTENTTYPE_MATCH | 875 / 875 | 0 | 0 | 15 |
| EMBEDDED_SIZE | 875 / 875 | 0 | 0 | 0 |
| EMBEDDED_MD5 | 875 / 875 | 0 | 0 | 0 |
| EMBEDDED_MD5_BINARY_FORMAT | 875 / 875 | 0 | 0 | 0 |
| PROFILE_COLLECTION | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_REQUIRED | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_FORBIDDEN | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_EXTENSIONS | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_D_FORBIDDEN | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_VIEW_FORBIDDEN | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_NAVIGATOR_FORBIDDEN | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_FILENAMES_VALID | 875 / 875 | 0 | 0 | 51 |
| COLLECTION_FILENAMES_UNIQUE_CASEFOLD | 875 / 875 | 0 | 0 | 51 |
| COLLECTION_SCHEMA_PRESENT | 875 / 875 | 0 | 0 | 0 |
| COLLECTION_SCHEMA_E_FALSE | 875 / 875 | 0 | 0 | 1 |
| DPM_ROOTNODE_PRESENT | 875 / 875 | 0 | 0 | 0 |
| DPM_NAME_TYPES | 875 / 875 | 0 | 0 | 853 |
| DPM_MAIL_GUID_PRESENT | 875 / 875 | 0 | 0 | 0 |
| DPM_MAIL_GUID_MATCH | 875 / 875 | 0 | 0 | 0 |
| DPM_MAIL_MESSAGEID_MATCH | 875 / 875 | 0 | 0 | 0 |
| DPM_INTERMEDIATE_COUNT_SINGLE | 875 / 875 | 0 | 0 | 0 |
| DPM_INTERMEDIATE_COUNT_MULTI | 875 / 875 | 0 | 0 | 0 |
| DPM_INTERMEDIATE_COUNT_CONTAINER | 875 / 875 | 0 | 0 | 49 |
| DPM_LEAF_REQUIRED | 875 / 875 | 0 | 0 | 854 |
| DPM_ATTACHMENTLIST_FORBIDDEN | 875 / 875 | 0 | 0 | 5 |
| DPM_CONTAINER_CONTENTSET_TYPES | 875 / 875 | 0 | 0 | 5 |
| DPM_CONTENTSETTYPE_PRESENT | 875 / 875 | 0 | 0 | 0 |
| DPM_FRONTMATTER_REQUIRED_CONTAINER | 875 / 875 | 0 | 0 | 0 |
| DPM_SUBTYPE_REQUIRED | 875 / 875 | 0 | 0 | 0 |
| DPM_SUBTYPE_MEDIA_TYPE_NAME | 875 / 875 | 0 | 0 | 152 |
| DPM_BODY_MEDIA_TYPE_MATCH_SOURCE | 875 / 875 | 0 | 0 | 0 |
| DPM_DESC_REQUIRED | 875 / 875 | 0 | 0 | 0 |
| CUSTOM_CONTENTSETS_TAGGEDPDF_RECOMMENDED | 875 / 875 | 0 | 0 | 0 |
| STRUCTTREE_ROOT_DOCUMENT | 875 / 875 | 0 | 0 | 0 |
| ROLEMAPNS_PRESENT | 875 / 875 | 0 | 0 | 774 |
| LOGICAL_TS32005_DECLARATION | 875 / 875 | 0 | 0 | 0 |
| ROLEMAP_MAIL_TAGS | 875 / 875 | 0 | 0 | 799 |
| CUSTOM_TAG_MARKED_SEQUENCE_ONLY | 875 / 875 | 0 | 0 | 0 |
| CUSTOM_TAG_OPERAND_MATCH | 875 / 875 | 0 | 0 | 0 |
| MAIL_MESSAGE_ROLEMAP_DOCUMENT | 875 / 875 | 0 | 0 | 148 |
| MAIL_CONTENTGROUP_ROLEMAP_PART | 875 / 875 | 0 | 0 | 148 |
| MAIL_CONTENTSET_ROLEMAP_ART | 875 / 875 | 0 | 0 | 150 |
| MAIL_FIELD_ROLEMAP_APPROPRIATE | 875 / 875 | 0 | 0 | 798 |
| MAIL_FIELDNAME_ROLEMAP_APPROPRIATE | 875 / 875 | 0 | 0 | 798 |
| MAIL_FIELDVALUE_ROLEMAP_APPROPRIATE | 875 / 875 | 0 | 0 | 798 |
| MAIL_MESSAGE_CHILDREN | 875 / 875 | 0 | 0 | 0 |
| MAIL_CONTENTGROUP_CHILDREN | 875 / 875 | 0 | 0 | 0 |
| MAIL_CONTENTSET_CHILDREN | 875 / 875 | 0 | 0 | 0 |
| MAIL_FIELD_CHILDREN | 875 / 875 | 0 | 0 | 0 |
| MAIL_FIELDVALUE_WITH_NAME | 875 / 875 | 0 | 0 | 0 |
| CONTENT_SET_MAIL_CONTENTSET_TAGGED | 875 / 875 | 0 | 0 | 0 |
| HEADER_FIELDS_TAGGED_RECOMMENDED | 875 / 875 | 0 | 0 | 0 |
| USERPROPS_NAME_ASCII | 875 / 875 | 0 | 0 | 0 |
| USERPROPS_TYPE | 875 / 875 | 0 | 0 | 0 |
| USERPROPS_TAG_SCOPE | 875 / 875 | 0 | 0 | 0 |
| USERPROPS_VALUES_UNMODIFIED | 875 / 875 | 0 | 0 | 7 |
| MAIL_CONTENTSET_ART_ONLY | 875 / 875 | 0 | 0 | 155 |
| MULTI_EMAIL_DOCUMENT_STRUCTURE | 875 / 875 | 0 | 0 | 0 |
| MAIL_MESSAGE_COUNT_SINGLE | 875 / 875 | 0 | 0 | 0 |
| MAIL_MESSAGE_COUNT_MULTI | 875 / 875 | 0 | 0 | 0 |
| MAIL_MESSAGE_COUNT_CONTAINER | 875 / 875 | 0 | 0 | 0 |
| MAIL_MESSAGE_GUID_ATTRIBUTE | 875 / 875 | 0 | 0 | 148 |
| MAIL_MESSAGE_GUID_MATCH_DPM_XMP | 875 / 875 | 0 | 0 | 784 |
| MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE | 875 / 875 | 0 | 0 | 148 |
| MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED | 875 / 875 | 0 | 0 | 148 |
| MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT | 875 / 875 | 0 | 0 | 147 |
| MAIL_CONTENTSET_COUNT | 875 / 875 | 0 | 0 | 0 |
| MAIL_CONTENTSET_NESTED_UNDER_MESSAGE | 875 / 875 | 0 | 0 | 151 |
| MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED | 875 / 875 | 0 | 0 | 151 |
| MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT | 875 / 875 | 0 | 0 | 146 |
| MAIL_FIELD_PROFILE_USE | 875 / 875 | 0 | 0 | 798 |
| MAIL_FIELD_NESTING_RECOMMENDED | 875 / 875 | 0 | 0 | 798 |
| MAIL_FIELDNAME_PROFILE_USE | 875 / 875 | 0 | 0 | 798 |
| MAIL_FIELDNAME_NESTING_RECOMMENDED | 875 / 875 | 0 | 0 | 798 |
| MAIL_FIELDVALUE_PROFILE_USE | 875 / 875 | 0 | 0 | 798 |
| MAIL_FIELDVALUE_NESTING_RECOMMENDED | 875 / 875 | 0 | 0 | 798 |
| XMP_NS_PREFIX | 875 / 875 | 0 | 0 | 0 |
| EAPDF_VERSION | 875 / 875 | 0 | 0 | 0 |
| EAPDF_REV | 875 / 875 | 0 | 0 | 0 |
| XMP_CREATOR | 875 / 875 | 0 | 0 | 0 |
| XMP_CREATOR_SEMANTICS | 875 / 875 | 0 | 0 | 159 |
| XMP_PRODUCER | 875 / 875 | 0 | 0 | 0 |
| XMP_PRODUCER_SEMANTICS | 875 / 875 | 0 | 0 | 159 |
| XMP_CREATEDATE | 875 / 875 | 0 | 0 | 0 |
| XMP_CREATEDATE_SEMANTICS | 875 / 875 | 0 | 0 | 159 |
| XMP_TITLE | 875 / 875 | 0 | 0 | 0 |
| EAPDF_CONFORMANCE_I_LAST | 875 / 875 | 0 | 0 | 0 |
| SOURCE_WITHOUT_XMP | 875 / 875 | 0 | 0 | 0 |
| ASSET_XMP | 875 / 875 | 0 | 0 | 115 |
| ASSET_XMP_FIELDS | 875 / 875 | 0 | 0 | 0 |
| ASSET_MATCH | 875 / 875 | 0 | 0 | 0 |
| ASSET_SIZE_TYPE | 875 / 875 | 0 | 0 | 0 |
| SOURCE_MIME_TYPE | 875 / 875 | 0 | 0 | 0 |
| ASSET_CONTENTTYPE | 875 / 875 | 0 | 0 | 0 |
| ASSET_NUMBERMESSAGES_TYPE | 875 / 875 | 0 | 0 | 0 |
| ASSET_CHECKSUM_FORM | 875 / 875 | 0 | 0 | 0 |
| ASSET_MD5_MATCH | 875 / 875 | 0 | 0 | 15 |
| ASSET_CONTENTTYPE_GENERIC | 875 / 875 | 0 | 0 | 0 |
| EMAIL_XMP_CONTAINER | 875 / 875 | 0 | 0 | 803 |
| MESSAGE_TO_XMP | 875 / 875 | 0 | 0 | 38 |
| MESSAGE_TO_FOAF | 875 / 875 | 0 | 0 | 0 |
| MESSAGE_RAW_TO_XMP | 875 / 875 | 0 | 0 | 3 |
| MESSAGE_FROM_XMP | 875 / 875 | 0 | 0 | 38 |
| MESSAGE_FROM_FOAF | 875 / 875 | 0 | 0 | 0 |
| MESSAGE_RAW_FROM_XMP | 875 / 875 | 0 | 0 | 7 |
| MESSAGE_SENT_XMP | 875 / 875 | 0 | 0 | 24 |
| MESSAGE_SENT_FORMAT | 875 / 875 | 0 | 0 | 613 |
| MESSAGE_RAW_SENT_REQUIRED_IF_INVALID | 875 / 875 | 0 | 0 | 46 |
| MESSAGE_RAW_SENT_OMIT_IF_VALID | 875 / 875 | 0 | 0 | 38 |
| MESSAGE_SUBJECT_XMP | 875 / 875 | 0 | 0 | 107 |
| MESSAGE_ID_XMP | 875 / 875 | 0 | 0 | 614 |
| MAIL_GUID_XMP | 875 / 875 | 0 | 0 | 803 |
| MAIL_GUID_UNIQUE | 875 / 875 | 0 | 0 | 0 |
| MESSAGE_SIZE_XMP | 875 / 875 | 0 | 0 | 0 |
| MESSAGE_ATTACHMENT_COUNT_XMP | 875 / 875 | 0 | 0 | 744 |
| MESSAGE_CC_XMP | 875 / 875 | 0 | 0 | 38 |
| MESSAGE_CC_FOAF | 875 / 875 | 0 | 0 | 0 |
| MESSAGE_RAW_CC_XMP | 875 / 875 | 0 | 0 | 4 |
| MESSAGE_BCC_XMP | 875 / 875 | 0 | 0 | 38 |
| MESSAGE_BCC_FOAF | 875 / 875 | 0 | 0 | 0 |
| MESSAGE_RAW_BCC_XMP | 875 / 875 | 0 | 0 | 4 |
| MESSAGE_INREPLYTO_XMP | 875 / 875 | 0 | 0 | 4 |
| ATTACHMENT_XMP | 875 / 875 | 0 | 0 | 1 |
| ATTACHMENT_XMP_FILENAME | 875 / 875 | 0 | 0 | 0 |
| ATTACHMENT_XMP_SIZE_PRESENT | 875 / 875 | 0 | 0 | 0 |
| ATTACHMENT_XMP_SIZE_TYPE | 875 / 875 | 0 | 0 | 0 |
| ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID | 875 / 875 | 0 | 0 | 795 |
| ATTACHMENT_XMP_CONTENTTYPE | 875 / 875 | 0 | 0 | 0 |
| ATTACHMENT_XMP_CONTENTTYPE_MATCH | 875 / 875 | 0 | 0 | 0 |
| ATTACHMENT_XMP_CHECKSUM_FORM | 875 / 875 | 0 | 0 | 0 |
| FONTS_NONE | 875 / 875 | 0 | 0 | 0 |
| XMP_KEYWORDS_UNCHECKED | 875 / 875 | 0 | 0 | 0 |
| STRUCTTREE_PRESENT | 875 / 875 | 0 | 0 | 0 |
| PDFA_XMP_UNCHECKED | 875 / 875 | 0 | 0 | 0 |
| EAPDF_XMP_UNCHECKED | 875 / 875 | 0 | 0 | 0 |
| SOURCE_MIME_NONE | 875 / 875 | 0 | 0 | 0 |
| ASSET_XMP_SKIPPED | 875 / 875 | 0 | 0 | 0 |
| MESSAGE_METADATA_SKIPPED | 875 / 875 | 0 | 0 | 0 |
| OPEN | 875 / 875 | 0 | 0 | 0 |
| PROFILE_RULES_SKIPPED | 875 / 875 | 0 | 0 | 0 |
| VALIDATION_INCOMPLETE | 875 / 875 | 0 | 0 | 106 |

## Evidence

- [Method, repairs and GUI references](README.md)
- [Hashes and build results](evidence.json)
- [Reviewed rule matrix with example paths](rules.json)
- [Complete repaired comparison, compressed JSON](comparison.json.gz)
- [Complete initial comparison, compressed JSON](baseline-comparison.json.gz)
