package com.soundseek.attachments;
import com.soundseek.identity.GuestIdentityFilter;
import com.soundseek.tournament.domain.GuestSession;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
public class ImageAttachmentController {
 private final ImageAttachmentService service;
 public ImageAttachmentController(ImageAttachmentService service){this.service=service;}
 private UUID owner(HttpServletRequest request){return ((GuestSession)request.getAttribute(GuestIdentityFilter.ATTRIBUTE)).getId();}
 @PostMapping(value="/api/v1/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 ImageAttachmentService.View upload(@RequestParam MultipartFile file,HttpServletRequest request)throws IOException{return service.upload(owner(request),file);}
 @GetMapping("/api/v1/attachments/{id}/content")
 ResponseEntity<byte[]> content(@PathVariable UUID id,HttpServletRequest request)throws IOException{return response(service.content(owner(request),id,null));}
 @GetMapping("/internal/v1/attachments/{id}/content")
 ResponseEntity<byte[]> internal(@PathVariable UUID id,@RequestParam UUID guestId,@RequestParam UUID conversationId)throws IOException{return response(service.content(guestId,id,conversationId));}
 private ResponseEntity<byte[]> response(ImageAttachmentService.Data data){return ResponseEntity.ok().contentType(MediaType.parseMediaType(data.contentType())).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(data.bytes());}
}
