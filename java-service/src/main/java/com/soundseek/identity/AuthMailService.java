package com.soundseek.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;import java.security.*;import java.time.*;import java.util.*;
import javax.crypto.Cipher;import javax.crypto.spec.GCMParameterSpec;import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.mail.SimpleMailMessage;import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;

/** Transactional, encrypted SMTP outbox. Never includes tokens or provider exceptions in logs. */
@Service public class AuthMailService {
 private final JdbcTemplate db;private final ObjectMapper json;private final ObjectProvider<JavaMailSender> senders;
 private final boolean enabled;private final String from,base,key;
 private static final SecureRandom RANDOM=new SecureRandom();
 public AuthMailService(JdbcTemplate db,ObjectMapper json,ObjectProvider<JavaMailSender> senders,
  @Value("${soundseek.auth.mail.enabled:false}")boolean enabled,@Value("${soundseek.auth.mail.from:}")String from,
  @Value("${soundseek.auth.mail.public-base-url:http://localhost:5173}")String base,@Value("${soundseek.auth.mail.encryption-key:}")String key){this.db=db;this.json=json;this.senders=senders;this.enabled=enabled;this.from=from;this.base=base.replaceAll("/+$","");this.key=key;}
 public void requireAvailable(){
  try{var uri=java.net.URI.create(base);if(!enabled||from.isBlank()||senders.getIfAvailable()==null||(senders.getIfAvailable() instanceof org.springframework.mail.javamail.JavaMailSenderImpl impl && (impl.getHost()==null||impl.getHost().isBlank()))||!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||Base64.getDecoder().decode(key).length!=32)throw new IllegalArgumentException();}
  catch(Exception e){throw new AuthFailure(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_MAIL_UNAVAILABLE","邮件服务暂不可用，请稍后重试。");}
 }
 public static String token(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public String fingerprint(String value){try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(Base64.getDecoder().decode(key),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("AUTH_MAIL_KEY_INVALID");}}
 public String encrypt(String value){try{byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(key),"AES"),new GCMParameterSpec(128,nonce));byte[] encrypted=c.doFinal(value.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());}catch(Exception e){throw new IllegalStateException("AUTH_MAIL_ENCRYPT_FAILED");}}
 String decrypt(String value){try{byte[] all=Base64.getDecoder().decode(value);var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(key),"AES"),new GCMParameterSpec(128,Arrays.copyOf(all,12)));return new String(c.doFinal(all,12,all.length-12),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("AUTH_MAIL_DECRYPT_FAILED");}}
 public boolean replay(String dedupe,String fingerprint){var rows=db.queryForList("SELECT input_hash FROM auth_mail_job WHERE dedupe_key=?",dedupe);if(rows.isEmpty())return false;if(!fingerprint.equals(rows.getFirst().get("input_hash")))throw new AuthFailure(HttpStatus.CONFLICT,"AUTH_IDEMPOTENCY_CONFLICT","请为修改后的请求使用新的提交标识。");return true;}
 public void queue(String challenge,String dedupe,String fingerprint,String to,String purpose,String token,Instant expiry){
  var subject=switch(purpose){case "REGISTER"->"确认注册寻声账号";case "RESET"->"重置寻声账号密码";case "VERIFY"->"验证寻声账号邮箱";case "CHANGE_EMAIL"->"确认新的寻声邮箱";default->"寻声账号安全通知";};
  String body=token==null?"你的寻声账号安全设置已更新。如果不是你本人操作，请联系站点维护者。":"请打开以下链接并在页面确认操作：\n"+base+"/#auth-confirm?purpose="+purpose+"&token="+token+"\n\n链接仅限本次操作使用，到期后失效。如非本人操作，请忽略本邮件。";
  try{String payload=json.writeValueAsString(Map.of("to",to,"subject",subject,"body",body));db.update("INSERT INTO auth_mail_job(id,challenge_id,dedupe_key,input_hash,ciphertext,expires_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),challenge,dedupe,fingerprint,encrypt(payload),java.sql.Timestamp.from(expiry));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("AUTH_MAIL_SERIALIZE_FAILED");}
 }
 @Scheduled(fixedDelayString="${soundseek.auth.mail.poll-ms:5000}") @Transactional public void deliver(){
  if(!enabled)return;
  db.update("UPDATE auth_mail_job SET status='EXPIRED',ciphertext=NULL WHERE ciphertext IS NOT NULL AND expires_at<=CURRENT_TIMESTAMP(3)");
  var rows=db.queryForList("SELECT * FROM auth_mail_job WHERE status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3) AND expires_at>CURRENT_TIMESTAMP(3) ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
  if(rows.isEmpty())return;var row=rows.getFirst();String id=(String)row.get("id");
  if(row.get("challenge_id")!=null&&db.queryForObject("SELECT COUNT(*) FROM auth_challenge WHERE id=? AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP(3)",Integer.class,row.get("challenge_id"))==0){db.update("UPDATE auth_mail_job SET status='EXPIRED',ciphertext=NULL WHERE id=?",id);return;}
  try{requireAvailable();var payload=json.readTree(decrypt((String)row.get("ciphertext")));var message=new SimpleMailMessage();message.setFrom(from);message.setTo(payload.path("to").asText());message.setSubject(payload.path("subject").asText());message.setText(payload.path("body").asText());senders.getObject().send(message);db.update("UPDATE auth_mail_job SET status='SENT',ciphertext=NULL,error_code=NULL WHERE id=?",id);}
  catch(Exception ignored){int attempts=((Number)row.get("attempts")).intValue()+1;db.update("UPDATE auth_mail_job SET attempts=?,status=?,ciphertext=CASE WHEN ?>=5 THEN NULL ELSE ciphertext END,next_attempt_at=?,error_code='SMTP_DELIVERY_FAILED' WHERE id=?",attempts,attempts>=5?"DEAD":"PENDING",attempts,java.sql.Timestamp.from(Instant.now().plusSeconds(Math.min(300,15L<<attempts))),id);}
 }
 @Scheduled(fixedDelayString="${soundseek.auth.mail.cleanup-ms:3600000}") @Transactional public void cleanup(){
  db.update("UPDATE auth_challenge SET password_hash=NULL,browser_hash=NULL WHERE password_hash IS NOT NULL AND (consumed_at IS NOT NULL OR expires_at<CURRENT_TIMESTAMP(3))");
  db.update("DELETE FROM auth_challenge WHERE expires_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 7 DAY)");
  db.update("DELETE FROM auth_mail_job WHERE created_at<DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 7 DAY) AND ciphertext IS NULL");
 }
}
