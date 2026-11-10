package com.indiesoundquest.knowledge;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeReconciliation {
  private final KnowledgeService service;
  public KnowledgeReconciliation(KnowledgeService service){this.service=service;}
  @Scheduled(fixedDelayString="${knowledge.reconcile-ms:60000}") public void reconcile(){service.reconcile();}
}
