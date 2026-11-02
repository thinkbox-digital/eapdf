package org.verapdf.pdfa.validation.validators;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextPathTests {
  @Test void preservesDiagnosticTextAndSharedAncestry() {
    var root = new BaseValidator.ContextPath(null, "root", 0, null, null);
    var parent = new BaseValidator.ContextPath(root, "indirectObjects", 17, "42 0", "scope");
    var child = new BaseValidator.ContextPath(parent, "entries", 0, null, "");
    assertEquals("root/indirectObjects[17](42 0){scope}/entries[0]{}", child.toString());
    assertEquals("root/indirectObjects[17](42 0){scope}", parent.toString());
    var deep = root;
    for (int i = 0; i < 10000; i++) deep = new BaseValidator.ContextPath(deep, "k", 0, null, null);
    assertEquals("root" + "/k[0]".repeat(10000), deep.toString());
  }
}
