package com.soundseek.identity;

import com.soundseek.tournament.domain.GuestSession;import com.soundseek.tournament.repository.GuestSessionRepository;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.security.crypto.password.PasswordEncoder;import org.springframework.security.authentication.BadCredentialsException;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;

@Service public class AuthLifecycleService {
 @org.springframework.beans.factory.annotation.Value("${soundseek.auth.registration-minutes:30}") private int registrationMinutes=30;
 @org.springframework.beans.factory.annotation.Value("${soundseek.auth.reset-minutes:20}") private int resetMinutes=20;
 @org.springframework.beans.factory.annotation.Value("${soundseek.auth.email-minutes:30}") private int emailMinutes=30;
 private final JdbcTemplate db;private final UserAccountRepository users;private final AuthIdentityRepository identities;private final PasswordEncoder passwords;private final AccountService accounts;private final GuestSessionRepository guests;private final AuthMailService mail;
 public AuthLifecycleService(JdbcTemplate db,UserAccountRepository users,AuthIdentityRepository identities,PasswordEncoder passwords,AccountService accounts,GuestSessionRepository guests,AuthMailService mail){this.db=db;this.users=users;this.identities=identities;this.passwords=passwords;this.accounts=accounts;this.guests=guests;this.mail=mail;}
 public static void passwordPolicy(String value){if(value==null||value.codePointCount(0,value.length())<10||value.getBytes(StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("密码至少 10 个字符，UTF-8 编码不能超过 72 字节。");}
 public void event(UUID user,String action){db.update("INSERT INTO account_security_event(id,user_id,action,outcome) VALUES(?,UUID_TO_BIN(?),?,'SUCCESS')",UUID.randomUUID().toString(),user==null?null:user.toString(),action);}
 private String dedupe(String purpose,String key,String owner){if(key==null||key.isBlank()||key.length()>100)throw new IllegalArgumentException("需要有效的提交标识。");return AuthMailService.hash(purpose+":"+owner+":"+key);}
 private void challenge(String purpose,UserAccount u,String email,String hash,String name,String browser,String key,String fingerprint,int minutes){
  String token=AuthMailService.token(),id=UUID.randomUUID().toString();int ttl=purpose.equals("REGISTER")?registrationMinutes:purpose.equals("RESET")?resetMinutes:emailMinutes;Instant expires=Instant.now().plusSeconds(Math.max(1,ttl)*60L);
  db.update("UPDATE auth_challenge SET consumed_at=CURRENT_TIMESTAMP(3),password_hash=NULL WHERE consumed_at IS NULL AND purpose=? AND email=? AND (user_id=UUID_TO_BIN(?) OR (user_id IS NULL AND browser_hash=?))",purpose,email,u==null?null:u.getId().toString(),browser);
  db.update("INSERT INTO auth_challenge(id,purpose,token_hash,user_id,email,auth_version,password_hash,display_name,browser_hash,expires_at) VALUES(?,?,?,UUID_TO_BIN(?),?,?,?,?,?,?)",id,purpose,AuthMailService.hash(token),u==null?null:u.getId().toString(),email,u==null?0:u.getAuthVersion(),hash,name,browser,java.sql.Timestamp.from(expires));
  mail.queue(id,key,fingerprint,email,purpose,token,expires);
  if(u!=null)db.update("UPDATE auth_mail_job SET user_id=UUID_TO_BIN(?) WHERE dedupe_key=?",u.getId().toString(),key);
 }
 @Transactional public void registration(String email,String password,String name,String browser,String key){
  mail.requireAvailable();passwordPolicy(password);email=AccountService.normalize(email);String d=dedupe("REGISTER",key,browser),fp=mail.fingerprint(email+":"+password+":"+name);
  if(mail.replay(d,fp))return;if(users.findByEmailNormalized(email).isPresent())return;
  challenge("REGISTER",null,email,passwords.encode(password),name,browser,d,fp,30);
 }
 private Map<String,Object> find(String token,String purpose,boolean lock){
  if(token==null||token.length()>100)throw AuthFailure.invalid();
  var rows=db.queryForList("SELECT c.*,BIN_TO_UUID(user_id) AS user_uuid FROM auth_challenge c WHERE token_hash=? AND purpose=? AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP(3)"+(lock?" FOR UPDATE":""),AuthMailService.hash(token),purpose);
  if(rows.isEmpty())throw AuthFailure.invalid();return rows.getFirst();
 }
 @Transactional public UserAccount confirmRegistration(String token,String password,String browser,GuestSession guest){
  guests.findByIdForUpdate(guest.getId()).orElseThrow();var c=find(token,"REGISTER",true);
  if(!Objects.equals(c.get("browser_hash"),browser)&&(password==null||password.length()>128||!passwords.matches(password,(String)c.get("password_hash"))))throw AuthFailure.invalid();
  if(users.findByEmailNormalized((String)c.get("email")).isPresent())throw AuthFailure.invalid();
  var u=accounts.register((String)c.get("email"),"unused-registration-secret",(String)c.get("display_name"),guest);
  identities.findByUserIdAndProvider(u.getId(),"PASSWORD").orElseThrow().changePassword((String)c.get("password_hash"));
  u.verifyEmail();consume(c);event(u.getId(),"REGISTER_CONFIRMED");return u;
 }
 @Transactional public void requestReset(String email,String key){
  mail.requireAvailable();email=AccountService.normalize(email);String d=dedupe("RESET",key,email),fp=mail.fingerprint(email);
  if(mail.replay(d,fp))return;var found=users.findByEmailNormalized(email);if(found.isEmpty())return;
  var u=users.lockById(found.get().getId()).orElseThrow();if(!u.isEmailVerified()||!"ACTIVE".equals(u.getStatus())||identities.findByUserIdAndProvider(u.getId(),"PASSWORD").isEmpty())return;
  challenge("RESET",u,email,null,null,null,d,fp,20);
 }
 @Transactional public void reset(String token,String password){
  passwordPolicy(password);var initial=find(token,"RESET",false);var u=locked(UUID.fromString((String)initial.get("user_uuid")));var c=find(token,"RESET",true);checkVersion(c,u);
  identities.findByUserIdAndProvider(u.getId(),"PASSWORD").orElseThrow(AuthFailure::invalid).changePassword(passwords.encode(password));u.revokeSessions();consume(c);invalidate(u);notify(u,u.getEmailNormalized(),"PASSWORD_RESET");event(u.getId(),"PASSWORD_RESET");
 }
 public UserAccount locked(UUID id){var u=users.lockById(id).orElseThrow(()->new BadCredentialsException("invalid credentials"));AccountService.requireActive(u);return u;}
 private void checkVersion(Map<String,Object> c,UserAccount u){if(((Number)c.get("auth_version")).longValue()!=u.getAuthVersion())throw AuthFailure.invalid();}
 private void consume(Map<String,Object> c){db.update("UPDATE auth_challenge SET consumed_at=CURRENT_TIMESTAMP(3),password_hash=NULL,browser_hash=NULL WHERE id=?",c.get("id"));}
 private void invalidate(UserAccount u){db.update("UPDATE auth_challenge SET consumed_at=CURRENT_TIMESTAMP(3),password_hash=NULL WHERE user_id=UUID_TO_BIN(?) AND consumed_at IS NULL",u.getId().toString());}
 private void notify(UserAccount u,String email,String action){if(email==null||!u.isEmailVerified())return;String d=AuthMailService.hash(UUID.randomUUID().toString());mail.queue(null,d,d,email,action,null,Instant.now().plusSeconds(3600));db.update("UPDATE auth_mail_job SET user_id=UUID_TO_BIN(?) WHERE dedupe_key=?",u.getId().toString(),d);}
 @Transactional public void verifyRequest(UUID id,String key){
  mail.requireAvailable();var u=locked(id);if(u.isEmailVerified())return;if(u.getEmailNormalized()==null)throw new IllegalArgumentException("请先添加真实联系邮箱。");String d=dedupe("VERIFY",key,id.toString()),fp=mail.fingerprint(u.getEmailNormalized());if(mail.replay(d,fp))return;challenge("VERIFY",u,u.getEmailNormalized(),null,null,null,d,fp,30);
 }
 @Transactional public UserAccount confirmEmail(UUID id,String token,String purpose){
  if(!Set.of("VERIFY","CHANGE_EMAIL").contains(purpose))throw AuthFailure.invalid();var u=locked(id);var c=find(token,purpose,true);
  if(!id.toString().equals(c.get("user_uuid")))throw AuthFailure.invalid();checkVersion(c,u);String email=(String)c.get("email");
  if("VERIFY".equals(purpose)){if(!email.equals(u.getEmailNormalized()))throw AuthFailure.invalid();u.verifyEmail();}
  else{var existing=users.findByEmailNormalized(email);if(existing.isPresent()&&!existing.get().getId().equals(id))throw new IllegalArgumentException("此邮箱暂不可用。");String old=u.getEmailNormalized();notify(u,old,"EMAIL_CHANGED");u.changeEmail(email);identities.findByUserIdAndProvider(id,"PASSWORD").ifPresent(i->i.changeSubject(email));invalidate(u);notify(u,email,"EMAIL_CHANGED");}
  consume(c);event(id,purpose+"_CONFIRMED");return u;
 }
 @Transactional public void changeEmailRequest(UUID id,String email,String key){
  mail.requireAvailable();var u=locked(id);email=AccountService.normalize(email);var existing=users.findByEmailNormalized(email);if(existing.isPresent())throw new IllegalArgumentException("此邮箱暂不可用。");String d=dedupe("CHANGE_EMAIL",key,id.toString()),fp=mail.fingerprint(email);if(mail.replay(d,fp))return;challenge("CHANGE_EMAIL",u,email,null,null,null,d,fp,30);
 }
 public boolean hasPassword(UUID id){return identities.findByUserIdAndProvider(id,"PASSWORD").isPresent();}
 public void checkPassword(UserAccount u,String password){var identity=identities.findByUserIdAndProvider(u.getId(),"PASSWORD").orElseThrow(()->new BadCredentialsException("invalid credentials"));if(password==null||password.length()>128||!passwords.matches(password,identity.getPasswordHash()))throw new BadCredentialsException("invalid credentials");}
 @Transactional public UserAccount changePassword(UUID id,String current,String next){passwordPolicy(next);var u=locked(id);checkPassword(u,current);identities.findByUserIdAndProvider(id,"PASSWORD").orElseThrow().changePassword(passwords.encode(next));u.revokeSessions();invalidate(u);if(u.isEmailVerified()){mail.requireAvailable();notify(u,u.getEmailNormalized(),"PASSWORD_CHANGED");}event(id,"PASSWORD_CHANGED");return u;}
 @Transactional public void logoutAll(UUID id){var u=locked(id);u.revokeSessions();invalidate(u);event(id,"LOGOUT_ALL");}
 @Transactional public UserAccount login(String email,String password,long expectedVersion,GuestSession guest){var found=users.findByEmailNormalized(AccountService.normalize(email)).orElseThrow(()->new BadCredentialsException("invalid credentials"));var u=locked(found.getId());if(u.getAuthVersion()!=expectedVersion)throw new BadCredentialsException("invalid credentials");checkPassword(u,password);var result=accounts.completePasswordLogin(email,guest);event(u.getId(),"LOGIN");return result;}
}
