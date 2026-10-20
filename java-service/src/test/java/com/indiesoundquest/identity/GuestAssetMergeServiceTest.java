package com.indiesoundquest.identity;

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

  @Test void movesOwnedAssetsWithoutDeletingSourceData() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any())).thenReturn(1);
    new GuestAssetMergeService(jdbc).merge(UUID.randomUUID(), UUID.randomUUID());
    var writes = mockingDetails(jdbc).getInvocations().stream().filter(call -> call.getMethod().getName().equals("update")).toList();
    assertEquals(7, writes.size());
    assertTrue(writes.stream().allMatch(call -> ((String) call.getArgument(0)).startsWith("UPDATE")));
    assertTrue(writes.stream().anyMatch(call -> ((String) call.getArgument(0)).contains("LEFT JOIN preference_event")));
  }
}
