import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import com.google.gson.*;

public class StandaloneGuiProof {
  static Object field(Object target,String name)throws Exception {
    Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
  }
  static Set<String> workspaces()throws Exception {
    try(var paths=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return paths.map(path->path.getFileName().toString()).filter(name->name.startsWith("eapdf-validator-")).collect(java.util.stream.Collectors.toSet());
    }
  }
  public static void main(String[]args)throws Exception {
    Set<String> before=workspaces(); AtomicReference<Object> holder=new AtomicReference<>();
    Class<?> guiType=Class.forName("eapdf.ValidatorGui"),engineType=Class.forName("eapdf.JavaValidatorEngine");
    Constructor<?> engine=engineType.getDeclaredConstructor();engine.setAccessible(true);
    Constructor<?> gui=guiType.getDeclaredConstructor(Class.forName("eapdf.ValidatorGui$Engine"));gui.setAccessible(true);
    Method show=guiType.getDeclaredMethod("show");show.setAccessible(true);
    SwingUtilities.invokeAndWait(()->{try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      Object window=gui.newInstance(engine.newInstance());holder.set(window);show.invoke(window);
      ((JTextField)field(window,"file")).setText(Path.of(args[0]).toAbsolutePath().toString());
      ((JButton)field(window,"validate")).doClick();
    }catch(Exception error){throw new RuntimeException(error);}});
    Object window=holder.get();JFrame frame=(JFrame)field(window,"frame");
    try {
      AtomicReference<String> status=new AtomicReference<>();long deadline=System.nanoTime()+60_000_000_000L;
      do {
        SwingUtilities.invokeAndWait(()->{try{status.set(((JLabel)field(window,"status")).getText());}catch(Exception error){throw new RuntimeException(error);}});
        if(status.get().startsWith("Result:"))break;
        Thread.sleep(100);
      }while(System.nanoTime()<deadline);
      if(!status.get().startsWith("Result: PASS"))throw new AssertionError(status.get());
      Object result=field(window,"result");Method json=result.getClass().getDeclaredMethod("json");json.setAccessible(true);
      JsonObject report=JsonParser.parseString((String)json.invoke(result)).getAsJsonObject();
      Files.writeString(Path.of(args[1]),report.toString());
      AtomicBoolean excerpt=new AtomicBoolean();
      SwingUtilities.invokeAndWait(()->{try {
        JTable table=(JTable)field(window,"table");
        if(table.getColumnCount()!=4||frame.getWidth()!=2000||table.getFont().getSize()!=16)throw new AssertionError("GUI settings changed");
        for(int row=0;row<table.getRowCount();row++)if("CATALOG_PIECEINFO".equals(table.getValueAt(row,2))) {
          table.setRowSelectionInterval(row,row);excerpt.set(((JTextArea)field(window,"specification")).getText().contains("SHALL NOT contain a PieceInfo"));
        }
        var image=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();try{frame.printAll(graphics);}finally{graphics.dispose();}
        javax.imageio.ImageIO.write(image,"png",Path.of(args[2]).toFile());
      }catch(Exception error){throw new RuntimeException(error);}});
      var proof=new JsonObject();
      proof.addProperty("jar_sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("eapdf-validator.jar")))));
      try(var files=Files.list(Path.of("."))){proof.addProperty("only_jar_in_empty_working_directory",files.map(path->path.getFileName().toString()).toList().equals(List.of("eapdf-validator.jar")));}
      proof.addProperty("path",System.getenv("PATH"));proof.addProperty("worker_classpath",System.getProperty("java.class.path"));
      proof.addProperty("overall",report.getAsJsonObject("summary").get("status").getAsString());
      proof.addProperty("java_port_complete",report.getAsJsonObject("summary").get("java_port_complete").getAsBoolean());
      proof.addProperty("pdfa",report.getAsJsonObject("pdfa").get("status").getAsString());
      proof.addProperty("ea_failures",report.getAsJsonObject("summary").get("fail").getAsInt());
      for(var value:report.getAsJsonArray("items")) {
        JsonObject item=value.getAsJsonObject();
        if("ISO32005_UPSTREAM".equals(item.get("code").getAsString()))proof.addProperty("iso",item.get("level").getAsString());
      }
      proof.addProperty("specification_pane_populated",excerpt.get());proof.addProperty("gui_font_points",16);proof.addProperty("window_width",2000);
      proof.addProperty("worker_workspaces_cleaned",before.equals(workspaces()));
      if(!excerpt.get()||!proof.get("only_jar_in_empty_working_directory").getAsBoolean()||!proof.get("worker_workspaces_cleaned").getAsBoolean())throw new AssertionError(proof.toString());
      Files.writeString(Path.of(args[3]),new GsonBuilder().setPrettyPrinting().create().toJson(proof));
      System.out.println(proof);
    }finally{SwingUtilities.invokeAndWait(frame::dispose);}
  }
}
