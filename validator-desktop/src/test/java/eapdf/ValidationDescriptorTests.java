package eapdf;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
class ValidationDescriptorTests {
    @TempDir Path temporary;
    public static class Probe {
        public static void main(String[] args) throws Exception {
            var streams=new ArrayList<java.io.InputStream>();
            try {
                for(int i=0;i<400;i++)streams.add(Files.newInputStream(Path.of(args[0])));
            } catch(java.io.IOException expected) {
                if(!expected.getMessage().contains("Too many open files"))throw expected;
            } finally {
                System.out.println(streams.size());
                for(var stream:streams)stream.close();
            }
        }
    }
    private int probe() throws Exception {
        Path input=temporary.resolve("input");Files.writeString(input,"fixture");
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-Xmx128m","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),Probe.class.getName(),input.toString());
        Path output=temporary.resolve("result");
        Process child=new ProcessBuilder(ValidationRuntime.boundedCommand(command,256)).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {assertTrue(child.waitFor(30,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),Files.readString(output));return Integer.parseInt(Files.readString(output).strip());}
        finally {child.destroyForcibly();}
    }
    @Test void legitimateStreamCountsAboveOldLimitComplete() throws Exception {assertEquals(400,probe());}
    @Test void configuredLowerLimitIsEnforced() throws Exception {
        String previous=System.getProperty("VALIDATOR_MAX_OPEN_FILES");
        System.setProperty("VALIDATOR_MAX_OPEN_FILES","300");
        try {int opened=probe();assertTrue(opened>=250&&opened<300,"opened="+opened);}
        finally {if(previous==null)System.clearProperty("VALIDATOR_MAX_OPEN_FILES");else System.setProperty("VALIDATOR_MAX_OPEN_FILES",previous);}
    }
}
