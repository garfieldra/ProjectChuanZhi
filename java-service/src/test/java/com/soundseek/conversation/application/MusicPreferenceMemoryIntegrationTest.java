package com.soundseek.conversation.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.soundseek.agent.repository.AgentRunRepository;
import com.soundseek.conversation.domain.*;
import com.soundseek.conversation.repository.*;
import com.soundseek.identity.AccountService;
import com.soundseek.identity.AuthFailure;
import com.soundseek.tournament.repository.GuestSessionRepository;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MusicPreferenceMemoryIntegrationTest {
  @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4").withUrlParam("sslMode","DISABLED").withUrlParam("allowPublicKeyRetrieval","true");
  @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
  @Container static GenericContainer<?> rabbit=new GenericContainer<>("rabbitmq:3.13-management-alpine").withExposedPorts(5672);
  @DynamicPropertySource static void props(DynamicPropertyRegistry p){
    p.add("spring.datasource.url",mysql::getJdbcUrl);p.add("spring.datasource.username",mysql::getUsername);p.add("spring.datasource.password",mysql::getPassword);
    p.add("spring.data.redis.url",()->"redis://"+redis.getHost()+":"+redis.getMappedPort(6379));
    p.add("spring.rabbitmq.host",rabbit::getHost);p.add("spring.rabbitmq.port",()->rabbit.getMappedPort(5672));
    p.add("soundseek.auth.mail.enabled",()->false);
  }
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired MusicPreferenceMemoryService memories;
  @Autowired ConversationApplicationService conversations;
  @Autowired ConversationRepository conversationRepository;
  @Autowired ConversationMessageRepository messages;
  @Autowired GuestSessionRepository guests;
  @Autowired AgentRunRepository runs;
  @Autowired AccountService accounts;
  final String path="/music-preference-memories";

  class Browser {
    final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).version(HttpClient.Version.HTTP_1_1).build();
    Browser()throws Exception{call("GET","/auth/session",null,200);}
    JsonNode call(String method,String route,Object body,int expected)throws Exception{
      var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+route)).header("Content-Type","application/json");
      if(!method.equals("GET"))cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).findFirst().ifPresent(c->request.header("X-XSRF-TOKEN",URLDecoder.decode(c.getValue(),StandardCharsets.UTF_8)));
      request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(expected,response.statusCode(),route+": "+response.body());
      return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
    }
    JsonNode save(String content)throws Exception{return call("POST",path,Map.of("content",content),200);}
    UUID conversation()throws Exception{return UUID.fromString(call("POST","/conversations",Map.of(),201).path("id").asText());}
    UUID owner(UUID conversation){return conversationRepository.findById(conversation).orElseThrow().getGuestSessionId();}
  }

  @Test void manualMemoryPersistsDeduplicatesEditsAndRevokesWithoutAConversation()throws Exception{
    var b=new Browser();var saved=b.save("  偏爱梦幻流行，人声不要太靠前  ");String id=saved.path("id").asText();
    assertTrue(saved.path("sourceConversationId").isNull());assertTrue(saved.path("sourceMessageId").isNull());
    assertEquals("偏爱梦幻流行，人声不要太靠前",saved.path("content").asText());assertFalse(saved.path("updatedAt").asText().isBlank());
    assertEquals(id,b.save("偏爱梦幻流行，人声不要太靠前").path("id").asText());
    assertEquals(1,b.call("GET",path,null,200).size());
    b.call("PATCH",path+"/"+id,Map.of("content","更喜欢清晰节奏，保留空间感"),200);
    assertEquals("更喜欢清晰节奏，保留空间感",b.call("GET",path,null,200).get(0).path("content").asText());
    b.call("DELETE",path+"/"+id,null,204);b.call("DELETE",path+"/"+id,null,204);
    assertEquals(0,b.call("GET",path,null,200).size());
    b.call("PATCH",path+"/"+id,Map.of("content","不能恢复撤销条目"),404);
  }

  @Test void ownersAndSourceMessagesAreCheckedBeforeSaving()throws Exception{
    var a=new Browser();var b=new Browser();UUID conversation=a.conversation();
    var message=messages.saveAndFlush(ConversationMessage.user(conversation,UUID.randomUUID(),"我喜欢人声融入伴奏",2));
    var body=Map.of("content","我喜欢人声融入伴奏","sourceConversationId",conversation,"sourceMessageId",message.getId());
    b.call("POST",path,body,404);
    var saved=a.call("POST",path,body,200);String id=saved.path("id").asText();
    assertEquals(message.getId().toString(),saved.path("sourceMessageId").asText());assertTrue(saved.path("sourceAvailable").asBoolean());
    assertEquals(0,b.call("GET",path,null,200).size());
    b.call("PATCH",path+"/"+id,Map.of("content","越权修改"),404);b.call("DELETE",path+"/"+id,null,404);
    a.call("POST",path,Map.of("content","缺少消息","sourceConversationId",conversation),400);
    UUID other=a.conversation();
    a.call("POST",path,Map.of("content","消息不属于此对话","sourceConversationId",other,"sourceMessageId",message.getId()),404);
    var agent=messages.saveAndFlush(ConversationMessage.assistant(conversation,null,"模型猜测的偏好",3));
    a.call("POST",path,Map.of("content","不能直接保存模型猜测","sourceConversationId",conversation,"sourceMessageId",agent.getId()),404);
    a.call("DELETE","/conversations/"+conversation,null,204);
    a.call("POST",path,body,404);var remaining=a.call("GET",path,null,200);assertEquals(1,remaining.size());assertFalse(remaining.get(0).path("sourceAvailable").asBoolean());
    assertTrue(messages.findById(message.getId()).isPresent());
  }

  @Test void validatesContentAndRejectsDuplicateEdits()throws Exception{
    var b=new Browser();
    b.call("POST",path,Map.of("content","   "),400);b.call("POST",path,Map.of("content","中".repeat(801)),400);
    var first=b.save("偏爱独立流行");b.save("不喜欢厚重失真");
    b.call("PATCH",path+"/"+first.path("id").asText(),Map.of("content","不喜欢厚重失真"),409);
    assertEquals("偏爱独立流行",b.call("GET",path,null,200).findValuesAsText("content").stream().filter(v->v.equals("偏爱独立流行")).findFirst().orElseThrow());
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"没有 CSRF\"}")).build();
    assertEquals(403,b.client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test void limitAndConcurrentRetriesCannotDuplicateMemory()throws Exception{
    var b=new Browser();UUID guest=b.owner(b.conversation());
    try(var pool=Executors.newFixedThreadPool(4)){
      var calls=new ArrayList<Callable<UUID>>();
      for(int i=0;i<4;i++)calls.add(()->memories.save(guest,null,null,"并发确认同一偏好").getId());
      var ids=new HashSet<UUID>();for(var result:pool.invokeAll(calls))ids.add(result.get());assertEquals(1,ids.size());
    }
    for(int i=1;i<20;i++)memories.save(guest,null,null,"长期偏好 "+i);
    var failure=assertThrows(AuthFailure.class,()->memories.save(guest,null,null,"第21条"));assertEquals("MEMORY_LIMIT_REACHED",failure.code());
    assertEquals(20,memories.confirmedContents(guest).size());
    var item=memories.list(guest).getFirst();memories.revoke(guest,item.getId());memories.save(guest,null,null,"补充的长期偏好");
    assertEquals(20,b.call("GET",path,null,200).size());
  }

  @Test void confirmedMemoriesEnterNewRunAndRemovalLeavesOriginalConversation()throws Exception{
    var b=new Browser();UUID source=b.conversation();UUID guest=b.owner(source);
    var message=messages.saveAndFlush(ConversationMessage.user(source,UUID.randomUUID(),"喜欢空间感，少一点失真",2));
    var saved=b.call("POST",path,Map.of("content","喜欢空间感，少一点失真","sourceConversationId",source,"sourceMessageId",message.getId()),200);
    UUID newConversation=b.conversation();var queued=conversations.enqueue(newConversation,guest,UUID.randomUUID(),"推荐几首适合散步的歌");
    var input=json.readTree(runs.findById(queued.runId()).orElseThrow().getContextSnapshotJson());
    assertEquals("喜欢空间感，少一点失真",input.path("confirmedMemories").get(0).asText());
    assertEquals("推荐几首适合散步的歌",input.path("userMessage").asText());
    b.call("DELETE",path+"/"+saved.path("id").asText(),null,204);
    var after=conversations.enqueue(b.conversation(),guest,UUID.randomUUID(),"这次想听厚重摇滚");
    assertTrue(json.readTree(runs.findById(after.runId()).orElseThrow().getContextSnapshotJson()).path("confirmedMemories").isEmpty());
    assertEquals("喜欢空间感，少一点失真",messages.findById(message.getId()).orElseThrow().getTextContent());
  }

  @Test void guestMemoryMergesIntoAccountAndCanBeManagedAfterLogin()throws Exception{
    var accountBrowser=new Browser();UUID accountGuest=accountBrowser.owner(accountBrowser.conversation());
    String email="memory-"+UUID.randomUUID()+"@example.invalid",password="Memory-Test-Password-123";
    accounts.register(email,password,"音乐记忆验收",guests.findById(accountGuest).orElseThrow());
    var b=new Browser();UUID source=b.conversation();UUID sourceGuest=b.owner(source);
    var message=messages.saveAndFlush(ConversationMessage.user(source,UUID.randomUUID(),"常听梦幻流行",2));
    var saved=b.call("POST",path,Map.of("content","常听梦幻流行","sourceConversationId",source,"sourceMessageId",message.getId()),200);
    b.call("POST","/auth/login",Map.of("email",email,"password",password),200);b.call("GET","/auth/session",null,200);
    assertEquals(1,b.call("GET",path,null,200).size());assertEquals(0,memories.list(sourceGuest).size());
    assertEquals(accountGuest,conversationRepository.findById(source).orElseThrow().getGuestSessionId());
    b.call("PATCH",path+"/"+saved.path("id").asText(),Map.of("content","偏爱梦幻流行，也接受清晰节奏"),200);
    b.call("DELETE",path+"/"+saved.path("id").asText(),null,204);assertTrue(memories.confirmedContents(accountGuest).isEmpty());
  }
}
