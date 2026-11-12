package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real Swing actions; opt in on a graphical desktop with eapdf.creator.uiTest=true. */
@EnabledIfSystemProperty(named="eapdf.creator.uiTest", matches="true")
class CreatorDesktopSmokeTests {
  @TempDir Path temporary;

  @Test void choosesInputAndOutputReportsErrorsConvertsAndStops() throws Exception {
    assertFalse(GraphicsEnvironment.isHeadless());
    var reference=new AtomicReference<EapdfGui>();
    SwingUtilities.invokeAndWait(()->{
      var gui=new EapdfGui();reference.set(gui);
      try { var method=EapdfGui.class.getDeclaredMethod("show");method.setAccessible(true);method.invoke(gui); }
      catch(Exception error){throw new AssertionError(error);}
    });
    var gui=reference.get();var frame=field(gui,"frame",JFrame.class);
    var run=find(frame,JButton.class,b->b.getText().equals("Convert Local Input"));
    var input=pathField(frame,"Input file or folder");
    var output=pathField(frame,"Output directory (required)");
    var status=field(gui,"statusLabel",JLabel.class);var stop=field(gui,"stopButton",JButton.class);
    try {
      SwingUtilities.invokeAndWait(()->find(frame,JCheckBox.class,b->b.getText().startsWith("Accessibility")).setSelected(true));
      assertTrue(errorDialog(frame,run::doClick).contains("Choose a local email"));
      SwingUtilities.invokeAndWait(()->input.setText(temporary.resolve("missing.eml").toString()));
      assertTrue(errorDialog(frame,run::doClick).contains("cannot be read"));
      Path source=Path.of("in/sample.eml").toAbsolutePath(),destination=Files.createDirectory(temporary.resolve("created"));
      choose(frame,find((Container)input.getParent(),JButton.class,b->true),source);
      choose(frame,find((Container)output.getParent(),JButton.class,b->true),destination);
      SwingUtilities.invokeAndWait(run::doClick);
      await(()->!running(gui));
      SwingUtilities.invokeAndWait(()->{
        assertEquals("Finished",status.getText());assertTrue(run.isEnabled());assertFalse(stop.isEnabled());
        assertTrue(field(gui,"openOutputButton",JButton.class).isEnabled());
        assertTrue(field(gui,"summaryArea",JTextArea.class).getText().contains("Validate the PDFs"));
      });
      try(var paths=Files.list(destination)){assertEquals(1,paths.filter(p->p.toString().endsWith(".pdf")).count());}
      assertTrue(Files.readString(destination.resolve("progress.json")).contains("COMPLETED"));
      String evidence=System.getProperty("eapdf.ui.evidence");
      if(evidence!=null){
        Path retained=Files.createDirectories(Path.of(evidence));
        try(var paths=Files.list(destination)){
          for(Path path:paths.toList())if(Files.isRegularFile(path))Files.copy(path,retained.resolve(path.toString().endsWith(".pdf")?"eml_gui_1s.pdf":path.getFileName().toString()),StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(retained.resolve("source.sha256"),Common.sha256Hex(Files.readAllBytes(source)));
        var image=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(()->{var graphics=image.createGraphics();try{frame.printAll(graphics);}finally{graphics.dispose();}});
        javax.imageio.ImageIO.write(image,"png",retained.resolve("creator-completed.png").toFile());
      }

      Path stopped=Files.createDirectory(temporary.resolve("stopped"));
      SwingUtilities.invokeAndWait(()->{output.setText(stopped.toString());run.doClick();assertTrue(stop.isEnabled());stop.doClick();});
      await(()->!running(gui));
      SwingUtilities.invokeAndWait(()->{assertEquals("Stopped",status.getText());assertTrue(run.isEnabled());assertFalse(stop.isEnabled());});
      try(var paths=Files.list(stopped)){assertEquals(0,paths.filter(p->p.toString().endsWith(".pdf")).count());}
    } finally {SwingUtilities.invokeAndWait(frame::dispose);}
  }

  private static boolean running(EapdfGui gui){return field(gui,"running",Boolean.class);}
  private static JTextField pathField(Container root,String name){
    var label=find(root,JLabel.class,l->name.equals(l.getText()));
    return find((Container)label.getLabelFor(),JTextField.class,f->true);
  }
  private static void choose(JFrame frame,JButton browse,Path selection) throws Exception {
    SwingUtilities.invokeAndWait(()->{
      var timer=new Timer(100,event->{for(var window:Window.getWindows())if(window instanceof JDialog d&&d.getOwner()==frame&&d.isShowing()){
        var chooser=findOrNull(d,JFileChooser.class,c->true);if(chooser!=null){((Timer)event.getSource()).stop();chooser.setSelectedFile(selection.toFile());chooser.approveSelection();}
      }});
      timer.start();try{browse.doClick();}finally{timer.stop();}
    });
  }
  private static String errorDialog(JFrame frame,Runnable action) throws Exception {
    var message=new AtomicReference<String>();
    SwingUtilities.invokeAndWait(()->{
      var timer=new Timer(100,event->{for(var window:Window.getWindows())if(window instanceof JDialog d&&d.getOwner()==frame&&d.isShowing()){
        var pane=findOrNull(d,JOptionPane.class,p->true);if(pane!=null){message.set(String.valueOf(pane.getMessage()));((Timer)event.getSource()).stop();pane.setValue(JOptionPane.OK_OPTION);}
      }});
      timer.start();try{action.run();}finally{timer.stop();}
    });
    assertNotNull(message.get());return message.get();
  }
  private static <T extends Component>T find(Container parent,Class<T> type,java.util.function.Predicate<T> predicate){
    T found=findOrNull(parent,type,predicate);assertNotNull(found,type.getSimpleName());return found;
  }
  private static <T extends Component>T findOrNull(Container parent,Class<T> type,java.util.function.Predicate<T> predicate){
    for(Component component:parent.getComponents()){
      if(type.isInstance(component)&&predicate.test(type.cast(component)))return type.cast(component);
      if(component instanceof Container child){T result=findOrNull(child,type,predicate);if(result!=null)return result;}
    }return null;
  }
  private static <T>T field(Object object,String name,Class<T> type){
    try{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(object));}
    catch(Exception error){throw new AssertionError(error);}
  }
  private static void await(BooleanSupplier condition) throws Exception {
    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
    while(System.nanoTime()<end){var ready=new AtomicReference<Boolean>();SwingUtilities.invokeAndWait(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(100);}
    fail("Creator desktop workflow timed out");
  }
}
