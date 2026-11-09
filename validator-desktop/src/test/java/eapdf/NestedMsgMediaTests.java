package eapdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NestedMsgMediaTests {
    private byte[] message(String body) throws Exception {
        try (var storage = new POIFSFileSystem(); var out = new ByteArrayOutputStream()) {
            storage.getRoot().createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[32]));
            storage.getRoot().createDocument("__substg1.0_1000001F", new ByteArrayInputStream((body+"\0").getBytes(StandardCharsets.UTF_16LE)));
            storage.writeFilesystem(out); return out.toByteArray();
        }
    }
    private Object original(String type, long size, String md5, Map<String,String> content) throws Exception {
        Class<?> record = Class.forName("eapdf.MetadataChecks$NativeAttachment");
        var constructor = record.getDeclaredConstructor(List.class,String.class,long.class,String.class,Map.class);
        constructor.setAccessible(true);return constructor.newInstance(List.of("attached.msg"),type,size,md5,content);
    }
    @SuppressWarnings("unchecked")
    private List<String> match(byte[] stored, Object... originals) throws Exception {
        String md5 = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(stored));
        var file = new EaPdfChecks.Embedded("attached.msg",new COSDictionary(),null,stored,"attached.msg","application/vnd.ms-outlook",md5,"Mail_Attachment");
        var method = MetadataChecks.class.getDeclaredMethod("matchedOriginalMediaTypes",EaPdfChecks.Embedded.class,List.class);
        method.setAccessible(true);return (List<String>)method.invoke(null,file,List.of(originals));
    }
    @Test void structuredMsgCanBeAssociatedWithoutAStandaloneSizeOrChecksum() throws Exception {
        byte[] stored=message("Preserved body");
        var original=original("application/vnd.ms-outlook",-1,"",OutlookSource.standaloneContent(stored));
        assertEquals(List.of("application/vnd.ms-outlook"),match(stored,original));
        assertTrue(match(message("Changed body"),original).isEmpty(),"Matching names cannot hide altered message content");
        assertTrue(match(new byte[]{1,2,3},original).isEmpty(),"Unreadable structured content cannot pass");
    }
    @Test void conflictingDeclarationsStayAmbiguousAndIdenticalDeclarationsCoalesce() throws Exception {
        byte[] stored=message("Same body");var content=OutlookSource.standaloneContent(stored);
        var one=original("application/vnd.ms-outlook",-1,"",content);
        var two=original("application/vnd.ms-outlook; test=other",-1,"",content);
        assertEquals(2,match(stored,one,two).size());
        assertEquals(List.of("application/vnd.ms-outlook"),match(stored,one,one));
    }
    @Test void byValueAttachmentsStillRequireExactSizeAndChecksum() throws Exception {
        byte[] bytes={1,2,3};String md5=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
        assertEquals(List.of("application/octet-stream"),match(bytes,original("application/octet-stream",3,md5,Map.of())));
        assertTrue(match(bytes,original("application/octet-stream",4,md5,Map.of())).isEmpty());
        assertTrue(match(bytes,original("application/octet-stream",3,"wrong",Map.of())).isEmpty());
    }
}
