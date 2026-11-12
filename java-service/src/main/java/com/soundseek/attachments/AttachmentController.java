package com.soundseek.attachments;

import com.soundseek.identity.GuestIdentityFilter;
import com.soundseek.tournament.domain.GuestSession;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AttachmentController {
  private final AttachmentService service;
  public AttachmentController(AttachmentService service){this.service=service;}
  private UUID owner(HttpServletRequest request){return ((GuestSession)request.getAttribute(GuestIdentityFilter.ATTRIBUTE)).getId();}
  @PostMapping(value="/api/v1/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  AttachmentService.View upload(@RequestParam MultipartFile file,HttpServletRequest request)throws IOException{return service.upload(owner(request),file);}
  @GetMapping("/api/v1/attachments/{id}")
  AttachmentService.View metadata(@PathVariable UUID id,HttpServletRequest request){return service.metadata(owner(request),id,null);}
  @GetMapping("/internal/v1/attachments/{id}")
  AttachmentService.View internalMetadata(@PathVariable UUID id,@RequestParam UUID guestId,@RequestParam UUID conversationId){return service.metadata(guestId,id,conversationId);}
  @GetMapping("/api/v1/attachments/{id}/content")
  ResponseEntity<byte[]> content(@PathVariable UUID id,HttpServletRequest request)throws IOException{return response(service.content(owner(request),id,null));}
  @GetMapping("/internal/v1/attachments/{id}/content")
  ResponseEntity<byte[]> internal(@PathVariable UUID id,@RequestParam UUID guestId,@RequestParam UUID conversationId)throws IOException{return response(service.content(guestId,id,conversationId));}
  @GetMapping("/api/v1/attachments/{id}/document")
  AttachmentService.Slice document(@PathVariable UUID id,@RequestParam(defaultValue="1")int page,HttpServletRequest request)throws IOException{return service.document(owner(request),id,null,page,1,0,null,false);}
  @GetMapping("/internal/v1/attachments/{id}/document")
  AttachmentService.Slice internalDocument(@PathVariable UUID id,@RequestParam UUID guestId,@RequestParam UUID conversationId,
      @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="1")int count,@RequestParam(defaultValue="0")int offset,
      @RequestParam(required=false)String query,@RequestParam(defaultValue="false")boolean overview)throws IOException {
    return service.document(guestId,id,conversationId,page,count,offset,query,overview);
  }
  private ResponseEntity<byte[]> response(AttachmentService.Data data){
    var builder=ResponseEntity.ok().contentType(MediaType.parseMediaType(data.contentType())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff");
    if(!data.image())builder.header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(data.fileName(),StandardCharsets.UTF_8).build().toString());
    return builder.body(data.bytes());
  }
}
