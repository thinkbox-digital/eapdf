package eapdf;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
class GuiSearchOptionsTests {

 @Test void creatorFeaturesStartUncheckedAndControlChildConfiguration()throws Exception{
  var config=EapdfConfig.get();String creator=config.creator;
  boolean searchIndexEnabled=config.searchIndex.enabled;
  boolean accessibilityEnabled=config.accessibility.addPdfUaFeatures;
  boolean signingEnabled=config.signing.enabled;
  try{
   config.creator="GUI regression";
   config.searchIndex.enabled=true;
   config.accessibility.addPdfUaFeatures=true;
   config.signing.enabled=true;
   var controls=EapdfGui.buildRunConfigControls();assertEquals("Embed searchable index",controls.searchIndex.getText());
   assertFalse(controls.searchIndex.isSelected());
   assertEquals("Accessibility (PDF/UA)",controls.pdfUa.getText());
   assertFalse(controls.pdfUa.isSelected());
   assertFalse(controls.signOutput.isSelected());
   for(boolean enabled:new boolean[]{false,true}){
    controls.searchIndex.setSelected(enabled);
    controls.pdfUa.setSelected(enabled);
    controls.signOutput.setSelected(enabled);
    Path path=EapdfGui.writeGuiConfig(controls.snapshot());
    try{var child=new com.google.gson.Gson().fromJson(Files.readString(path),EapdfConfig.class);
     assertFalse(Files.readString(path).contains("\"branding\""));assertFalse(Files.readString(path).contains("\"findingAid\""));assertFalse(Files.readString(path).contains("\"premis\""));
     assertEquals(enabled,child.searchIndex.enabled);assertEquals(enabled,child.searchIndex.fullTextEnabled);
     assertEquals(enabled,child.accessibility.addPdfUaFeatures);
     assertEquals(enabled,child.signing.enabled);

    }finally{Files.delete(path);}
   }
  }finally{
   config.creator=creator;config.searchIndex.enabled=searchIndexEnabled;
   config.accessibility.addPdfUaFeatures=accessibilityEnabled;config.signing.enabled=signingEnabled;
  }
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
