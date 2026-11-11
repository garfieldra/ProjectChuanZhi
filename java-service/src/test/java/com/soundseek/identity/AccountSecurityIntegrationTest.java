package com.soundseek.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;import java.net.*;import java.net.http.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.regex.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.web.server.LocalServerPort;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.data.redis.core.StringRedisTemplate;import org.springframework.test.context.*;import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;import org.testcontainers.containers.MySQLContainer;import org.testcontainers.junit.jupiter.*;

@Testcontainers @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AccountSecurityIntegrationTest {
 @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4").withUrlParam("sslMode","DISABLED").withUrlParam("allowPublicKeyRetrieval","true");
 @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
 @Container static GenericContainer<?> rabbit=new GenericContainer<>("rabbitmq:3.13-management-alpine").withExposedPorts(5672);
 static final Smtp smtp=new Smtp();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
  p.add("spring.datasource.url",mysql::getJdbcUrl);p.add("spring.datasource.username",mysql::getUsername);p.add("spring.datasource.password",mysql::getPassword);
  p.add("spring.data.redis.url",()->"redis://host.docker.internal:"+redis.getMappedPort(6379));
  p.add("spring.rabbitmq.host",()->"host.docker.internal");p.add("spring.rabbitmq.port",()->rabbit.getMappedPort(5672));
  p.add("soundseek.auth.mail.enabled",()->true);p.add("soundseek.auth.mail.from",()->"sender@example.invalid");p.add("soundseek.auth.mail.public-base-url",()->"http://localhost:5173");
  p.add("soundseek.auth.mail.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));p.add("soundseek.auth.mail.poll-ms",()->3600000);
  p.add("spring.mail.host",()->"127.0.0.1");p.add("spring.mail.port",()->smtp.port());p.add("spring.mail.properties.mail.smtp.auth",()->false);p.add("spring.mail.properties.mail.smtp.starttls.enable",()->false);p.add("spring.mail.properties.mail.smtp.starttls.required",()->false);
 }
 @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired AuthMailService mail;@Autowired AuthLifecycleService lifecycle;@Autowired AccountOperations operations;@Autowired StringRedisTemplate redisClient;@Autowired AccountService accounts;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;@Autowired UserAccountRepository users;
 @LocalServerPort int port;
 @BeforeEach void resetLimits(){var keys=redisClient.keys("soundseek:security:*");if(keys!=null&&!keys.isEmpty())redisClient.delete(keys);}
 @AfterAll static void close() throws Exception{smtp.close();}
 String email(){return "account-"+UUID.randomUUID()+"@example.invalid";}
 final String password="Account-Test-Password-123";
 class Browser {
  CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
  HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).version(HttpClient.Version.HTTP_1_1).build();
  Browser() throws Exception{get("/auth/session",200);}
  JsonNode get(String path,int status)throws Exception{return call("GET",path,null,status);}
  JsonNode post(String path,Object body,int status)throws Exception{return call("POST",path,body,status);}
  JsonNode call(String method,String path,Object body,int expected)throws Exception{
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString());
   if(!method.equals("GET"))cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).findFirst().ifPresent(c->request.header("X-XSRF-TOKEN",URLDecoder.decode(c.getValue(),StandardCharsets.UTF_8)));
   request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
   var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),path+": "+response.body());
   return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
  }
  void refresh()throws Exception{get("/auth/session",200);}
 }
 String token(String purpose,String email)throws Exception{
  var rows=db.queryForList("SELECT j.ciphertext FROM auth_mail_job j JOIN auth_challenge c ON c.id=j.challenge_id WHERE c.purpose=? AND c.email=? AND j.ciphertext IS NOT NULL ORDER BY j.created_at DESC",purpose,email);
  assertFalse(rows.isEmpty());String encrypted=(String)rows.getFirst().get("ciphertext");String payload=mail.decrypt(encrypted);var match=Pattern.compile("&token=([A-Za-z0-9_-]+)").matcher(payload);assertTrue(match.find());String token=match.group(1);assertFalse(encrypted.contains(token));return token;
 }
 JsonNode register(Browser b,String email)throws Exception{b.post("/auth/register",Map.of("email",email,"password",password,"displayName","账号验收"),202);String t=token("REGISTER",email);var value=b.post("/auth/registration/confirm",Map.of("token",t),200);b.refresh();return value;}
 @Test void registrationRequiresOwnershipSendsRealSmtpAndRevokesOldGuestAccess()throws Exception{
  var b=new Browser();String email=email();var conversation=b.post("/conversations",Map.of(),201);String id=conversation.path("id").asText();
  b.post("/auth/register",Map.of("email",email,"password",password),202);
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_account WHERE email_normalized=?",Integer.class,email));
  String t=token("REGISTER",email);var other=new Browser();other.post("/auth/registration/confirm",Map.of("token",t,"password","wrong-password"),400);
  mail.deliver();String received=smtp.messages.poll(10,TimeUnit.SECONDS);assertNotNull(received);assertTrue(received.contains("Message-ID:"));
  var account=b.post("/auth/registration/confirm",Map.of("token",t),200);assertTrue(account.path("emailVerified").asBoolean());assertTrue(account.path("authenticated").asBoolean());b.refresh();
  b.get("/conversations/"+id,200);b.get("/admin/knowledge/access",403);
  b.post("/auth/logout",Map.of(),200);b.refresh();b.get("/conversations/"+id,404);
  other.post("/auth/registration/confirm",Map.of("token",t,"password",password),400);
 }
 @Test void resetIsOneTimeAndInvalidatesAllExistingSessions()throws Exception{
  var b=new Browser();String email=email();register(b,email);var other=new Browser();other.post("/auth/login",Map.of("email",email,"password",password),200);other.refresh();resetLimits();
  var recovery=new Browser();recovery.post("/auth/password-reset/request",Map.of("email",email),202);String t=token("RESET",email);
  recovery.post("/auth/password-reset/confirm",Map.of("token",t,"password","New-Password-456789"),200);
  b.get("/me/saved-songs",401);other.get("/me/saved-songs",401);recovery.post("/auth/password-reset/confirm",Map.of("token",t,"password","Another-Password-123"),400);
  recovery.post("/auth/login",Map.of("email",email,"password",password),401);recovery.post("/auth/login",Map.of("email",email,"password","New-Password-456789"),200);
 }
 @Test void passwordEmailAndGlobalLogoutRequireCredentialsAndInvalidateOldSessions()throws Exception{
  var b=new Browser();String email=email();var account=register(b,email);var other=new Browser();other.post("/auth/login",Map.of("email",email,"password",password),200);other.refresh();
  b.post("/me/password",Map.of("currentPassword","wrong","password","New-Password-456789"),401);
  b.post("/me/password",Map.of("currentPassword",password,"password","New-Password-456789"),200);b.refresh();other.get("/me/saved-songs",401);
  String next=email();b.post("/me/email-change/request",Map.of("email",next),401);b.post("/auth/reauth",Map.of("password","New-Password-456789"),200);b.post("/me/email-change/request",Map.of("email",next),202);
  String t=token("CHANGE_EMAIL",next);b.post("/me/email-change/confirm",Map.of("token",t),200);b.refresh();assertEquals(next,b.get("/auth/session",200).path("email").asText());
  b.post("/auth/reauth",Map.of("password","New-Password-456789"),200);b.post("/auth/logout-all",Map.of(),200);b.refresh();assertFalse(b.get("/auth/session",200).path("authenticated").asBoolean());
  assertEquals(account.path("userId").asText(),accounts.current(account.path("userId").asText()).orElseThrow().getId().toString());
 }
 @Test void explicitUuidAndVerificationAreRequiredForMaintenanceAndDisablePreservesAssets()throws Exception{
  var b=new Browser();String email=email();var account=register(b,email);String id=account.path("userId").asText();
  var access=new com.soundseek.knowledge.KnowledgeAccess(accounts,id);assertEquals(id,access.require(()->id));
  var emailAccess=new com.soundseek.knowledge.KnowledgeAccess(accounts,email);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->emailAccess.require(()->id));
  b.post("/conversations",Map.of(),201);var preview=operations.preview(UUID.fromString(id));assertEquals(false,preview.get("physicalDeletionSupported"));
  operations.disable(UUID.fromString(id),"isolated-test-only");b.get("/me/saved-songs",401);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->access.require(()->id));
  assertEquals(preview.get("conversations"),operations.preview(UUID.fromString(id)).get("conversations"));
  var other=new Browser();other.post("/auth/login",Map.of("email",email,"password",password),401);
 }
 @Test void expiredWrongPurposeAndConcurrentRecoveryCannotChangeCredentialsTwice()throws Exception{
  var b=new Browser();String email=email();register(b,email);resetLimits();var recovery=new Browser();
  recovery.post("/auth/password-reset/request",Map.of("email",email),202);String t=token("RESET",email);
  recovery.post("/auth/registration/confirm",Map.of("token",t,"password",password),400);
  var one=new Browser();var two=new Browser();var pool=Executors.newFixedThreadPool(2);
  try{
   var tasks=List.<Callable<Integer>>of(()->recoveryAttempt(one,t,"Recovered-Password-111"),()->recoveryAttempt(two,t,"Recovered-Password-222"));
   var result=pool.invokeAll(tasks);var statuses=List.of(result.get(0).get(),result.get(1).get());assertTrue(statuses.contains(200));assertTrue(statuses.contains(400));
  }finally{pool.shutdownNow();}
  resetLimits();recovery.post("/auth/password-reset/request",Map.of("email",email),202);String expired=token("RESET",email);
  db.update("UPDATE auth_challenge SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 MINUTE) WHERE token_hash=?",AuthMailService.hash(expired));
  recovery.post("/auth/password-reset/confirm",Map.of("token",expired,"password","Expired-Password-123"),400);
 }
 int recoveryAttempt(Browser b,String token,String next)throws Exception{
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/password-reset/confirm")).header("Content-Type","application/json");
  b.cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).findFirst().ifPresent(c->request.header("X-XSRF-TOKEN",URLDecoder.decode(c.getValue(),StandardCharsets.UTF_8)));
  return b.client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("token",token,"password",next)))).build(),HttpResponse.BodyHandlers.ofString()).statusCode();
 }
 @Test void mailRequestsAreIdempotentWithoutReplacingTheOriginalPassword()throws Exception{
  String email=email(),browser=AuthMailService.hash("test-browser-"+UUID.randomUUID()),key=UUID.randomUUID().toString();
  lifecycle.registration(email,password,"幂等测试",browser,key);String t=token("REGISTER",email);
  lifecycle.registration(email,password,"幂等测试",browser,key);
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM auth_challenge WHERE email=?",Integer.class,email));
  assertThrows(AuthFailure.class,()->lifecycle.registration(email,"Changed-Password-123","幂等测试",browser,key));
  assertEquals(t,token("REGISTER",email));
 }
 @Test void concurrentProfileUpdateCannotRestoreRevokedSessionVersion()throws Exception{
  var b=new Browser();var account=register(b,email());UUID id=UUID.fromString(account.path("userId").asText());
  long version=users.findById(id).orElseThrow().getAuthVersion();var read=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
  try{var updating=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{var u=users.findById(id).orElseThrow();read.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){throw new RuntimeException(e);}u.rename("并发昵称");return null;}));
   assertTrue(read.await(10,TimeUnit.SECONDS));lifecycle.logoutAll(id);release.countDown();updating.get(15,TimeUnit.SECONDS);
   var updated=users.findById(id).orElseThrow();assertEquals(version+1,updated.getAuthVersion());assertEquals("并发昵称",updated.getDisplayName());b.get("/me/saved-songs",401);
  }finally{release.countDown();pool.shutdownNow();}
 }
 @Test void csrfAndMailRateLimitAreEnforced()throws Exception{
  var raw=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/register")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,raw.statusCode());
  var b=new Browser();String email=email();b.post("/auth/password-reset/request",Map.of("email",email),202);b.post("/auth/password-reset/request",Map.of("email",email),429);
 }
 static class Smtp implements AutoCloseable {
  final ServerSocket server;final BlockingQueue<String> messages=new LinkedBlockingQueue<>();
  Smtp(){try{server=new ServerSocket(0);Thread.startVirtualThread(()->{while(!server.isClosed())try{var socket=server.accept();Thread.startVirtualThread(()->serve(socket));}catch(IOException ignored){}});}catch(IOException e){throw new RuntimeException(e);}}
  int port(){return server.getLocalPort();}
  void serve(Socket socket){try(socket){var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);out.print("220 test.local SMTP\r\n");out.flush();String line;while((line=in.readLine())!=null){if(line.startsWith("EHLO")||line.startsWith("HELO"))out.print("250 test.local\r\n");else if(line.equals("DATA")){out.print("354 send\r\n");out.flush();var data=new StringBuilder();while((line=in.readLine())!=null&&!line.equals("."))data.append(line).append("\n");messages.add(data.toString());out.print("250 accepted\r\n");}else if(line.equals("QUIT")){out.print("221 bye\r\n");out.flush();return;}else out.print("250 OK\r\n");out.flush();}}catch(IOException ignored){}}
  public void close()throws Exception{server.close();}
 }
}
