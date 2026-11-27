package eapdf.viewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** File identity and change time for work that reuses parsed PDF data. */
record PdfFileStamp(Object key, long size, FileTime modified, FileTime changed) {
    static PdfFileStamp read(Path path) {
        try {
            var fields = Files.readAttributes(path, "unix:fileKey,size,lastModifiedTime,ctime");
            var key = fields.get("fileKey");
            var time = (FileTime) fields.get("ctime");
            return key == null || time == null ? null
                    : new PdfFileStamp(key, (Long) fields.get("size"), (FileTime) fields.get("lastModifiedTime"), time);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException unavailable) {
            return null;
        }
    }
}
