package com.soundseek.identity;
import jakarta.servlet.http.HttpServletRequest;import java.time.Instant;import java.util.UUID;import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.session.Session;import org.springframework.session.SessionRepository;import org.springframework.stereotype.Component;
@Component public class AccountStreamGuard {
 private final ObjectProvider<SessionRepository<? extends Session>> repositories;private final UserAccountRepository users;private final JdbcTemplate db;private final AuthSessions sessions;
 public AccountStreamGuard(ObjectProvider<SessionRepository<? extends Session>> repositories,UserAccountRepository users,JdbcTemplate db,AuthSessions sessions){this.repositories=repositories;this.users=users;this.db=db;this.sessions=sessions;}
 public BooleanSupplier capture(HttpServletRequest request){
  var principal=request.getUserPrincipal();var http=request.getSession(false);String id=http==null?null:http.getId();String name=principal==null?null:principal.getName();
  var guest=(com.soundseek.tournament.domain.GuestSession)request.getAttribute(GuestIdentityFilter.ATTRIBUTE);
  if(name==null||name.startsWith("guest:"))return ()->guest!=null&&db.queryForObject("SELECT COUNT(*) FROM user_account WHERE primary_guest_session_id=UUID_TO_BIN(?)",Integer.class,guest.getId().toString())==0;
  return ()->{try{var repo=repositories.getIfAvailable();var s=repo==null||id==null?null:repo.findById(id);if(s==null)return false;var u=users.findById(UUID.fromString(name)).orElse(null);Long version=s.getAttribute(AuthSessions.VERSION),created=s.getAttribute(AuthSessions.CREATED);return u!=null&&"ACTIVE".equals(u.getStatus())&&version!=null&&version==u.getAuthVersion()&&created!=null&&created+sessions.absoluteSeconds()>Instant.now().getEpochSecond();}catch(RuntimeException e){return false;}};
 }
}
