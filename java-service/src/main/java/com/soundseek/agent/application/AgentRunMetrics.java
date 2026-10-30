package com.soundseek.agent.application;

import com.soundseek.agent.domain.AgentRunType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class AgentRunMetrics {
  private final MeterRegistry registry;
  public AgentRunMetrics(MeterRegistry registry){this.registry=registry;}
  public void created(AgentRunType type){registry.counter("soundseek.agent.runs.created","type",type.name()).increment();}
  public void claimed(AgentRunType type){registry.counter("soundseek.agent.runs.claimed","type",type.name()).increment();}
  public void retry(String reason){registry.counter("soundseek.agent.runs.retries","reason",reason).increment();}
  public void completed(AgentRunType type,Duration duration){registry.counter("soundseek.agent.runs.completed","type",type.name()).increment();registry.timer("soundseek.agent.runs.duration","type",type.name()).record(duration);}
  public void failed(AgentRunType type){registry.counter("soundseek.agent.runs.failed","type",type.name()).increment();}
}
