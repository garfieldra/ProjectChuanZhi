package com.soundseek.library;

import com.soundseek.identity.GuestIdentityFilter;
import com.soundseek.tournament.domain.GuestSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/saved-songs")
public class SavedSongController {
  private final SavedSongService service;
  public SavedSongController(SavedSongService service){this.service=service;}
  private UUID owner(HttpServletRequest request){return ((GuestSession)request.getAttribute(GuestIdentityFilter.ATTRIBUTE)).getId();}
  record Save(@NotNull UUID sourceMessageId) {}
  @PutMapping("/{recordingId}")
  SavedSongService.Song save(@PathVariable UUID recordingId,@Valid @RequestBody Save body,HttpServletRequest request){return service.save(owner(request),recordingId,body.sourceMessageId());}
  @DeleteMapping("/{recordingId}")
  ResponseEntity<Void> remove(@PathVariable UUID recordingId,HttpServletRequest request){service.remove(owner(request),recordingId);return ResponseEntity.noContent().build();}
  @GetMapping
  SavedSongService.Page list(@RequestParam(defaultValue="") String q,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){return service.list(owner(request),q,cursor,limit);}
  @GetMapping("/status")
  List<UUID> status(@RequestParam(defaultValue="") List<UUID> recordingIds,HttpServletRequest request){return service.status(owner(request),recordingIds);}
  @GetMapping("/{recordingId}/context")
  Map<String,String> context(@PathVariable UUID recordingId,HttpServletRequest request){return Map.of("content",service.context(owner(request),recordingId));}
}
