package com.soundseek.knowledge;

import static com.soundseek.knowledge.KnowledgeContracts.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Authentication for this namespace is enforced by InternalServiceAuthenticationFilter. */
@RestController
@RequestMapping("/internal/v1/knowledge")
public class KnowledgeInternalController {
  private final KnowledgeService service;
  public KnowledgeInternalController(KnowledgeService service){this.service=service;}
  @GetMapping("/generation") Object generation(){return service.activeGeneration();}
  @GetMapping("/generations/{id}") Object generation(@PathVariable int id){return service.generation(id);}
  @PostMapping("/scope") Object scope(@Valid @RequestBody Search body){return service.expand(body);}
  @PostMapping("/exact") Object exact(@Valid @RequestBody Search body){return Map.of("hits",service.exact(body));}
  @PostMapping("/verify") Object verify(@Valid @RequestBody Verify body){return Map.of("evidence",service.verify(body));}
  @PostMapping("/jobs/{id}/claim") Object claim(@PathVariable String id,@Valid @RequestBody Claim body){return service.claim(id,body.workerId());}
  @PostMapping("/jobs/{id}/finish") Object finish(@PathVariable String id,@RequestHeader("X-Lease-Token") String lease,@Valid @RequestBody Finish body){return service.finish(id,lease,body);}
  @PostMapping("/jobs/{id}/heartbeat") void heartbeat(@PathVariable String id,@RequestHeader("X-Lease-Token") String lease){service.heartbeat(id,lease);}
}
