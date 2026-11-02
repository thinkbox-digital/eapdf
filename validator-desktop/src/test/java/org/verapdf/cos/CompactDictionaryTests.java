package org.verapdf.cos;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.verapdf.as.ASAtom;
import static org.junit.jupiter.api.Assertions.*;

class CompactDictionaryTests {
    private static COSObject value(int n) { return COSInteger.construct(n); }

    private static void same(COSDictionary actual, Map<ASAtom, COSObject> expected) {
        assertEquals(expected.size(), actual.size());
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.getKeySet()));
        assertEquals(new ArrayList<>(expected.values()), new ArrayList<>(actual.getValues()));
        assertEquals(expected.entrySet(), actual.getEntrySet());
        for (var entry : expected.entrySet()) assertSame(entry.getValue(), actual.getKey(entry.getKey()));
    }

    @Test void smallAndExpandedMapsMatchHashMapOrderAndCanonicalValues() {
        var keys = Arrays.asList(null, ASAtom.getASAtom("Aa"), ASAtom.getASAtom("BB"),
                ASAtom.TYPE, ASAtom.SUBTYPE, ASAtom.getASAtom("alpha"));
        var random = new Random(47321);
        for (int trial = 0; trial < 200; trial++) {
            var actual = new COSDictionary();
            var expected = new HashMap<ASAtom, COSObject>();
            int limit = trial % 2 == 0 ? 3 : keys.size();
            for (int step = 0; step < 100; step++) {
                ASAtom key = keys.get(random.nextInt(limit));
                if (random.nextInt(4) == 0) { actual.removeKey(key); expected.remove(key); }
                else { var value = value(step); actual.setKey(key, value); expected.put(key, value); }
                same(actual, expected);
            }
        }
    }

    @Test void copiesAndPreviouslyAcquiredViewsRemainLiveAcrossPromotion() {
        var original = new COSDictionary();
        original.setIntegerKey(ASAtom.TYPE, 1);
        var alias = new COSDictionary(original);
        var keys = original.getKeySet();
        var values = alias.getValues();
        var entries = alias.getEntrySet();
        var oldEntry = entries.iterator().next();
        alias.setIntegerKey(ASAtom.SUBTYPE, 2);
        original.setIntegerKey(ASAtom.getASAtom("third"), 3);
        alias.setIntegerKey(ASAtom.getASAtom("fourth"), 4);
        assertEquals(4, keys.size()); assertEquals(4, values.size());
        var replacement = value(17); oldEntry.setValue(replacement);
        assertSame(replacement, original.getKey(ASAtom.TYPE));
        assertSame(replacement, alias.getKey(ASAtom.TYPE));
        keys.remove(ASAtom.SUBTYPE);
        assertFalse(alias.knownKey(ASAtom.SUBTYPE));
        var iterator = entries.iterator(); var removed = iterator.next().getKey(); iterator.remove();
        assertFalse(original.knownKey(removed));
        values.clear(); assertEquals(0, original.size()); assertEquals(0, alias.size());
    }

    @Test void inlineViewsSupportMutationAndFailFastIteration() {
        var actual = new COSDictionary(); actual.setIntegerKey(ASAtom.TYPE, 1);
        actual.setIntegerKey(ASAtom.SUBTYPE, 2);
        var iterator = actual.getEntrySet().iterator();
        var entry = iterator.next(); var replacement = value(7);
        entry.setValue(replacement); assertSame(replacement, actual.getKey(entry.getKey()));
        iterator.remove(); assertFalse(actual.knownKey(entry.getKey()));
        assertThrows(IllegalStateException.class, iterator::remove);
        var stale = actual.getKeySet().iterator();
        actual.setIntegerKey(ASAtom.getASAtom("another"), 3);
        assertThrows(ConcurrentModificationException.class, stale::next);
        actual.getValues().clear(); assertEquals(0, actual.size());
    }
}
