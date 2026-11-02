package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Frozen regression PDFs, with split predicates routed to the current reviewed rule catalogue. */
class WebParityRegressionTests {
  @org.junit.jupiter.api.Test
  void ordinaryEmailDatesCompareAsInstantsWithoutLosingGmtOrComments() {
    var expected = java.time.Instant.parse("2010-09-30T13:24:56Z");
    for (String value : java.util.List.of("Thu, 30 Sep 2010 13:24:56 GMT", "Thu, 30 Sep 2010 09:24:56 -0400 (EDT)",
        "Thu, 30 Sep 2010 13:24:56 -0000", "Thu, 30 Sep 2010 05:24:56 PST"))
      assertEquals(expected, SourceHeaders.instant(value), value);
    assertThrows(IllegalArgumentException.class, () -> SourceHeaders.instant("Fri, 30 Sep 2010 13:24:56 GMT"));
    assertThrows(java.time.DateTimeException.class, () -> SourceHeaders.instant("30 Feb 2010 13:24:56 GMT"));
  }

  @ParameterizedTest
  @CsvSource({
    "validator-required-metadata/createdate-array.pdf, VALIDATION_INCOMPLETE, N/A",
    "validator-required-metadata/producer-array.pdf, VALIDATION_INCOMPLETE, N/A",
    "validator-active-userproperties/wrong-owner-after-valid.pdf, VALIDATION_INCOMPLETE, WARN",
    "validator-nonsmtp-source/bad-foaf.pdf, VALIDATION_INCOMPLETE, WARN",
    "validator-presence/creator-first-empty.pdf, VALIDATION_INCOMPLETE, WARN",
    "validator-xmp-records/asset-uri-size.pdf, VALIDATION_INCOMPLETE, N/A",
    "validator-uninspectable-source/msg-bad-xmp.pdf, MESSAGE_METADATA_MATCH_SOURCE, FAIL",
    "validator-art-usage/empty-standard-article.pdf, STRUCTURE_CORRESPONDENCE, PASS",
    "validator-file-requirements/associated-date-calendar.pdf, ASSOCIATED_FILE_MODDATE, FAIL",
    "validator-local-decisions/contents-empty.pdf, ANNOTATION_CONTENTS_REQUIRED, PASS",
    "validator-profile-source-scope/ambiguous-invalid-nfsm.pdf, PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS, FAIL",
    "validator-profile-source-scope/ambiguous-mixed-attachments-mode.pdf, PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS, WARN",
    "validator-dpart-object-ranges/multi-overlapping.pdf, MAIL_CONTENTSET_ART_ONLY, WARN",
    "validator-dpart-object-ranges/multi-overlapping.pdf, MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT, WARN",
    "validator-attachment-owners/message-id-only-native-alias.pdf, ATTACHMENT_XMP_SIZE_MATCH, PASS",
    "validator-attachment-owners/message-id-only.pdf, ATTACHMENT_XMP_SIZE_MATCH, PASS",
    "validator-attachment-owners/wrong-owner.pdf, ATTACHMENT_ANNOTATIONS_PRESENT, FAIL",
    "validator-media-parameters/malformed-source-repaired.pdf, ATTACHMENT_XMP_CONTENTTYPE_MATCH, FAIL",
    "validator-title-language/empty-default.pdf, XMP_TITLE, PASS",
    "validator-title-language/qualified-default.pdf, XMP_TITLE, PASS",
    "validator-source-date-availability/utc-overflow.pdf, MESSAGE_RAW_SENT_REQUIRED_IF_INVALID, WARN",
    "validator-coverage-branches/url-linked.pdf, ACTIONABLE_URLS_LINK_ANNOTATIONS, PASS",
    "validator-coverage-branches/url-unlinked.pdf, ACTIONABLE_URLS_LINK_ANNOTATIONS, WARN",
    "validator-dpm-guid-decoding/valid-utf8.pdf, DPM_MAIL_GUID_MATCH, PASS",
    "validator-dpm-guid-decoding/unreadable-utf8.pdf, DPM_MAIL_GUID_MATCH, WARN",
    "validator-dpm-guid-decoding/utf16-decoded-empty.pdf, DPM_MAIL_GUID_PRESENT, FAIL",
    "validator-dpm-guid-decoding/readable-mismatch.pdf, DPM_MAIL_GUID_MATCH, FAIL",
    "validator-foaf-mailbox-uris/encoded-resource.pdf, MESSAGE_TO_FOAF, PASS",
    "validator-foaf-mailbox-uris/quoted-local.pdf, MESSAGE_TO_FOAF, PASS",
    "validator-foaf-mailbox-uris/local-case.pdf, MESSAGE_METADATA_MATCH_SOURCE, FAIL",
    "validator-foaf-mailbox-uris/double-encoded.pdf, MESSAGE_METADATA_MATCH_SOURCE, FAIL",
    "validator-foaf-mailbox-uris/invalid-utf8.pdf, MESSAGE_TO_FOAF, FAIL",
    "validator-active-userproperties/later-array-replacement.pdf, MAIL_MESSAGE_GUID_MATCH_DPM_XMP, PASS",
    "validator-dpart-entries/node-name-space.pdf, DPART_OBJECT_STRUCTURE, FAIL",
    "validator-dpart-entries/record-level-boolean.pdf, DPART_OBJECT_STRUCTURE, FAIL",
    "validator-email-schema/sent-extended-year.pdf, MESSAGE_SENT_FORMAT, PASS",
    "validator-email-schema/sent-no-timezone.pdf, MESSAGE_SENT_FORMAT, PASS",
    "validator-email-schema/sent-impossible-date.pdf, MESSAGE_SENT_FORMAT, FAIL",
    "validator-local-decisions/reply-bag.pdf, MESSAGE_INREPLYTO_XMP, PASS",
    "validator-local-decisions/reply-seq.pdf, MESSAGE_INREPLYTO_XMP, FAIL",
    "upstream-issues/mcaf-unlisted.pdf, AF_IN_NAME_TREE, FAIL",
    "validator-subject-encoding/valid-encoded.pdf, MESSAGE_METADATA_MATCH_SOURCE, PASS",
    "validator-subject-encoding/valid-different.pdf, MESSAGE_METADATA_MATCH_SOURCE, FAIL",
    "validator-subject-encoding/malformed-b-repaired.pdf, MESSAGE_METADATA_MATCH_SOURCE, WARN",
    "validator-subject-encoding/unknown-charset.pdf, MESSAGE_METADATA_MATCH_SOURCE, WARN",
    "validator-subject-encoding/required-subject-omitted.pdf, MESSAGE_CORE_FIELDS_SOURCE_REQUIRED, FAIL",
    "validator-subject-encoding/supplied-malformed.pdf, MESSAGE_METADATA_MATCH_SOURCE, FAIL",
    "validator-utf8-source-headers/subject-utf8.pdf, MESSAGE_METADATA_MATCH_SOURCE, PASS",
    "validator-attachment-occurrences/placeholder-empty-checksum.pdf, ATTACHMENT_XMP_SIZE_MATCH, PASS",
    "validator-attachment-occurrences/wrong-retained-size.pdf, ATTACHMENT_XMP_SIZE_MATCH, FAIL",
    "validator-attachment-streams/valid.pdf, ATTACHMENT_XMP_SIZE_MATCH, PASS",
    "validator-attachment-streams/wrong-checksum.pdf, ATTACHMENT_XMP_CHECKSUM_MATCH, FAIL",
    "validator-attachment-streams/wrong-size.pdf, ATTACHMENT_XMP_SIZE_MATCH, FAIL",
    "validator-attachment-streams/ambiguous-records.pdf, ATTACHMENT_XMP_SIZE_MATCH, FAIL",
    "validator-guid-decoding/unreadable-guid.pdf, MAIL_MESSAGE_GUID_MATCH_DPM_XMP, WARN",
    "validator-property-value-diagnostics/guid-array-value.pdf, MAIL_MESSAGE_GUID_MATCH_DPM_XMP, WARN",
    "validator-attachment-identity/qualified.pdf, ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID, PASS",
    "validator-attachment-identity/uri.pdf, ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID, FAIL",
    "validator-attachment-scope/member-name-collision-c.pdf, ATTACHMENT_XMP_SIZE_MATCH, N/A",
    "validator-attachment-scope/member-name-collision-ci.pdf, ATTACHMENT_XMP_SIZE_MATCH, N/A",
    "validator-xmp-records/asset-type-alias.pdf, EMBEDDED_MEDIA_TYPE_MATCH_XMP, PASS",
    "validator-property-value-diagnostics/guid-array-value.pdf, MAIL_MESSAGE_GUID_ATTRIBUTE, PASS",
    "validator-property-name-decoding/array-as-property-name.pdf, MAIL_MESSAGE_GUID_ATTRIBUTE, FAIL",
    "validator-dpart-object-ranges/leaf-stream-preserved.pdf, PAGE_DPART_PRESENT, FAIL",
    "validator-empty-dpm-names/missing-required-subtype.pdf, DPM_BODY_MEDIA_TYPE_MATCH_SOURCE, WARN",
    "validator-source-date-availability/negative-zero-wrong.pdf, MESSAGE_METADATA_MATCH_SOURCE, FAIL",
    "validator-source-date-availability/wide-offset-wrong.pdf, MESSAGE_METADATA_MATCH_SOURCE, FAIL",
    "validator-collection-child-status/normal-1c-failed.pdf, COLLECTION_CHILD_VALIDATION, FAIL",
    "validator-attachment-evidence/alternative-annotation.pdf, ATTACHMENT_ANNOTATIONS_PRESENT, FAIL",
    "validator-attachment-filenames/renamed-wrong-type.pdf, ATTACHMENT_ANNOTATIONS_PRESENT, FAIL",
    "validator-attachment-filenames/stored-name-no-checksum.pdf, ATTACHMENT_ANNOTATIONS_PRESENT, PASS",
    "validator-attachment-owner-mid/outside-annotations.pdf, ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX, FAIL",
    "validator-attachment-owners/message-id-only.pdf, ATTACHMENT_ANNOTATIONS_PRESENT, PASS",
    "validator-blank-source-date/blank-no-raw.pdf, MESSAGE_RAW_SENT_REQUIRED_IF_INVALID, FAIL",
    "validator-source-date-availability/invalid-calendar.pdf, MESSAGE_RAW_SENT_REQUIRED_IF_INVALID, FAIL",
    "validator-collection-names/folder-case.pdf, COLLECTION_FILENAMES_UNIQUE_CASEFOLD, FAIL",
    "validator-collection-names/implicit-root-alias.pdf, COLLECTION_FILENAMES_UNIQUE_CASEFOLD, FAIL"
  })
  void agreesWithWebEngineForRepairedChecks(String file, String code, String expected) {
    var result = JavaValidation.validate(Path.of("..", "tests", "fixtures", "eapdf").resolve(file));
    Map<String, Integer> rank = Map.of("MISSING", -1, "N/A", 0, "PASS", 1, "WARN", 2, "FAIL", 3);
    String actual = result.getAsJsonArray("items").asList().stream().map(value -> value.getAsJsonObject())
        .filter(item -> code.equals(item.get("code").getAsString()))
        .map(item -> item.get("level").getAsString()).max(java.util.Comparator.comparingInt(rank::get)).orElse("MISSING");
    assertEquals(expected, actual, file + " " + code);
  }
}
