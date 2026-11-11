package com.soundseek.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soundseek.async.AsyncOutboxEventRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeControllerTest {
  @Test void forwardsJsonWithoutHttp2Upgrade() throws Exception {
    var upgrade=new AtomicReference<String>();var received=new AtomicReference<String>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/knowledge/search",exchange->{
      upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      byte[] body="{\"status\":\"no_match\",\"evidence\":[]}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    });
    server.start();
    try {
      var access=mock(KnowledgeAccess.class);var service=new KnowledgeService(mock(JdbcTemplate.class),new ObjectMapper(),mock(jakarta.validation.Validator.class),mock(AsyncOutboxEventRepository.class),mock(KnowledgeIndexPreflight.class));
      var controller=new KnowledgeController(access,service,"http://127.0.0.1:"+server.getAddress().getPort(),"test-token");
      Object result=controller.search(()->"operator",null,new KnowledgeContracts.Search("音乐背景",List.of(),"STRICT",List.of("FACT"),6));
      assertEquals("no_match",((Map<?,?>)result).get("status"));assertNull(upgrade.get());
      assertEquals("音乐背景",new ObjectMapper().readTree(received.get()).get("query").asText());
    } finally {server.stop(0);}
  }
}
