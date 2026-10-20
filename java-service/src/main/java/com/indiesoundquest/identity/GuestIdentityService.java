package com.indiesoundquest.identity;

import com.indiesoundquest.tournament.domain.GuestSession;
import com.indiesoundquest.tournament.repository.GuestSessionRepository;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GuestIdentityService {
  static final String COOKIE = "isq_guest";
  private final GuestSessionRepository sessions;
  private final boolean secureCookies;
  public GuestIdentityService(GuestSessionRepository sessions, @Value("${indie.auth.secure-cookies:false}") boolean secureCookies) { this.sessions = sessions; this.secureCookies = secureCookies; }

  public GuestSession resolve(HttpServletRequest request, HttpServletResponse response) {
    var token = token(request).orElse(null);
    if (token == null || token.isBlank()) {
      token = UUID.randomUUID() + "-" + UUID.randomUUID();
      var created = sessions.save(new GuestSession(UUID.randomUUID(), hash(token)));
      response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, token).httpOnly(true).secure(secureCookies).sameSite("Lax").path("/").build().toString());
      return created;
    }
    var tokenHash = hash(token);
    return sessions.findByTokenHash(tokenHash).orElseGet(() -> sessions.save(new GuestSession(UUID.randomUUID(), tokenHash)));
  }

  public Optional<GuestSession> existing(HttpServletRequest request) { return token(request).flatMap(value -> sessions.findByTokenHash(hash(value))); }
  public void revoke(GuestSession guest) { guest.revokeToken(hash(UUID.randomUUID() + "-" + UUID.randomUUID())); sessions.save(guest); }
  private Optional<String> token(HttpServletRequest request) { return Arrays.stream(Optional.ofNullable(request.getCookies()).orElse(new Cookie[0])).filter(cookie -> COOKIE.equals(cookie.getName())).map(Cookie::getValue).findFirst(); }
  private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
