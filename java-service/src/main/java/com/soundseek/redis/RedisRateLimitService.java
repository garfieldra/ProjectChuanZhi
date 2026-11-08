package com.soundseek.redis;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisRateLimitService {
  private final StringRedisTemplate redis;
  private final RedisCacheProperties properties;

  public RedisRateLimitService(StringRedisTemplate redis, RedisCacheProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  public void assertTournamentCreateAllowed(UUID guestId) {
    assertWithinLimit(guestId, "tournament-create", properties.getRateLimit().getTournamentCreatePerMinute());
  }

  public void assertVoteAllowed(UUID guestId) {
    assertWithinLimit(guestId, "vote", properties.getRateLimit().getVotePerMinute());
  }

  public void assertReportAllowed(UUID guestId) {
    assertWithinLimit(guestId, "report", properties.getRateLimit().getReportPerMinute());
  }

  public void assertConversationMessageAllowed(UUID guestId) {
    assertWithinLimit(guestId, "conversation-message", properties.getRateLimit().getConversationMessagePerMinute());
  }

  public void assertExplorationReportAllowed(UUID guestId) {
    assertWithinLimit(guestId, "exploration-report", properties.getRateLimit().getExplorationReportPerMinute());
  }

  public void assertAgentRunAnswerAllowed(UUID guestId) {
    assertWithinLimit(guestId, "agent-run-answer", properties.getRateLimit().getAgentRunAnswerPerMinute());
  }

  public void assertAuthAllowed(String callerIdentity) {
    assertWithinLimit(hash(callerIdentity), "auth", properties.getRateLimit().getAuthPerMinute());
  }

  private void assertWithinLimit(UUID guestId, String operation, int maxPerMinute) {
    assertWithinLimit(guestId.toString(), operation, maxPerMinute);
  }

  private void assertWithinLimit(String identity, String operation, int maxPerMinute) {
    var windowBucket = System.currentTimeMillis() / Duration.ofMinutes(1).toMillis();
    var key = RedisKeyNames.rateLimit(identity, operation, windowBucket);
    var count = redis.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redis.expire(key, Duration.ofMinutes(2));
    }
    if (count != null && count > maxPerMinute) {
      throw new RateLimitExceededException(operation);
    }
  }


  private String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
