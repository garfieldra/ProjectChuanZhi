package com.soundseek.attachments;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {
  private final JdbcTemplate jdbc;
  private final Path directory;
  private final DocumentParser parser;
  private final ObjectMapper json;

  public AttachmentService(JdbcTemplate jdbc, @Value("${soundseek.attachments.directory:./data/attachments}") String directory,
      DocumentParser parser, ObjectMapper json) {
    this.jdbc=jdbc;this.directory=Path.of(directory).toAbsolutePath().normalize();this.parser=parser;this.json=json;
  }
  public record View(UUID id,String fileName,long byteSize,int width,int height,String contentType,
      String kind,String parseStatus,int pageCount,int textLength,String warning) {}
  public record Data(byte[] bytes,String contentType,String fileName,boolean image) {}
  public record Slice(View document,List<DocumentParser.Page> pages,boolean completeDocument,boolean excerptOnly,
      boolean hasMore,Integer nextPage,Integer nextOffset,int matchedPages) {}

  @Transactional
  public View upload(UUID owner,MultipartFile file) throws IOException {
    if(file.isEmpty()||file.getSize()>8L*1024*1024)throw new IllegalArgumentException("每个附件大小须为1字节至8MiB。");
    String name=Optional.ofNullable(file.getOriginalFilename()).orElse("附件").replaceAll("[\\\\/\\p{Cntrl}]","_");
    if(name.length()>180)name=name.substring(0,180);
    String lower=name.toLowerCase(Locale.ROOT);
    String extension=lower.endsWith(".pdf")?"pdf":lower.endsWith(".txt")?"txt":lower.endsWith(".md")||lower.endsWith(".markdown")?"md":"image";
    UUID id=UUID.randomUUID();byte[] bytes=file.getBytes();int width=0,height=0;String kind,type,warning="";int pageCount=0,textLength=0;
    DocumentParser.Parsed parsed=null;
    if(!extension.equals("image")) {
      parsed=parser.parse(bytes,extension);kind=parsed.kind();type=kind.equals("PDF")?"application/pdf":"text/plain";
      pageCount=parsed.pages().size();textLength=parsed.textLength();warning=parsed.warning();
    } else {
      BufferedImage image;
      try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("请选择JPG、PNG、WebP、TXT、Markdown或文字型PDF。");
        ImageReader reader=readers.next();try {
          reader.setInput(input);if(!Set.of("jpeg","png").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("图片须由浏览器转换为JPG/PNG后上传。");
          width=reader.getWidth(0);height=reader.getHeight(0);
          if(width<1||height<1||width>8192||height>8192||(long)width*height>16_000_000)throw new IllegalArgumentException("图片尺寸超出限制。");
          image=reader.read(0);
        } finally {reader.dispose();}
      } catch(javax.imageio.IIOException invalid) {throw new IllegalArgumentException("图片已损坏或无法读取。");}
      double scale=Math.min(1,1600.0/Math.max(width,height));width=Math.max(1,(int)(width*scale));height=Math.max(1,(int)(height*scale));
      var clean=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics=clean.createGraphics();
      try {graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,width,height);graphics.drawImage(image,0,0,width,height,null);}finally{graphics.dispose();image.flush();}
      var output=new ByteArrayOutputStream();try{if(!ImageIO.write(clean,"jpeg",output))throw new IOException("图片编码失败");}finally{clean.flush();}
      bytes=output.toByteArray();kind="IMAGE";type="image/jpeg";
    }
    Files.createDirectories(directory);
    var source=directory.resolve(id+(kind.equals("IMAGE")?".jpg":".source"));var extracted=directory.resolve(id+".document.json");
    try {
      Files.write(source,bytes,StandardOpenOption.CREATE_NEW);
      if(parsed!=null)Files.write(extracted,json.writeValueAsBytes(parsed),StandardOpenOption.CREATE_NEW);
      jdbc.update("INSERT INTO conversation_attachment(id,guest_session_id,file_name,content_type,byte_size,width,height,kind,parse_status,page_count,text_length,warning) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?,?,?,?)",
          id.toString(),owner.toString(),name,type,bytes.length,width,height,kind,warning.isEmpty()?"READY":"PARTIAL",pageCount,textLength,warning);
    } catch(IOException|RuntimeException failure) {deleteFiles(id);throw failure;}
    if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
      @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{deleteFiles(id);}catch(IOException ignored){}}
    });
    return new View(id,name,bytes.length,width,height,type,kind,warning.isEmpty()?"READY":"PARTIAL",pageCount,textLength,warning);
  }

  @Transactional
  public void bind(UUID owner,UUID conversation,List<UUID> ids) {
    long bytes=0;for(var id:ids)bytes+=((Number)owned(owner,id,conversation,true).get("byte_size")).longValue();
    if(bytes>20L*1024*1024)throw new IllegalArgumentException("本条消息的附件合计超过20MiB。");
    for(var id:ids)jdbc.update("UPDATE conversation_attachment SET conversation_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?) AND guest_session_id=UUID_TO_BIN(?)",conversation.toString(),id.toString(),owner.toString());
  }
  private Map<String,Object> owned(UUID owner,UUID id,UUID conversation,boolean lock) {
    var rows=jdbc.queryForList("SELECT a.*,BIN_TO_UUID(a.conversation_id) conversation_uuid FROM conversation_attachment a LEFT JOIN conversation c ON c.id=a.conversation_id WHERE a.id=UUID_TO_BIN(?) AND a.guest_session_id=UUID_TO_BIN(?) AND ((a.conversation_id IS NULL AND a.created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR)) OR (c.status<>'DELETED' AND c.guest_session_id=a.guest_session_id))"+(lock?" FOR UPDATE":""),id.toString(),owner.toString());
    if(rows.isEmpty())throw new NoSuchElementException();var row=rows.getFirst();
    if(conversation!=null&&!lock&&row.get("conversation_uuid")==null)throw new NoSuchElementException();
    if(conversation!=null&&row.get("conversation_uuid")!=null&&!conversation.toString().equals(row.get("conversation_uuid")))throw new NoSuchElementException();
    return row;
  }
  private View view(UUID id,Map<String,Object> row) {
    return new View(id,(String)row.get("file_name"),((Number)row.get("byte_size")).longValue(),((Number)row.get("width")).intValue(),((Number)row.get("height")).intValue(),
        (String)row.get("content_type"),(String)row.get("kind"),(String)row.get("parse_status"),((Number)row.get("page_count")).intValue(),((Number)row.get("text_length")).intValue(),(String)row.get("warning"));
  }
  @Transactional(readOnly=true)
  public View metadata(UUID owner,UUID id,UUID conversation) {return view(id,owned(owner,id,conversation,false));}
  @Transactional(readOnly=true)
  public Data content(UUID owner,UUID id,UUID conversation) throws IOException {
    var meta=metadata(owner,id,conversation);boolean image=meta.kind().equals("IMAGE");var path=directory.resolve(id+(image?".jpg":".source"));
    if(!Files.isRegularFile(path))throw new NoSuchElementException();return new Data(Files.readAllBytes(path),meta.contentType(),meta.fileName(),image);
  }
  @Transactional(readOnly=true)
  public List<Map<String,Object>> references(UUID owner,UUID conversation,List<UUID> ids) {
    return ids.stream().distinct().map(id->{var meta=metadata(owner,id,conversation);return json.convertValue(meta,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}).toList();
  }
  @Transactional(readOnly=true)
  public Slice document(UUID owner,UUID id,UUID conversation,int page,int count,int offset,String query,boolean overview) throws IOException {
    var meta=metadata(owner,id,conversation);if(meta.kind().equals("IMAGE"))throw new IllegalArgumentException("此附件不是文档。");
    if(page<1||count<1||count>3||offset<0||(query!=null&&query.length()>200))throw new IllegalArgumentException("文档读取范围不合法。");
    var path=directory.resolve(id+".document.json");if(!Files.isRegularFile(path))throw new NoSuchElementException();
    var parsed=json.readValue(Files.readAllBytes(path),DocumentParser.Parsed.class);
    if(overview) {
      boolean small=meta.textLength()<=24_000;
      return new Slice(meta,small?parsed.pages():List.of(),small&&meta.warning().isEmpty(),false,!small,small?null:1,null,0);
    }
    if(query!=null&&!query.isBlank()) {
      String needle=query.toLowerCase(Locale.ROOT);var hits=new ArrayList<DocumentParser.Page>();int matches=0;
      for(var part:parsed.pages()) {int index=part.text().toLowerCase(Locale.ROOT).indexOf(needle);if(index<0)continue;matches++;
        if(hits.size()<8)hits.add(new DocumentParser.Page(part.number(),part.text().substring(Math.max(0,index-200),Math.min(part.text().length(),index+needle.length()+400)),part.startLine(),part.endLine()));}
      return new Slice(meta,hits,false,true,matches>hits.size(),null,null,matches);
    }
    if(page>parsed.pages().size())throw new IllegalArgumentException("页码或片段超出文档范围。");
    int budget=conversation==null?50_000:30_000;var selected=new ArrayList<DocumentParser.Page>();Integer nextPage=null,nextOffset=null;
    for(int number=page;number<=Math.min(parsed.pages().size(),page+count-1);number++) {
      var part=parsed.pages().get(number-1);int start=number==page?offset:0;if(start>part.text().length())throw new IllegalArgumentException("文字偏移超出页面范围。");
      int end=Math.min(part.text().length(),start+budget);selected.add(new DocumentParser.Page(part.number(),part.text().substring(start,end),part.startLine(),part.endLine()));budget-=end-start;
      if(end<part.text().length()){nextPage=number;nextOffset=end;break;}
      if(budget==0&&number<parsed.pages().size()){nextPage=number+1;nextOffset=0;break;}
    }
    int last=selected.getLast().number();if(nextPage==null&&last<parsed.pages().size()){nextPage=last+1;nextOffset=0;}
    boolean complete=page==1&&offset==0&&last==parsed.pages().size()&&nextPage==null&&meta.warning().isEmpty();
    return new Slice(meta,selected,complete,false,nextPage!=null,nextPage,nextOffset,0);
  }
  private void deleteFiles(UUID id)throws IOException {for(String suffix:List.of(".jpg",".source",".document.json"))Files.deleteIfExists(directory.resolve(id+suffix));}
  @Scheduled(fixedDelay=3600000)
  @Transactional
  public void cleanup()throws IOException {
    var ids=jdbc.queryForList("SELECT BIN_TO_UUID(id) FROM conversation_attachment WHERE conversation_id IS NULL AND created_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR)",String.class);
    for(var id:ids)if(jdbc.update("DELETE FROM conversation_attachment WHERE id=UUID_TO_BIN(?) AND conversation_id IS NULL",id)>0)deleteFiles(UUID.fromString(id));
  }
}
