package com.soundseek.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KnowledgeMergeIntegrationTest {
  @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4").withUrlParam("sslMode","DISABLED").withUrlParam("allowPublicKeyRetrieval","true");
  @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
  @Container static GenericContainer<?> rabbit=new GenericContainer<>("rabbitmq:3.13-management-alpine")
      .withEnv("RABBITMQ_DEFAULT_USER","merge_test").withEnv("RABBITMQ_DEFAULT_PASS","merge-test-only-password").withExposedPorts(5672);
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url",mysql::getJdbcUrl);
    registry.add("spring.datasource.username",mysql::getUsername);
    registry.add("spring.datasource.password",mysql::getPassword);
    registry.add("spring.data.redis.url",()->"redis://"+redis.getHost()+":"+redis.getMappedPort(6379)+"/0");
    registry.add("spring.rabbitmq.host",rabbit::getHost);
    registry.add("spring.rabbitmq.port",()->rabbit.getMappedPort(5672));
    registry.add("spring.rabbitmq.username",()->"merge_test");
    registry.add("spring.rabbitmq.password",()->"merge-test-only-password");
    registry.add("agent.internal.service-token",()->"merge-test-only-token");
  }
  @Autowired MockMvc mvc;
  @Autowired AmqpAdmin queues;

  @Test void discoversKnowledgeAndSavedSongEndpointsAndDeclaresMatchingQueueNames() throws Exception {
    mvc.perform(get("/internal/v1/knowledge/generation").header("Authorization","Bearer merge-test-only-token"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.config.factCollection").value("soundseek_fact_evidence_g1"));
    mvc.perform(get("/api/v1/admin/knowledge/access")).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/me/saved-songs")).andExpect(status().isOk());
    assertNotNull(queues.getQueueProperties("soundseek.knowledge-index.v1"));
    assertNotNull(queues.getQueueProperties("soundseek.agent-runs.v1"));
  }
}
