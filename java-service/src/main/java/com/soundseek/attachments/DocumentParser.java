package com.soundseek.attachments;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Bounded, local document normalization. No model or external URLs are used. */
@Component
public class DocumentParser {
  public static final int MAX_CHARS = 500_000;
  public record Page(int number, String text, Integer startLine, Integer endLine) {}
  public record Parsed(String kind, List<Page> pages, int textLength, String warning) {}
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8), task -> {var thread = new Thread(task, "document-parser");thread.setDaemon(true);return thread;}, new ThreadPoolExecutor.AbortPolicy());

  public Parsed parse(byte[] bytes, String extension) {
    Future<Parsed> task;
    try { task = workers.submit(() -> extension.equals("pdf") ? pdf(bytes) : text(bytes)); }
    catch (RejectedExecutionException busy) { throw new IllegalArgumentException("文件处理繁忙，请稍后重试。"); }
    try { return task.get(15, TimeUnit.SECONDS); }
    catch (TimeoutException timeout) { task.cancel(true);throw new IllegalArgumentException("文件解析超时，请简化文件后重试。"); }
    catch (InterruptedException interrupted) { task.cancel(true);Thread.currentThread().interrupt();throw new IllegalArgumentException("文件处理已取消，请重试。"); }
    catch (ExecutionException failure) {
      if (failure.getCause() instanceof IllegalArgumentException invalid) throw invalid;
      throw new IllegalArgumentException("文档损坏或无法解析，请换一个文件。");
    }
  }
  @PreDestroy public void close() { workers.shutdownNow(); }

  Parsed text(byte[] bytes) {
    if (bytes.length == 0) throw new IllegalArgumentException("文件没有可读取的文字。");
    if ((bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) || (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K')) throw new IllegalArgumentException("文件内容不是纯文本，请使用正确的文件类型。");
    String value;
    try {
      if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) value = decode(bytes, 2, StandardCharsets.UTF_16LE);
      else if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) value = decode(bytes, 2, StandardCharsets.UTF_16BE);
      else {
        int skip = bytes.length >= 3 && bytes[0] == (byte)0xef && bytes[1] == (byte)0xbb && bytes[2] == (byte)0xbf ? 3 : 0;
        try { value = decode(bytes, skip, StandardCharsets.UTF_8); }
        catch (CharacterCodingException utf8) { if(skip>0)throw utf8;value = decode(bytes, skip, Charset.forName("GB18030")); }
      }
    } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("无法识别文本编码，请另存为UTF-8后上传。"); }
    value = value.replace("\r\n", "\n").replace('\r', '\n');
    if (value.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\t' && cp != '\f')) throw new IllegalArgumentException("TXT/Markdown必须是纯文本，不能包含二进制内容。");
    if (value.isBlank()) throw new IllegalArgumentException("文件没有可读取的文字。");
    if (value.length() > MAX_CHARS) throw new IllegalArgumentException("文档文字超过500,000字符，请拆分后上传。");
    var pages = new ArrayList<Page>();var part = new StringBuilder();int line = 1, start = 1;
    for (String row : value.split("\n", -1)) {
      if (!part.isEmpty() && part.length() + row.length() + 1 > 6000) { pages.add(new Page(pages.size()+1, part.toString(), start, line-1));part.setLength(0);start=line; }
      for (int offset=0;offset<row.length();offset+=6000) {
        if (part.length() >= 6000) {pages.add(new Page(pages.size()+1,part.toString(),start,line));part.setLength(0);start=line;}
        part.append(row,offset,Math.min(row.length(),offset+6000));
      }
      part.append('\n');line++;
    }
    if (!part.isEmpty()) pages.add(new Page(pages.size()+1,part.toString(),start,line-1));
    return new Parsed("TEXT", List.copyOf(pages), value.length(), "");
  }
  private String decode(byte[] bytes,int offset,Charset charset) throws CharacterCodingException {
    return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();
  }
  Parsed pdf(byte[] bytes) throws IOException {
    if (bytes.length < 5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw new IllegalArgumentException("PDF文件内容与格式不符。");
    try (var document = Loader.loadPDF(new org.apache.pdfbox.io.RandomAccessReadBuffer(bytes), IOUtils.createTempFileOnlyStreamCache())) {
      if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()) throw new IllegalArgumentException("暂不支持加密或禁止文字提取的PDF，请提供可读取的版本。");
      if (document.getNumberOfPages() > 100) throw new IllegalArgumentException("PDF超过100页，请拆分后上传。");
      var pages = new ArrayList<Page>();var missing = new ArrayList<Integer>();int total = 0;
      for (int number=1;number<=document.getNumberOfPages();number++) {
        if(Thread.currentThread().isInterrupted())throw new IOException("cancelled");
        var streams = document.getPage(number-1).getContentStreams();int streamBytes=0;
        while (streams.hasNext()) try(var input=streams.next().createInputStream()) {streamBytes+=input.readNBytes(2_000_001).length;if(streamBytes>2_000_000)throw new IllegalArgumentException("PDF单页内容过于复杂，请简化后上传。");}
        var stripper = new PDFTextStripper();stripper.setSortByPosition(true);stripper.setStartPage(number);stripper.setEndPage(number);
        var writer = new LimitedWriter(Math.min(50_000,MAX_CHARS-total));stripper.writeText(document,writer);String text=writer.toString();
        total+=text.length();if(text.isBlank())missing.add(number);pages.add(new Page(number,text,null,null));
      }
      if (total == 0 || pages.stream().allMatch(page->page.text().isBlank())) throw new IllegalArgumentException("此PDF没有可提取的文字，可能是扫描件；首版暂不支持OCR，请上传文字型PDF或TXT。");
      String warning = missing.isEmpty()?"":"第"+String.join("、",missing.stream().map(String::valueOf).toList())+"页未提取到文字（可能为空白或扫描页）；这些页尚未读懂。";
      return new Parsed("PDF",List.copyOf(pages),total,warning);
    } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted) {throw new IllegalArgumentException("暂不支持加密PDF，请提供未加密版本。");}
  }
  static final class LimitedWriter extends Writer {
    private final int limit;private final StringBuilder output=new StringBuilder();
    LimitedWriter(int limit){this.limit=limit;}
    @Override public void write(char[] chars,int offset,int count)throws IOException {
      if(Thread.currentThread().isInterrupted())throw new IOException("cancelled");
      if(output.length()+count>limit)throw new IllegalArgumentException("文档文字超出解析预算（单页50,000、总计500,000字符），请拆分后上传。");
      output.append(chars,offset,count);
    }
    @Override public void flush(){}
    @Override public void close(){}
    @Override public String toString(){return output.toString();}
  }
}
