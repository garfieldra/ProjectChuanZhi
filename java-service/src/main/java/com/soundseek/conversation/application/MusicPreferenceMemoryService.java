package com.soundseek.conversation.application;

import com.soundseek.conversation.domain.*;
import com.soundseek.conversation.repository.*;
import com.soundseek.identity.AuthFailure;
import com.soundseek.tournament.repository.GuestSessionRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MusicPreferenceMemoryService {
  public static final int MAX_MEMORIES = 20;
  private final MusicPreferenceMemoryRepository memories;
  private final ConversationRepository conversations;
  private final ConversationMessageRepository messages;
  private final GuestSessionRepository guests;

  public MusicPreferenceMemoryService(MusicPreferenceMemoryRepository memories, ConversationRepository conversations,
      ConversationMessageRepository messages, GuestSessionRepository guests) {
    this.memories=memories; this.conversations=conversations; this.messages=messages; this.guests=guests;
  }

  @Transactional(readOnly=true)
  public List<String> confirmedContents(UUID guestId) {
    return list(guestId).stream().map(MusicPreferenceMemory::getContent).limit(MAX_MEMORIES).toList();
  }

  @Transactional
  public MusicPreferenceMemory save(UUID guestId, UUID conversationId, UUID messageId, String content) {
    lockOwner(guestId);
    String value=validate(content);
    if ((conversationId==null)!=(messageId==null)) throw new IllegalArgumentException("请同时提供来源对话和消息。");
    if (conversationId!=null) {
      conversations.findByIdAndGuestSessionIdAndStatusNot(conversationId,guestId,ConversationStatus.DELETED).orElseThrow(NoSuchElementException::new);
      var message=messages.findById(messageId).filter(m->m.getRole()==ConversationMessageRole.USER
          && m.getType()==ConversationMessageType.USER_TEXT && m.getStatus()==ConversationMessageStatus.COMPLETED
          && m.getConversationId().equals(conversationId)).orElseThrow(NoSuchElementException::new);
      if (message.getTextContent()==null || message.getTextContent().isBlank()) throw new IllegalArgumentException("请选择包含文字的用户消息。");
    }
    var active=list(guestId);
    var existing=active.stream().filter(m->m.getContent().equals(value)).findFirst();
    if(existing.isPresent()) return existing.get(); // Retrying the same confirmation does not duplicate a memory.
    if(active.size()>=MAX_MEMORIES) throw new AuthFailure(HttpStatus.CONFLICT,"MEMORY_LIMIT_REACHED","最多保留 20 条音乐记忆，请先整理或移除旧条目。");
    var memory=MusicPreferenceMemory.confirmed(guestId,conversationId,value);
    memory.setSourceMessageId(messageId);
    return memories.save(memory);
  }

  @Transactional
  public MusicPreferenceMemory update(UUID guestId, UUID memoryId, String content) {
    lockOwner(guestId);
    var memory=owned(guestId,memoryId);
    if(!memory.getConfirmationStatus().equals("CONFIRMED")) throw new NoSuchElementException();
    String value=validate(content);
    if(list(guestId).stream().anyMatch(m->!m.getId().equals(memoryId)&&m.getContent().equals(value)))
      throw new AuthFailure(HttpStatus.CONFLICT,"MEMORY_ALREADY_EXISTS","已有相同的音乐记忆，请保留其中一条。");
    memory.revise(value);
    return memories.save(memory);
  }

  @Transactional
  public void revoke(UUID guestId, UUID memoryId) {
    lockOwner(guestId);
    var memory=owned(guestId,memoryId);
    if(memory.getConfirmationStatus().equals("CONFIRMED")){memory.revoke();memories.save(memory);}
  }

  @Transactional(readOnly=true)
  public List<MusicPreferenceMemory> list(UUID guestId) {
    return memories.findByGuestSessionIdAndConfirmationStatusOrderByUpdatedAtDesc(guestId,"CONFIRMED");
  }
  @Transactional(readOnly=true)
  public boolean sourceAvailable(MusicPreferenceMemory memory) {
    return memory.getSourceConversationId()!=null && conversations.findByIdAndGuestSessionIdAndStatusNot(
        memory.getSourceConversationId(),memory.getGuestSessionId(),ConversationStatus.DELETED).isPresent();
  }
  private MusicPreferenceMemory owned(UUID guestId, UUID id) {
    return memories.findById(id).filter(m->m.getGuestSessionId().equals(guestId)).orElseThrow(NoSuchElementException::new);
  }
  private void lockOwner(UUID id) { guests.findByIdForUpdate(id).orElseThrow(NoSuchElementException::new); }
  private String validate(String content) {
    if(content==null || content.isBlank() || content.length()>800) throw new IllegalArgumentException("音乐记忆需要填写 1–800 个字符。");
    return content.strip();
  }
}
