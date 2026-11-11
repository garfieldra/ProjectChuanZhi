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
  @org.springframework.beans.factory.annotation.Value("${soundseek.auth.limits.login-ip:60}") private int loginIp=60;
  @org.springframework.beans.factory.annotation.Value("${soundseek.auth.limits.login-account:20}") private int loginAccount=20;
  @org.springframework.beans.factory.annotation.Value("${soundseek.auth.limits.login-combination:10}") private int loginCombination=10;
  @org.springframework.beans.factory.annotation.Value("${soundseek.auth.limits.mail-global:500}") private int mailGlobal=500;
  @org.springframework.beans.factory.annotation.Value("${soundseek.auth.limits.mail-ip:20}") private int mailIp=20;
  @org.springframework.beans.factory.annotation.Value("${soundseek.auth.limits.mail-hour:5}") private int mailHour=5;
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


  public void assertAccountLoginAllowed(String ip,String email) {
    atomicLimit("auth-ip:"+hash(ip),loginIp,60);
    atomicLimit("auth-account:"+hash(email),loginAccount,900);
    atomicLimit("auth-combination:"+hash(ip+":"+email),loginCombination,900);
  }
  public void assertMailAllowed(String ip,String email,String requestKey) {
    if(requestKey==null||requestKey.isBlank()||requestKey.length()>100)throw new IllegalArgumentException("需要有效的提交标识。");
    String script="if redis.call('EXISTS',KEYS[1])==1 then return 0 end; "
      +"for i=2,5 do if tonumber(redis.call('GET',KEYS[i]) or '0')>=tonumber(ARGV[(i-2)*2+1]) then return -1 end end; "
      +"for i=2,5 do local n=redis.call('INCR',KEYS[i]); if n==1 then redis.call('EXPIRE',KEYS[i],ARGV[(i-2)*2+2]) end end; "
      +"redis.call('SET',KEYS[1],'1','EX',3600); return 1";
    try {
      var keys=java.util.List.of("soundseek:security:mail-request:"+hash(ip+":"+email+":"+requestKey),"soundseek:security:mail-global","soundseek:security:mail-ip:"+hash(ip),"soundseek:security:mail-minute:"+hash(email),"soundseek:security:mail-hour:"+hash(email));
      var result=redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(script,Long.class),keys,String.valueOf(mailGlobal),"3600",String.valueOf(mailIp),"3600","1","60",String.valueOf(mailHour),"3600");
      if(result==null)throw new IllegalStateException();if(result<0)throw new RateLimitExceededException("mail");
    }catch(RateLimitExceededException e){throw e;}
    catch(Exception e){throw new com.soundseek.identity.AuthFailure(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"AUTH_SERVICE_UNAVAILABLE","认证服务暂不可用。");}
  }
  private void atomicLimit(String id,int max,int seconds) {
    var script=new org.springframework.data.redis.core.script.DefaultRedisScript<Long>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n",Long.class);
    try {var n=redis.execute(script,java.util.List.of("soundseek:security:"+id),String.valueOf(seconds));if(n==null)throw new IllegalStateException();if(n>max)throw new RateLimitExceededException("auth");}
    catch(RateLimitExceededException e){throw e;}
    catch(Exception e){throw new com.soundseek.identity.AuthFailure(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"AUTH_SERVICE_UNAVAILABLE","认证服务暂不可用，请稍后重试。");}
  }

  private String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
