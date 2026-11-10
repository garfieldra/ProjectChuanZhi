package com.soundseek.knowledge;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Checks the exact worker template with the pinned model's tokenizer before publication. */
@Component
public class KnowledgeIndexPreflight {
  private final RestClient agent;
  private final String token;
  public KnowledgeIndexPreflight(@Value("${agent.internal.base-url}") String url,
      @Value("${agent.internal.service-token}") String token) {
    this.token=token;
    var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(90));
    agent=RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }
  public void validate(Map<String,Object> content, Map<String,Object> config) {
    try {
      var result=agent.post().uri("/internal/knowledge/validate-input")
          .header("Authorization","Bearer "+token).body(Map.of("content",content,"config",config))
          .retrieve().onStatus(status -> status.value()==422, (request,response) -> {
            throw new IllegalArgumentException("资料超过当前嵌入模型的输入长度，请缩短表述、别名或实体列表，或拆分成多条资料后重新审核发布。");
          }).body(Map.class);
      if(result==null || !Boolean.TRUE.equals(result.get("valid")))throw new RestClientException("INVALID_PREFLIGHT_RESPONSE");
    } catch(RestClientException unavailable) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"暂时无法检查知识索引输入，资料未发布，请稍后重试。");
    }
  }
}
