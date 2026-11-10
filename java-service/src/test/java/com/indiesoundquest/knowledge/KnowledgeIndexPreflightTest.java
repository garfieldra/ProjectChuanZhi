package com.indiesoundquest.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class KnowledgeIndexPreflightTest {
  @Test void validatesTemplateAndSurfacesLengthAndAvailabilityFailures() throws Exception {
    var status=new AtomicInteger(200);var body=new AtomicReference<String>();var auth=new AtomicReference<String>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/knowledge/validate-input",exchange->{
      body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] response=(status.get()==200?"{\"valid\":true}":"{\"detail\":\"KNOWLEDGE_INPUT_TOO_LONG\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type","application/json");
      exchange.sendResponseHeaders(status.get(),response.length);exchange.getResponseBody().write(response);exchange.close();
    });
    server.start();
    try {
      var validator=new KnowledgeIndexPreflight("http://127.0.0.1:"+server.getAddress().getPort(),"test-token");
      Map<String,Object> content=Map.of("summary","待审核资料"),config=Map.of("modelRevision","a".repeat(40));
      validator.validate(content,config);
      assertEquals("Bearer test-token",auth.get());
      assertEquals("待审核资料",new ObjectMapper().readTree(body.get()).path("content").path("summary").asText());
      status.set(422);
      assertTrue(assertThrows(IllegalArgumentException.class,()->validator.validate(content,config)).getMessage().contains("拆分"));
      status.set(503);
      assertEquals(503,assertThrows(ResponseStatusException.class,()->validator.validate(content,config)).getStatusCode().value());
    } finally {server.stop(0);}
  }
}
