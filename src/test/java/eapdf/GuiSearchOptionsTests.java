package eapdf;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
class GuiSearchOptionsTests {

 @Test void searchableIndexCheckboxControlsBothIndexesInChildConfiguration()throws Exception{
  var config=EapdfConfig.get();String creator=config.creator;
  try{
   config.creator="GUI regression";
   var controls=EapdfGui.buildRunConfigControls();assertEquals("Embed searchable index",controls.searchIndex.getText());
   assertEquals("Accessibility (PDF/UA)",controls.pdfUa.getText());
   for(boolean enabled:new boolean[]{false,true}){
    controls.searchIndex.setSelected(enabled);
    Path path=EapdfGui.writeGuiConfig(controls.snapshot());
    try{var child=new com.google.gson.Gson().fromJson(Files.readString(path),EapdfConfig.class);
     assertFalse(Files.readString(path).contains("\"branding\""));assertFalse(Files.readString(path).contains("\"findingAid\""));assertFalse(Files.readString(path).contains("\"premis\""));
     assertEquals(enabled,child.searchIndex.enabled);assertEquals(enabled,child.searchIndex.fullTextEnabled);

    }finally{Files.delete(path);}
   }
  }finally{config.creator=creator;}
 }
 @Test void nestedEditableControlsAndButtonsReceiveTooltips(){
  JPanel panel=new JPanel();var field=new JTextField();var button=new JButton("Browse");var spinner=new JSpinner();var choice=new JComboBox<>(new String[]{"One"});
  panel.add(field);panel.add(button);panel.add(spinner);panel.add(choice);
  EapdfGui.addMissingTooltips(panel,"Output directory");
  for(var control:new JComponent[]{field,button,spinner,choice})assertFalse(control.getToolTipText().isBlank());
  field.setToolTipText("Specific help");EapdfGui.addMissingTooltips(panel,"Other");assertEquals("Specific help",field.getToolTipText());
 }
 @org.junit.jupiter.api.Test void labelsUseEntryHelpInsidePathPanels() {
  var panel=new javax.swing.JPanel(new java.awt.BorderLayout());
  var entry=new javax.swing.JTextField();entry.setToolTipText("Specific destination help");
  var browse=new javax.swing.JButton("Browse");browse.setToolTipText("Browse help");
  panel.add(entry,java.awt.BorderLayout.CENTER);panel.add(browse,java.awt.BorderLayout.EAST);
  assertEquals(entry.getToolTipText(),EapdfGui.tooltipFor(panel,"Destination"));
  assertEquals("Fallback",EapdfGui.tooltipFor(new javax.swing.JPanel(),"Fallback"));
 }
}
