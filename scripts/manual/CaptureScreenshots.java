package eapdf;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Documentation-only capture of the real Swing applications using fictional mail. */
public final class CaptureScreenshots {
    static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    static void invoke(Object owner,String name) throws Exception {
        Method method=owner.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(owner);
    }
    static <T> java.util.List<T> components(Container root,Class<T> type) {
        var result=new ArrayList<T>();
        for(Component component:root.getComponents()) {
            if(type.isInstance(component))result.add(type.cast(component));
            if(component instanceof Container container)result.addAll(components(container,type));
        }
        return result;
    }
    static void capture(JFrame frame,Path output) throws Exception {
        SwingUtilities.invokeAndWait(()->{
            BufferedImage image=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics=image.createGraphics();frame.printAll(graphics);graphics.dispose();
            try {ImageIO.write(image,"png",output.toFile());}catch(Exception error){throw new RuntimeException(error);}
        });
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3&&args.length!=5)throw new IllegalArgumentException("Usage: CaptureScreenshots creator|validator WORK OUTPUT [WIDTH HEIGHT]");
        String mode=args[0];if(!mode.equals("creator")&&!mode.equals("validator"))throw new IllegalArgumentException("Unknown capture mode: "+mode);
        Path work=Path.of(args[1]);Path output=Path.of(args[2]);Files.createDirectories(output);
        int width=args.length==5?Integer.parseInt(args[3]):1200;
        int height=args.length==5?Integer.parseInt(args[4]):mode.equals("creator")?955:850;
        if(width<1||height<1)throw new IllegalArgumentException("Capture dimensions must be positive");
        Object[] instance={null};
        SwingUtilities.invokeAndWait(()->{
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                if(mode.equals("creator")) {
                    var gui=new EapdfGui();instance[0]=gui;invoke(gui,"show");
                    JFrame frame=(JFrame)field(gui,"frame");frame.setSize(width,height);frame.validate();
                    var fields=components(frame,JTextField.class);fields.get(0).setText(work.resolve("emails").toString());fields.get(1).setText(work.resolve("converted").toString());
                    components(frame,JComboBox.class).getFirst().setSelectedIndex(2);
                } else {
                    var gui=new ValidatorGui(new JavaValidatorEngine());instance[0]=gui;gui.show();
                    ((JFrame)field(gui,"frame")).setSize(width,height);
                    ((JTextField)field(gui,"file")).setText(work.resolve("manual_1m.pdf").toString());
                }
            } catch(Exception error){throw new RuntimeException(error);}
        });
        Object gui=instance[0];JFrame frame=(JFrame)field(gui,"frame");
        capture(frame,output.resolve(mode+"-ready.png"));
        SwingUtilities.invokeAndWait(()->{
            try {
                if(mode.equals("creator")) components(frame,JButton.class).stream().filter(b->b.getText().equals("Convert Local Input")).findFirst().orElseThrow().doClick();
                else invoke(gui,"start");
            }catch(Exception error){throw new RuntimeException(error);}
        });
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
        while(true){
            boolean[] running={true};SwingUtilities.invokeAndWait(()->{try{running[0]=(boolean)field(gui,"running");}catch(Exception error){throw new RuntimeException(error);}});
            if(!running[0])break;if(System.nanoTime()>deadline)throw new IllegalStateException("Application did not finish");Thread.sleep(200);
        }
        capture(frame,output.resolve(mode+"-complete.png"));
        if(mode.equals("creator")) {
            SwingUtilities.invokeAndWait(()->{
                var tabs=components(frame,JTabbedPane.class).getFirst();tabs.setSelectedIndex(1);frame.validate();
            });
            capture(frame,output.resolve("creator-gmail.png"));
        }
        SwingUtilities.invokeAndWait(()->{
            try {if(!mode.equals("creator"))invoke(gui,"clear");}
            catch(Exception error){throw new RuntimeException(error);}
            frame.dispose();
        });System.exit(0);
    }
}
