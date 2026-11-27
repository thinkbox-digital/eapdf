package eapdf;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.flavours.PDFAFlavour;

class DecodedObjectStreamCacheTests {
  @org.junit.jupiter.api.io.TempDir Path temporary;
  record Finding(String rule, String context) {}
  record Result(boolean compliant, long checks, int failures, java.util.List<Finding> findings) {}
  private Result validate(Path path,boolean bounded) throws Exception {
    // veraPDF retains both model caches and a Rhino context in ThreadLocals.
    // Use the same disposable-worker boundary as JavaValidation.pdfa(), so this
    // direct parser regression does not leak into an unrelated JUnit method.
    var task=new java.util.concurrent.FutureTask<Result>(() -> validateOnWorker(path,bounded));
    Thread worker=new Thread(task,"eapdf-test-upstream-check");
    worker.start();
    boolean interrupted=false;
    try {
      while(worker.isAlive())try { worker.join(); } catch(InterruptedException cancelled) { interrupted=true;worker.interrupt(); }
      if(interrupted)throw new InterruptedException("Upstream validation test was interrupted.");
      try { return task.get(); }
      catch(java.util.concurrent.ExecutionException failed) {
        if(failed.getCause() instanceof Exception error)throw error;
        if(failed.getCause() instanceof Error error)throw error;
        throw new IllegalStateException("Upstream validation test failed",failed.getCause());
      } catch(InterruptedException cancelled) { interrupted=true;throw cancelled; }
    } finally { if(interrupted)Thread.currentThread().interrupt(); }
  }
  private Result validateOnWorker(Path path,boolean bounded) throws Exception {
    JavaValidation.initialise();var foundry=Foundries.defaultInstance();
    try(var parser=foundry.createParser(path.toFile(),PDFAFlavour.NO_FLAVOUR);
        var validator=foundry.createValidator(parser.getFlavour(),100,false,true,false)) {
      // A one-byte budget forces byte-based eviction while allowing a single
      // oversized stream to stay open for the current object.
      var cache=bounded?DecodedObjectStreamCache.install(parser,1024,1):null;
      BoundedGlyphCache.install();var result=validator.validate(parser);
      if(cache!=null){
        assertEquals(1, cache.size());
        assertTrue(cache.evictions > 0, "Real fixture must exercise byte-based eviction");
        assertTrue(cache.peakBytes() > 1);
      }
      var findings = result.getTestAssertions().stream()
          .map(assertion -> new Finding(assertion.getRuleId().toString(), assertion.getLocationContext()))
          .toList();
      return new Result(result.isCompliant(),result.getTotalAssertions(),result.getFailedChecks().size(), findings);
    }
  }
  @Test void evictedStreamsCanBeReopenedWithoutChangingRealPdfaResults() throws Exception {
    for(String source:java.util.List.of("../examples/pst/pst_quality-attached-message_1m.pdf","../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf")) {
      // Repack a copy of real content with small object streams to force eviction;
      // originals and their embedded source bytes remain unchanged.
      var path=temporary.resolve(Path.of(source).getFileName());
      try(var document=org.apache.pdfbox.Loader.loadPDF(Path.of(source).toFile())) {
        document.save(path.toFile(),new org.apache.pdfbox.pdfwriter.compress.CompressParameters(10));
      }
      var original=validate(path,false);assertTrue(original.compliant());
      assertEquals(original,validate(path,true));
    }
  }
  @Test void missingEmbeddedFontStillFailsWithTheSameAssertions() throws Exception {
    var path=temporary.resolve("missing-font.pdf");boolean changed=false;
    try(var document=org.apache.pdfbox.Loader.loadPDF(Path.of("../examples/pst/pst_quality-attached-message_1m.pdf").toFile())) {
      for(var page:document.getPages()) {
        if(changed)break;
        for(var name:page.getResources().getFontNames()) {
          var descriptor=page.getResources().getFont(name).getFontDescriptor();
          if(descriptor!=null&&(descriptor.getFontFile()!=null||descriptor.getFontFile2()!=null||descriptor.getFontFile3()!=null)) {
            for(var key:java.util.List.of(org.apache.pdfbox.cos.COSName.FONT_FILE,org.apache.pdfbox.cos.COSName.FONT_FILE2,org.apache.pdfbox.cos.COSName.FONT_FILE3))descriptor.getCOSObject().removeItem(key);
            changed=true;break;
          }
        }
      }
      assertTrue(changed);document.save(path.toFile(),new org.apache.pdfbox.pdfwriter.compress.CompressParameters(10));
    }
    var original=validate(path,false);assertFalse(original.compliant());assertTrue(original.failures()>0);
    assertEquals(original,validate(path,true));
  }
}
