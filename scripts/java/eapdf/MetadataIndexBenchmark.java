package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;

/** Scaling fixture only: repeat one public real email with distinct internal identities.
 * Reuses rendering; this is not a throughput benchmark of the email converter. */
public final class MetadataIndexBenchmark {
  public static void main(String[] args) throws Exception {
    Path source=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
    String sourceHash=Common.digestHex(source,"SHA-256"),font=Common.resolveFontPath(null),icc=Common.resolveIccPath(null);
    EmailRecord template=EmlReader.readEml(source.toString(),"",List.of(),false);template.embedSourceMessage=false;
    byte[] templatePdf=PdfMailWriter.buildSingleMessagePdf(output.resolve("template_1si.pdf"),template,font,icc,false,output,1,"si").pdfBytes;
    int[] counts=Arrays.stream(args).skip(2).mapToInt(Integer::parseInt).sorted().toArray();
    if(counts.length==0)counts=new int[]{100,1000,10000};
    var records=new ArrayList<EmailRecord>();var members=new ArrayList<PdfMailWriter.CollectionMember>();
    try(var store=new PayloadStore()){
      for(int number=1;number<=counts[counts.length-1];number++){
        ProcessingLimits.checkCancelled();
        EmailRecord record=EmlReader.readEml(source.toString(),"",List.of(),false);
        record.mailGuid=UUID.nameUUIDFromBytes((sourceHash+":"+number).getBytes(StandardCharsets.UTF_8)).toString();
        record.folderPath="Benchmark";record.embedSourceMessage=false;Common.finalizeRecordMetadata(record);
        Path child=store.createPdf();
        try(var pdf=Loader.loadPDF(templatePdf)){
          String xml=new String(pdf.getDocumentCatalog().getMetadata().toByteArray(),StandardCharsets.UTF_8);
          PdfMailWriter.setCompressedMetadata(pdf,xml.replace(template.mailGuid,record.mailGuid));
          replaceIdentity(pdf.getDocumentCatalog().getCOSObject(),template.mailGuid,record.mailGuid);
          pdf.save(child.toFile());
        }
        var member=new PdfMailWriter.CollectionMember("Benchmark",record.subject,"si",List.of(record),1,number,
            String.format(Locale.ROOT,"message_%05d_1si.pdf",number),null,record.attachmentCount,record.date);
        member.pdfPath=child;member.storedPdfSize=Math.toIntExact(Files.size(child));records.add(record);members.add(member);
        if(Arrays.binarySearch(counts,number)>=0){
          long started=System.nanoTime();
          var sections=PdfCollectionSupport.buildFolderSections(records);
          var front=PdfFindingAidSupport.buildCollectionFrontMatter("ci",PdfMailWriter.buildCollectionRunSummary(records,sections),font);
          try(var doc=front.document){
            doc.setVersion(2.0f);doc.getDocument().setVersion(2.0f);
            PdfMailWriter.setDocumentInfo(doc,records.get(0));
            PdfMailWriter.addOutlines(doc,PdfCollectionSupport.buildCollectionFrontRecord(records,"ci",members));
            PdfMailWriter.configureArchiveCatalog(doc,"ci");
            var folders=List.of(new PdfMailWriter.CollectionFolder("Benchmark",1,number));
            PdfCollectionSupport.addTopLevelCollectionDictionary(doc,folders);
            PdfCollectionSupport.addCollectionDPartStructure(doc,records,"ci");
            PdfCollectionSupport.addCollectionLogicalStructure(doc);
            PdfMailWriter.setPdfA3Metadata(doc,icc,records,"ci");
            PdfCollectionSupport.embedCollectionFiles(doc,records,members,false,true);
            PdfMailWriter.normalizePageContentStreams(doc);
            SemanticStructure.balanceParentTree(doc);
            Path indexed=output.resolve("eml_repeated_"+number+"_1ci.pdf");PdfFontMetadata.save(doc,indexed.toFile());
            String xml=new String(doc.getDocumentCatalog().getMetadata().toByteArray(),StandardCharsets.UTF_8);
            String stripped=xml.replaceAll("<rdf:Description rdf:about=\"\" xmlns:eapdfindex=\"https://thinkbox.digital/eapdf/search-index/\">.*?</rdf:Description>","");
            if(stripped.equals(xml))throw new IllegalStateException("Index was not generated");
            PdfMailWriter.setCompressedMetadata(doc,stripped);
            Path legacy=output.resolve("eml_repeated_"+number+"_legacy_1ci.pdf");PdfFontMetadata.save(doc,legacy.toFile());
            System.out.println("{\"messages\":"+number+",\"sourceSha256\":\""+sourceHash+"\",\"indexedBytes\":"+Files.size(indexed)+",\"legacyBytes\":"+Files.size(legacy)+",\"assemblyMs\":"+(System.nanoTime()-started)/1_000_000+"}");
          }
        }
      }
    }
    if(!sourceHash.equals(Common.digestHex(source,"SHA-256")))throw new IllegalStateException("Source changed");
  }
  static void replaceIdentity(COSBase root,String before,String after){
    var pending=new ArrayDeque<COSBase>();pending.add(root);var seen=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());
    while(!pending.isEmpty()){
      COSBase value=pending.removeFirst();if(value instanceof COSObject object)value=object.getObject();if(value==null||!seen.add(value))continue;
      if(value instanceof COSDictionary dict){
        for(COSName key:new ArrayList<>(dict.keySet())){COSBase item=dict.getDictionaryObject(key);
          if(item instanceof COSString text&&text.getString().contains(before))dict.setString(key,text.getString().replace(before,after));
          else if(item!=null)pending.add(item);
        }
      }else if(value instanceof COSArray array)for(int i=0;i<array.size();i++){COSBase item=array.getObject(i);
        if(item instanceof COSString text&&text.getString().contains(before))array.set(i,new COSString(text.getString().replace(before,after)));
        else if(item!=null)pending.add(item);
      }
    }
  }
}
