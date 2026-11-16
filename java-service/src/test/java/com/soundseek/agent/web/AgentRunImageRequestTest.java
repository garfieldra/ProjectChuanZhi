package com.soundseek.agent.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soundseek.agent.application.AgentRunFollowUpApplicationService;
import com.soundseek.agent.domain.*;
import com.soundseek.identity.GuestIdentityFilter;
import com.soundseek.redis.RedisRateLimitService;
import com.soundseek.tournament.domain.GuestSession;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AgentRunImageRequestTest {
  @Test
  void imageOnlyQueuedRequestReachesTheImageAwareService() {
    var owner = UUID.randomUUID();
    var runId = UUID.randomUUID();
    var key = UUID.randomUUID();
    var ids = List.of(UUID.randomUUID());
    var followUps = mock(AgentRunFollowUpApplicationService.class);
    var rateLimit = mock(RedisRateLimitService.class);
    var guest = mock(GuestSession.class);
    when(guest.getId()).thenReturn(owner);
    var request = new MockHttpServletRequest();
    request.setAttribute(GuestIdentityFilter.ATTRIBUTE, guest);
    var run = AgentRun.queued(runId, owner, UUID.randomUUID(), AgentRunType.CONVERSATION, "{}");
    var queued = AgentRunFollowUp.waiting(run, key, "");
    queued.setAttachmentIds(ids);
    when(followUps.queue(runId, owner, key, "", ids)).thenReturn(queued);
    var controller = new AgentRunController(null, followUps, null, null, rateLimit, new ObjectMapper());

    var result = controller.queueNextMessage(runId, key, new AgentRunController.InterventionBody(null, ids), request);

    assertEquals(202, result.getStatusCode().value());
    assertEquals(ids, result.getBody().attachmentIds());
    verify(followUps).queue(runId, owner, key, "", ids);
  }

  @Test
  void emptyOrDuplicateImageRequestsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new AgentRunController.InterventionBody(" ", List.of()));
    assertThrows(IllegalArgumentException.class, () -> new AgentRunController.InterventionBody("图片", Arrays.asList((UUID) null)));
    var id = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () -> new AgentRunController.InterventionBody("图片", List.of(id, id)));
  }
}
