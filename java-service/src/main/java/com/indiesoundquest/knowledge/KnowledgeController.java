package com.indiesoundquest.knowledge;

import static com.indiesoundquest.knowledge.KnowledgeContracts.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/v1/admin/knowledge")
public class KnowledgeController {
  private final KnowledgeAccess access;
  private final KnowledgeService service;
  private final RestClient agent;
  private final String token;
  public KnowledgeController(KnowledgeAccess access,KnowledgeService service,
      @Value("${agent.internal.base-url}") String url,@Value("${agent.internal.service-token}") String token) {
    this.access=access;this.service=service;this.token=token;
    var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(3)).build());
    factory.setReadTimeout(java.time.Duration.ofSeconds(90));
    this.agent=RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }
  @GetMapping("/access") Map<String,Object> access(Principal p){access.require(p);return Map.of("maintainer",true);}
  @GetMapping("/entries") Object entries(Principal p){access.require(p);return service.list();}
  @GetMapping("/entries/{id}") Object detail(Principal p,@PathVariable String id){access.require(p);return service.detail(id);}
  // Validate each item in the service so a bad item does not hide results for a valid batch.
  @PostMapping("/imports") Object ingest(Principal p,@RequestHeader("Idempotency-Key") String key,@RequestBody ImportRequest body){return service.ingest(access.require(p),key,body);}
  @PutMapping("/entries/{id}") Object revise(Principal p,@PathVariable String id,@RequestParam int expectedRevision,@Valid @RequestBody Submission body){return service.revise(access.require(p),id,expectedRevision,body);}
  @PostMapping("/entries/{id}/review") Object review(Principal p,@PathVariable String id,@Valid @RequestBody Decision body){return service.review(access.require(p),id,body);}
  @PostMapping("/entries/{id}/publish") Object publish(Principal p,@PathVariable String id,@Valid @RequestBody Publish body){return service.publish(access.require(p),id,body.revision());}
  @PostMapping("/entries/{id}/retract") void retract(Principal p,@PathVariable String id,@Valid @RequestBody Reason body){service.retract(access.require(p),id,body.reason());}
  @GetMapping("/sources") Object sources(Principal p){access.require(p);return service.sources();}
  @PostMapping("/sources/{id}/retract") void retractSource(Principal p,@PathVariable String id,@Valid @RequestBody Reason body){service.retractSource(access.require(p),id,body.reason());}
  @GetMapping("/jobs") Object jobs(Principal p){access.require(p);return service.jobs();}
  @PostMapping("/jobs/{id}/retry") void retry(Principal p,@PathVariable String id){service.retry(access.require(p),id);}
  @GetMapping("/audit") Object audit(Principal p){access.require(p);return service.auditLog();}
  @GetMapping("/generations") Object generations(Principal p){access.require(p);return service.generations();}
  @PostMapping("/generations") Object createGeneration(Principal p,@Valid @RequestBody Generation body){return service.createGeneration(access.require(p),body);}
  @PostMapping("/generations/{id}/activate") void activate(Principal p,@PathVariable int id){service.activate(access.require(p),id);}
  @PostMapping("/generations/{id}/validate") void validateGeneration(Principal p,@PathVariable int id,@Valid @RequestBody Reason body){service.validateGeneration(access.require(p),id,body.reason());}
  @PostMapping("/reconcile") Object reconcile(Principal p){access.require(p);return service.reconcile();}
  @PostMapping("/search") Object search(Principal p,@RequestParam(required=false) Integer generationId,@Valid @RequestBody Search body){access.require(p);var payload=service.read(service.write(body));if(generationId!=null)payload.put("generationId",generationId);return agent.post().uri("/internal/knowledge/search").header("Authorization","Bearer "+token).body(payload).retrieve().body(Map.class);}
  @PostMapping("/read-source") Object readSource(Principal p,@RequestBody Map<String,String> body){access.require(p);return agent.post().uri("/internal/knowledge/read-source").header("Authorization","Bearer "+token).body(body).retrieve().body(Map.class);}
  @PostMapping("/extract") Object extract(Principal p,@RequestBody Map<String,String> body){access.require(p);String text=body.getOrDefault("text","");if(text.isBlank()||text.length()>12000)throw new IllegalArgumentException("MATERIAL_LENGTH_INVALID");return agent.post().uri("/internal/knowledge/extract").header("Authorization","Bearer "+token).body(body).retrieve().body(Map.class);}
}
