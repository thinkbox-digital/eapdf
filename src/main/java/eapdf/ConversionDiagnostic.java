package eapdf;

import com.google.gson.JsonObject;

/** Stable diagnostic codes; text describes effects without claiming full preservation. */
record ConversionDiagnostic(Code code, String source, String subject, String messageId,
                            String mailGuid, String details) {
  enum Code {
    SEARCH_INDEX_UNAVAILABLE("The optional metadata index was not embedded.", "Metadata search remains available through child PDF inspection."),
    SOURCE_INCOMPLETE("Some source content could not be recovered.", "Keep the original and review the missing content before accepting the PDF."),
    TEXT_RECOVERED("Displayed text was recovered using a fallback; characters may differ.", "Compare the affected text with the original message."),
    DATE_PRECISION_REDUCED("The reconstructed date retains whole seconds; native fractional seconds are lost.", "Keep the original container if subsecond timing matters."),
    BODY_FALLBACK("An available body or plain-text alternative was used; appearance may differ.", "Compare the PDF body with the original message."),
    SIGNATURE_UNVERIFIED("The email signature was not verified.", "Verify the original email signature separately if authenticity is required."),
    ATTACHMENT_REFERENCE_UNRESOLVED("An attachment reference could not be resolved reliably; inline display may be incomplete.", "Check the affected attachment and its position in the original email."),
    ATTACHMENT_TYPE_RECOVERED("An attachment's declared type disagrees with the available content.", "Check the attachment with software that supports its actual format."),
    HEADER_CONFLICT("Source header values disagree; the warning identifies the value used.", "Review the conflicting source values before relying on the displayed header."),
    RENDERING_REDUCED("The displayed representation has a rendering limitation.", "Compare the affected content with the original; retain the original."),
    REVIEW_REQUIRED("The warning describes a conversion limitation that needs review.", "Review the warning and compare the affected content with the original.");

    final String effect, action;
    Code(String effect, String action) { this.effect = effect; this.action = action; }
  }

  JsonObject toJson() {
    JsonObject json = new JsonObject();
    json.addProperty("code", code.name());
    json.addProperty("severity", "WARNING");
    json.addProperty("source", source);
    json.addProperty("subject", subject);
    json.addProperty("message_id", messageId);
    json.addProperty("mail_guid", mailGuid);
    json.addProperty("details", details);
    json.addProperty("effect", code.effect);
    json.addProperty("suggested_action", code.action);
    return json;
  }
}
