package com.soundseek.attachments;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DocumentParserTest {
  private final DocumentParser parser=new DocumentParser();
  @AfterEach void close(){parser.close();}
  private byte[] pdf(String... contents)throws Exception {
    try(var doc=new PDDocument();var output=new ByteArrayOutputStream()){
      for(String text:contents){var page=new PDPage();doc.addPage(page);if(text!=null&&!text.isEmpty())try(var writer=new PDPageContentStream(doc,page)){writer.beginText();writer.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);writer.newLineAtOffset(50,700);writer.showText(text);writer.endText();}}
      doc.save(output);return output.toByteArray();
    }
  }
  @Test void decodesChineseUtf8AndGb18030WithoutLosingLines(){
    for(var charset:new Charset[]{StandardCharsets.UTF_8,Charset.forName("GB18030")}){
      var result=parser.parse("寻声资料\n偏好：器乐\n时间：22:40".getBytes(charset),"txt");
      assertTrue(result.pages().getFirst().text().contains("偏好：器乐"));assertEquals(1,result.pages().getFirst().startLine());assertEquals(3,result.pages().getFirst().endLine());
    }
  }
  @Test void supportsUtf16Bom(){
    var result=parser.parse("寻声资料".getBytes(StandardCharsets.UTF_16),"txt");assertTrue(result.pages().getFirst().text().contains("寻声"));
  }
  @Test void rejectsBinaryEmptyDisguisedAndOversizedText(){
    for(var bytes:new byte[][]{new byte[]{0,1,2}," ".getBytes(),"%PDF-1.7 fake".getBytes(),"PK fake".getBytes()})assertThrows(IllegalArgumentException.class,()->parser.parse(bytes,"txt"));
    assertThrows(IllegalArgumentException.class,()->parser.parse("x".repeat(500_001).getBytes(),"txt"));
  }
  @Test void keepsEveryTextCharacterAcrossLongLineSegments(){
    String text="x".repeat(13000);var parsed=parser.parse(text.getBytes(),"txt");assertEquals(3,parsed.pages().size());assertEquals(13000,parsed.pages().stream().mapToInt(page->page.text().replace("\n","").length()).sum());assertTrue(parsed.pages().stream().allMatch(page->page.startLine()==1&&page.endLine()==1));
  }
  @Test void pdfPreservesOriginalPhysicalPagesAndMissingPageWarning()throws Exception{
    var result=parser.parse(pdf("First page","","Secret cue AMBER-742"),"pdf");assertEquals(3,result.pages().size());assertTrue(result.pages().get(2).text().contains("AMBER-742"));assertEquals(3,result.pages().get(2).number());assertTrue(result.warning().contains("第2页"));
  }
  @Test void rejectsNonTextScannedAndBrokenPdf()throws Exception{
    assertThrows(IllegalArgumentException.class,()->parser.parse(pdf(""),"pdf"));assertThrows(IllegalArgumentException.class,()->parser.parse("%PDF-corrupt".getBytes(),"pdf"));assertThrows(IllegalArgumentException.class,()->parser.parse("wrong".getBytes(),"pdf"));
  }
  @Test void rejectsEncryptedAndTooManyPages()throws Exception{
    try(var doc=new PDDocument();var output=new ByteArrayOutputStream()){
      doc.addPage(new PDPage());doc.protect(new StandardProtectionPolicy("owner","secret",new AccessPermission()));doc.save(output);
      assertThrows(IllegalArgumentException.class,()->parser.parse(output.toByteArray(),"pdf"));
    }
    assertThrows(IllegalArgumentException.class,()->parser.parse(pdf(new String[101]),"pdf"));
  }
}
