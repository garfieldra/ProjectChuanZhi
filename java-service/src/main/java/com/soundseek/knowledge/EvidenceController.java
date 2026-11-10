package com.soundseek.knowledge;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {
  private final KnowledgeService service;
  public EvidenceController(KnowledgeService service){this.service=service;}
  // Only currently published evidence is disclosed. Historical drafts never become readable by ID.
  @GetMapping("/{id}/{revision}") Object evidence(@PathVariable String id,@PathVariable int revision){return service.publicEvidence(id,revision);}
}
