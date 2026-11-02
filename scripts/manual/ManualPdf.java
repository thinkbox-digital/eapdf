package eapdf;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.xml.XmpSerializer;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.processor.reports.enums.JobEndStatus;
import com.google.gson.*;

/** Documentation-only PDF/A-2b finishing and independent veraPDF validation. */
public final class ManualPdf {
    public static void main(String[] args) throws Exception {
        Path input=Path.of(args[0]), output=Path.of(args[1]), icc=Path.of(args[2]), report=Path.of(args[3]);
        try(var document=Loader.loadPDF(input.toFile())) {
            var catalog=document.getDocumentCatalog();catalog.setLanguage("en-US");
            for(var page:document.getPages())for(var annotation:page.getAnnotations())annotation.setAnnotationFlags(4);
            var info=document.getDocumentInformation();
            var metadata=XMPMetadata.createXMPMetadata();
            var identification=metadata.createAndAddPDFAIdentificationSchema();identification.setPart(2);identification.setConformance("B");
            var dc=metadata.createAndAddDublinCoreSchema();dc.setTitle(info.getTitle());dc.addCreator(info.getAuthor());dc.setDescription(info.getSubject());dc.setFormat("application/pdf");
            var basic=metadata.createAndAddXMPBasicSchema();basic.setCreatorTool(info.getCreator());basic.setCreateDate(info.getCreationDate());basic.setModifyDate(info.getModificationDate());
            var pdf=metadata.createAndAddAdobePDFSchema();pdf.setProducer(info.getProducer());pdf.setKeywords(info.getKeywords());
            var bytes=new ByteArrayOutputStream();new XmpSerializer().serialize(metadata,bytes,true);
            var xmp=new PDMetadata(document);xmp.importXMPMetadata(bytes.toByteArray());catalog.setMetadata(xmp);
            try(var stream=Files.newInputStream(icc)) {
                var intent=new PDOutputIntent(document,stream);intent.setInfo("sRGB IEC61966-2.1");intent.setOutputCondition("sRGB IEC61966-2.1");intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");intent.setRegistryName("http://www.color.org");catalog.setOutputIntents(List.of(intent));
            }
            document.setDocumentId(1793404800000L);document.save(output.toFile());
        }
        VeraGreenfieldFoundryProvider.initialise();var foundry=Foundries.defaultInstance();var resultJson=new JsonObject();
        try(var parser=foundry.createParser(output.toFile(),PDFAFlavour.PDFA_2_B);
            var validator=foundry.createValidator(parser.getFlavour(),100,false,true,false)) {
            var result=validator.validate(parser);
            boolean passed=result.isCompliant()&&result.getJobEndStatus()==JobEndStatus.NORMAL;
            resultJson.addProperty("status",passed?"PASS":"FAIL");resultJson.addProperty("profile",result.getProfileDetails().getName());resultJson.addProperty("checks",result.getTotalAssertions());resultJson.addProperty("failed_rules",result.getFailedChecks().size());resultJson.addProperty("job_end_status",result.getJobEndStatus().getValue());
            var failures=new JsonArray();
            for(var assertion:result.getTestAssertions())if(assertion.getStatus()!=org.verapdf.pdfa.results.TestAssertion.Status.PASSED){
                var item=new JsonObject();item.addProperty("clause",assertion.getRuleId().getClause());item.addProperty("test",assertion.getRuleId().getTestNumber());item.addProperty("message",assertion.getMessage());item.addProperty("error",assertion.getErrorMessage());item.addProperty("context",assertion.getLocationContext());failures.add(item);
            }
            resultJson.add("findings",failures);
            Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(resultJson)+"\n");
            System.out.println(resultJson);if(!passed)System.exit(2);
        }
    }
}
