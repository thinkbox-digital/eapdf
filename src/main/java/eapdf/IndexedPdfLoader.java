/*
 * Object-stream loading adapted from Apache PDFBox 3.0.8 COSParser.
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eapdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;

/** PDFBox 3.0.8 object-stream parsing with one canonical object-key index per document. */
final class IndexedPdfLoader {
  static PDDocument load(Path path) throws IOException {
    return load(path, null);
  }

  static PDDocument load(Path path, org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction cache) throws IOException {
    RandomAccessRead source = new RandomAccessReadBufferedFile(path.toFile());
    try { return new Parser(source, cache).parse(); }
    catch (IOException | RuntimeException | Error failure) {
      try { source.close(); } catch (IOException close) { failure.addSuppressed(close); }
      throw failure;
    }
  }

  private static final class Parser extends PDFParser {
    private final Map<Long, Map<COSObjectKey, COSBase>> pendingObjects = new HashMap<>();
    private ObjectKeyIndex objectKeys;
    @Override protected COSObjectKey getObjectKey(long number,int generation) {
      if(objectKeys==null)objectKeys=new ObjectKeyIndex();
      return objectKeys.get(document.getXrefTable(),number,generation);
    }
    Parser(RandomAccessRead source, org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction cache) throws IOException { super(source, "", null, null, cache); }

    @Override protected org.apache.pdfbox.cos.COSDictionary parseCOSDictionary(boolean direct) throws IOException {
      return compact(super.parseCOSDictionary(direct));
    }
    private static org.apache.pdfbox.cos.COSDictionary compact(org.apache.pdfbox.cos.COSDictionary original) {
      var result = new CompactPdfDictionary(original.size());
      for (var entry : original.entrySet()) result.setItem(entry.getKey(), entry.getValue());
      result.setDirect(original.isDirect());
      return result;
    }

    // Keep PDFBox's parsing, stream-index handling, lazy references and leniency.
    // Only key lookup changes: its short-lived object-stream parsers otherwise
    // each copy the entire cross-reference table into their own key cache.
    @Override protected COSBase parseObjectStreamObject(long streamNumber, COSObjectKey key) throws IOException {
      Map<COSObjectKey, COSBase> pending = pendingObjects.computeIfAbsent(streamNumber, ignored -> new HashMap<>());
      COSBase result = pending.remove(key);
      if (result != null) return result;
      COSBase value = document.getObjectFromPool(getObjectKey(streamNumber, 0)).getObject();
      if (value instanceof COSStream stream) {
        try {
          PDFObjectStreamParser parser = new PDFObjectStreamParser(stream, document) {
            @Override protected org.apache.pdfbox.cos.COSDictionary parseCOSDictionary(boolean direct) throws IOException {
              return compact(super.parseCOSDictionary(direct));
            }
            @Override protected COSObjectKey getObjectKey(long number, int generation) {
              return Parser.this.getObjectKey(number, generation);
            }
          };
          Map<COSObjectKey, COSBase> parsed = parser.parseAllObjects();
          result = parsed.remove(key);
          parsed.forEach(pending::putIfAbsent);
        } catch (IOException error) {
          if (!isLenient()) throw error;
          LogFactory.getLog(org.apache.pdfbox.pdfparser.COSParser.class)
              .error("object stream " + streamNumber + " could not be parsed due to an exception", error);
        }
      }
      return result;
    }
  }
}
