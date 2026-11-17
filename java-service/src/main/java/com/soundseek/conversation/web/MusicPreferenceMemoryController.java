package com.soundseek.conversation.web;

import com.soundseek.conversation.application.MusicPreferenceMemoryService;
import com.soundseek.conversation.domain.MusicPreferenceMemory;
import com.soundseek.identity.GuestIdentityFilter;
import com.soundseek.tournament.domain.GuestSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/music-preference-memories")
public class MusicPreferenceMemoryController {
  private final MusicPreferenceMemoryService service;
  public MusicPreferenceMemoryController(MusicPreferenceMemoryService service){this.service=service;}
  @GetMapping List<View> list(HttpServletRequest request){return service.list(guest(request)).stream().map(this::view).toList();}
  @PostMapping View save(@Valid @RequestBody Create body,HttpServletRequest request){
    return view(service.save(guest(request),body.sourceConversationId(),body.sourceMessageId(),body.content()));
  }
  @PatchMapping("/{id}") View update(@PathVariable UUID id,@Valid @RequestBody Edit body,HttpServletRequest request){
    return view(service.update(guest(request),id,body.content()));
  }
  @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable UUID id,HttpServletRequest request){service.revoke(guest(request),id);return ResponseEntity.noContent().build();}
  private UUID guest(HttpServletRequest request){return ((GuestSession)request.getAttribute(GuestIdentityFilter.ATTRIBUTE)).getId();}
  record Create(@NotBlank @Size(max=800) String content,UUID sourceConversationId,UUID sourceMessageId){}
  record Edit(@NotBlank @Size(max=800) String content){}
  private View view(MusicPreferenceMemory m){return new View(m.getId(),m.getContent(),m.getSourceConversationId(),m.getSourceMessageId(),m.getCreatedAt(),m.getUpdatedAt(),service.sourceAvailable(m));}
  record View(UUID id,String content,UUID sourceConversationId,UUID sourceMessageId,Instant createdAt,Instant updatedAt,boolean sourceAvailable){}

}
