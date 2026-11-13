package org.verapdf.pdfa.validation.validators;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.verapdf.as.ASAtom;
import org.verapdf.cos.COSDictionary;
import org.verapdf.cos.COSObject;
import org.verapdf.gf.model.impl.pd.GFPDContentStream;
import org.verapdf.pd.PDResourcesHandler;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.validation.profiles.*;
import static org.junit.jupiter.api.Assertions.*;

class DeferredResourceCheckTests {
    private static final class RawStream extends org.verapdf.pd.PDObject implements org.verapdf.pd.PDContentStream {
        RawStream() { super(COSDictionary.construct()); }
        public COSObject getContents() { return getObject(); }
        public void setContents(COSObject value) { throw new UnsupportedOperationException(); }
    }
    private static Rule resourceRule(int number) {
        return Profiles.getVeraProfileDirectory().getValidationProfileByFlavour(PDFAFlavour.PDFA_4_F)
            .getRulesByObject("PDContentStream").stream()
            .filter(r -> r.getRuleId().getClause().equals("6.2.2") && r.getRuleId().getTestNumber() == number)
            .findFirst().orElseThrow();
    }
    @Test void deferredViewsKeepLateDiscoveredMissingResourcesAndErrorArguments() {
        var handler = PDResourcesHandler.getInstance(new org.verapdf.pd.PDResources(COSDictionary.construct()), false);
        var stream = new GFPDContentStream(new RawStream(), handler, null, null, null);
        var scope = JavaScriptEvaluator.initialise();
        try {
            for (int number : List.of(2, 3)) {
                Rule rule = resourceRule(number);
                var view = BaseValidator.deferredCheckObject(stream, rule);
                assertInstanceOf(GFPDContentStream.ResourceCheckView.class, view);
                assertEquals(stream.getContext(), view.getContext());
                assertTrue(JavaScriptEvaluator.getTestEvalResult(view, rule, scope));
                var names = number == 2 ? handler.getInheritedResourceNames() : handler.getUndefinedResourceNames();
                names.add(ASAtom.getASAtom("MissingFont")); // Discovered after the deferred view was created.
                assertFalse(JavaScriptEvaluator.getTestEvalResult(view, rule, scope));
                assertEquals(JavaScriptEvaluator.getTestEvalResult(stream, rule, scope),
                    JavaScriptEvaluator.getTestEvalResult(view, rule, scope));
                JavaScriptEvaluator.getTestEvalResult(stream, rule, scope);
                var expected = JavaScriptEvaluator.getErrorArgumentsResult(stream, rule.getError().getArguments(), scope);
                JavaScriptEvaluator.getTestEvalResult(view, rule, scope);
                var actual = JavaScriptEvaluator.getErrorArgumentsResult(view, rule.getError().getArguments(), scope);
                assertEquals(expected.getFirst().getArgumentValue(), actual.getFirst().getArgumentValue());
                assertEquals("MissingFont", actual.getFirst().getArgumentValue());
            }
        } finally { JavaScriptEvaluator.exitContext(); }
    }
    @Test void customDeferredRulesKeepTheOriginalModel() {
        var handler = PDResourcesHandler.getInstance(new org.verapdf.pd.PDResources(COSDictionary.construct()), false);
        var stream = new GFPDContentStream(new RawStream(), handler, null, null, null);
        Rule original = resourceRule(2);
        Rule custom = Profiles.ruleFromValues(original.getRuleId(), "PDContentStream", true, "", "custom",
            "operators_size > 0", original.getError(), List.of());
        assertSame(stream, BaseValidator.deferredCheckObject(stream, custom));
        var extraError = Profiles.errorFromValues("custom", List.of(
            ErrorArgumentImpl.fromValues("operators_size", "operators", "")));
        custom = Profiles.ruleFromValues(original.getRuleId(), "PDContentStream", true, "", "custom",
            original.getTest(), extraError, List.of());
        assertSame(stream, BaseValidator.deferredCheckObject(stream, custom));
    }
}
