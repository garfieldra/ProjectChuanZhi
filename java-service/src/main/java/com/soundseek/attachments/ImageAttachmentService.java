package com.soundseek.attachments;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
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
public class ImageAttachmentService {
 private final JdbcTemplate jdbc;
 private final Path directory;
 public ImageAttachmentService(JdbcTemplate jdbc,@Value("${soundseek.attachments.directory:./data/attachments}") String directory){this.jdbc=jdbc;this.directory=Path.of(directory).toAbsolutePath().normalize();}
 public record View(UUID id,String fileName,long byteSize,int width,int height,String contentType){}
 public record Data(byte[] bytes,String contentType){}
 @Transactional
 public View upload(UUID owner,MultipartFile file) throws IOException {
  if(file.isEmpty()||file.getSize()>8L*1024*1024)throw new IllegalArgumentException("每张图片大小须为1字节至8MB");
  var id=UUID.randomUUID();BufferedImage image;
  try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))){
   var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("图片格式无法读取");
   ImageReader reader=readers.next();try{
    reader.setInput(input);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
    if(!Set.of("jpeg","png").contains(format))throw new IllegalArgumentException("服务端接收JPG/PNG，其他支持格式须先由浏览器转换");
    int width=reader.getWidth(0),height=reader.getHeight(0);
    if(width<1||height<1||width>8192||height>8192||(long)width*height>16_000_000)throw new IllegalArgumentException("图片尺寸超出限制");
    image=reader.read(0);
   }finally{reader.dispose();}
  }catch(javax.imageio.IIOException invalid){throw new IllegalArgumentException("图片已损坏或无法读取");}
  int width=image.getWidth(),height=image.getHeight();double scale=Math.min(1,1600.0/Math.max(width,height));
  var clean=new BufferedImage(Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)),BufferedImage.TYPE_INT_RGB);
  var graphics=clean.createGraphics();try{graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,clean.getWidth(),clean.getHeight());graphics.drawImage(image,0,0,clean.getWidth(),clean.getHeight(),null);}finally{graphics.dispose();image.flush();}
  var output=new ByteArrayOutputStream();try{if(!ImageIO.write(clean,"jpeg",output))throw new IOException("图片编码失败");}finally{clean.flush();}
  byte[] bytes=output.toByteArray();String name=Optional.ofNullable(file.getOriginalFilename()).orElse("图片");name=name.replace('/','_').chars().filter(ch->!Character.isISOControl(ch)).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();if(name.length()>180)name=name.substring(0,180);
  Files.createDirectories(directory);var path=directory.resolve(id+".jpg");Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
  try{jdbc.update("INSERT INTO image_attachment(id,guest_session_id,file_name,content_type,byte_size,width,height) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,'image/jpeg',?,?,?)",id.toString(),owner.toString(),name,bytes.length,clean.getWidth(),clean.getHeight());}
  catch(RuntimeException failure){Files.deleteIfExists(path);throw failure;}
  // A transaction may fail after the metadata INSERT, for example at commit.
  if(TransactionSynchronizationManager.isSynchronizationActive()) {
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCompletion(int status) {
     if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(path);}catch(IOException ignored){}
    }
   });
  }
  return new View(id,name,bytes.length,clean.getWidth(),clean.getHeight(),"image/jpeg");
 }
 @Transactional
 public void bind(UUID owner,UUID conversation,List<UUID> ids){
  if(ids.isEmpty())return;
  long bytes=0;
  for(var id:ids){var row=owned(owner,id,conversation,true);bytes+=((Number)row.get("byte_size")).longValue();}
  if(bytes>20L*1024*1024)throw new IllegalArgumentException("本条消息的图片合计超过20MB");
  for(var id:ids)jdbc.update("UPDATE image_attachment SET conversation_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?) AND guest_session_id=UUID_TO_BIN(?)",conversation.toString(),id.toString(),owner.toString());
 }
 private Map<String,Object> owned(UUID owner,UUID id,UUID conversation,boolean lock){
  var rows=jdbc.queryForList("SELECT a.*,BIN_TO_UUID(a.conversation_id) conversation_uuid FROM image_attachment a LEFT JOIN conversation c ON c.id=a.conversation_id WHERE a.id=UUID_TO_BIN(?) AND a.guest_session_id=UUID_TO_BIN(?) AND ((a.conversation_id IS NULL AND a.created_at>DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR)) OR (c.status<>'DELETED' AND c.guest_session_id=a.guest_session_id))"+(lock?" FOR UPDATE":""),id.toString(),owner.toString());
  if(rows.isEmpty())throw new NoSuchElementException();var row=rows.getFirst();
  if(conversation!=null&&!lock&&row.get("conversation_uuid")==null)throw new NoSuchElementException();
  if(conversation!=null&&row.get("conversation_uuid")!=null&&!conversation.toString().equals(row.get("conversation_uuid")))throw new NoSuchElementException();
  return row;
 }
 @Transactional(readOnly=true)
 public Data content(UUID owner,UUID id,UUID conversation) throws IOException {owned(owner,id,conversation,false);var path=directory.resolve(id+".jpg");if(!Files.isRegularFile(path))throw new NoSuchElementException();return new Data(Files.readAllBytes(path),"image/jpeg");}
 @Transactional(readOnly=true)
 public List<Map<String,Object>> references(UUID owner,UUID conversation,List<UUID> ids){
  return ids.stream().distinct().map(id->{var row=owned(owner,id,conversation,false);return Map.<String,Object>of("id",id.toString(),"fileName",row.get("file_name"),"contentType",row.get("content_type"));}).toList();
 }
 @Scheduled(fixedDelay=3600000)
 @Transactional
 public void cleanup() throws IOException {
  var ids=jdbc.queryForList("SELECT BIN_TO_UUID(id) FROM image_attachment WHERE conversation_id IS NULL AND created_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR)",String.class);
  for(var id:ids){if(jdbc.update("DELETE FROM image_attachment WHERE id=UUID_TO_BIN(?) AND conversation_id IS NULL",id)>0)Files.deleteIfExists(directory.resolve(UUID.fromString(id)+".jpg"));}
 }
}
