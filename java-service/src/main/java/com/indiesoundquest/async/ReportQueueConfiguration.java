package com.indiesoundquest.async;
import org.springframework.amqp.core.*; import org.springframework.context.annotation.*;
@Configuration public class ReportQueueConfiguration { public static final String EXCHANGE="isq.tasks", AGENT_QUEUE="isq.agent-runs.v1", AGENT_ROUTING_KEY="agent.run", DLX="isq.dlx", AGENT_DLQ="isq.agent-runs.dlq";
 @Bean Queue knowledgeQueue(){return QueueBuilder.durable("isq.knowledge-index.v1").deadLetterExchange(DLX).deadLetterRoutingKey("knowledge.failed").build();}
 @Bean Queue knowledgeDeadLetterQueue(){return QueueBuilder.durable("isq.knowledge-index.dlq").build();}
 @Bean Binding knowledgeBinding(){return BindingBuilder.bind(knowledgeQueue()).to(taskExchange()).with("knowledge.index");}
 @Bean Binding knowledgeDeadLetterBinding(){return BindingBuilder.bind(knowledgeDeadLetterQueue()).to(deadLetterExchange()).with("knowledge.failed");}
 @Bean TopicExchange taskExchange(){return new TopicExchange(EXCHANGE,true,false);} @Bean TopicExchange deadLetterExchange(){return new TopicExchange(DLX,true,false);} @Bean Queue agentQueue(){return QueueBuilder.durable(AGENT_QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey("agent-run.failed").build();} @Bean Queue agentDeadLetterQueue(){return QueueBuilder.durable(AGENT_DLQ).build();} @Bean Binding agentBinding(){return BindingBuilder.bind(agentQueue()).to(taskExchange()).with(AGENT_ROUTING_KEY);}@Bean Binding agentDeadLetterBinding(){return BindingBuilder.bind(agentDeadLetterQueue()).to(deadLetterExchange()).with("agent-run.failed");}
}
