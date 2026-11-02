package org.verapdf.pdfa.validation.validators;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpillableIdSetTests {
    @TempDir Path temporary;
    @Test void matchesExactMembershipThroughSpillCollisionsAndResizing() throws Exception {
        Set<String> expected = new HashSet<>();
        try (var actual = new SpillableIdSet(3, temporary)) {
            List<String> values = new ArrayList<>(List.of("", "Aa", "BB", "\0\u0840", "AaAa", "BBBB",
                "\u007f", "\u0080", "ab\0cd", "\ud800", "\ud801", "?", "\ud83d\ude00"));
            var random = new Random(42);
            for (int i = 0; i < 20000; i++) values.add("id-" + random.nextInt(16000));
            for (String value : values) assertEquals(expected.add(value), actual.add(value), value);
            assertEquals(expected.size(), actual.size());
            for (String value : values) assertTrue(actual.contains(value));
            assertFalse(actual.contains("not-present")); assertFalse(actual.contains(null));
            assertEquals(expected, new HashSet<>(actual));
            actual.clear(); assertTrue(actual.isEmpty()); assertFalse(actual.contains("Aa"));
            for (String value : values) actual.add(value);
            assertEquals(expected, new HashSet<>(actual));
        }
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }
    @Test void preservesKeysAcrossMappedSegmentBoundaries() throws Exception {
        for (String character : List.of("x", "\u0100")) {
            String large = character.repeat(8 * 1024 * 1024 + 1);
            try (var actual = new SpillableIdSet(1, temporary)) {
                actual.add("first"); actual.add(large); actual.add(large + "a");
                assertTrue(actual.contains(large)); assertTrue(actual.contains(large + "a"));
                assertFalse(actual.contains(large + "b"));
                assertEquals(Set.of("first", large, large + "a"), new HashSet<>(actual));
            }
            try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
        }
    }
    @Test void storageFailureNeverPretendsAnUnknownIdWasAlreadyChecked() {
        try (var actual = new SpillableIdSet(1, temporary.resolve("missing"))) {
            actual.add("first");
            assertThrows(java.io.UncheckedIOException.class, () -> actual.add("second"));
        }
    }
    @Test void exactMembershipAndIterationSurviveRepeatedRandomLookupsAndClears() throws Exception {
        List<String> values = new ArrayList<>(List.of("", "Aa", "BB", "\0\u0840", "\ud800", "\ud83d\ude00"));
        for (int i = 0; i < 1000; i++) values.add("value-" + i + (i % 3 == 0 ? "中文" : ""));
        Set<String> expected = new HashSet<>(values);
        try (var actual = new SpillableIdSet(1, temporary)) {
            for (int pass = 0; pass < 2; pass++) {
                for (String value : values) actual.add(value);
                Collections.shuffle(values, new Random(42));
                for (String value : values) assertTrue(actual.contains(value));
                assertFalse(actual.contains("absent"));
                assertEquals(expected, new HashSet<>(actual));
                actual.clear(); assertTrue(actual.isEmpty());
            }
        }
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }
}
