package eapdf;

import java.nio.*;
import java.nio.file.*;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RtfExpansionLimitTests {
    @TempDir Path temporary;
    private static byte[] header(int size, int magic) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(12).putInt(size).putInt(magic).putInt(0).array();
    }
    @Test void compressedRtfSizeIsCheckedBeforeLibraryAllocation() throws Exception {
        byte[] data = header(Integer.MAX_VALUE, 0x75465a4c);
        assertThrows(java.io.IOException.class, () -> com.pff.EaPdfPstProperties.checkRtfHeader(data, 16, 2048));
        assertThrows(java.io.IOException.class, () -> com.pff.EaPdfPstProperties.checkRtfHeader(header(-1, 0x75465a4c), 16, 2048));
        assertThrows(java.io.IOException.class, () -> com.pff.EaPdfPstProperties.checkRtfHeader(header(1, 0x414c454d), 8192, 2048));
        com.pff.EaPdfPstProperties.checkRtfHeader(header(1024, 0x75465a4c), 16, 2048);
    }
    @Test void msgPreflightIncludesNestedEmbeddedMessageStorages() throws Exception {
        Path file = temporary.resolve("rtf-bomb.msg");
        try (var storage = new POIFSFileSystem(); var output = Files.newOutputStream(file)) {
            storage.getRoot().createDirectory("__attach_version1.0_#00000000")
                .createDirectory("__substg1.0_3701000D")
                .createDocument("__substg1.0_10090102", new java.io.ByteArrayInputStream(header(Integer.MAX_VALUE, 0x75465a4c)));
            storage.writeFilesystem(output);
        }
        var error = assertThrows(java.io.IOException.class, () -> MsgReader.checkCompressedRtf(file));
        assertTrue(error.getMessage().contains("RTF expansion"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "TxtSampleEmail.msg", "HtmlSampleEmail.msg", "RtfSampleEmail.msg",
        "EmailWithReactions.msg", "EmailWithSpecialCharsInSubject.msg", "EmailWithSpecialCharsInSubject_2.msg"
    })
    void realNamedPropertyMappingsAreNotCompressedRtf(String name) throws Exception {
        Path source = Path.of("in", name);
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(source));
        assertDoesNotThrow(() -> MsgReader.checkCompressedRtf(source));
    }
    @Test void mappingCollisionDoesNotBypassRealBodyChecks() throws Exception {
        Path file = temporary.resolve("named-properties.msg");
        try (var storage = new POIFSFileSystem(); var output = Files.newOutputStream(file)) {
            storage.getRoot().createDirectory("__nameid_version1.0")
                .createDocument("__substg1.0_10090102", new java.io.ByteArrayInputStream(new byte[8]));
            storage.getRoot().createDocument("__substg1.0_10090102",
                new java.io.ByteArrayInputStream(header(Integer.MAX_VALUE, 0x75465a4c)));
            storage.writeFilesystem(output);
        }
        var error = assertThrows(java.io.IOException.class, () -> MsgReader.checkCompressedRtf(file));
        assertTrue(error.getMessage().contains("RTF expansion"));
    }
}
