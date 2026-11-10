package com.soundseek.identity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GuestAssetMergeServiceTest {
  @Test void sameGuestIsIdempotent() {
    var jdbc = mock(JdbcTemplate.class);
    var guest = UUID.randomUUID();
    new GuestAssetMergeService(jdbc).merge(guest, guest);
    verifyNoInteractions(jdbc);
  }

  @Test void movesOwnedAssetsAndDeduplicatesOnlySavedSongs() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any())).thenReturn(1);
    var source = UUID.randomUUID();
    var target = UUID.randomUUID();
    new GuestAssetMergeService(jdbc).merge(source, target);
    var writes = mockingDetails(jdbc).getInvocations().stream().filter(call -> call.getMethod().getName().equals("update")).toList();
    var deletes = writes.stream().filter(call -> ((String) call.getArgument(0)).startsWith("DELETE")).toList();
    assertEquals(1, deletes.size());
    assertTrue(((String) deletes.getFirst().getArgument(0)).contains("saved_song source JOIN saved_song target"));
    verify(jdbc).update(startsWith("UPDATE saved_song"), eq(target.toString()), eq(source.toString()));
    assertTrue(writes.stream().anyMatch(call -> ((String) call.getArgument(0)).contains("LEFT JOIN preference_event")));
  }
}
