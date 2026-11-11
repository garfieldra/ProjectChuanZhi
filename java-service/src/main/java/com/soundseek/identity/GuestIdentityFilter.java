package com.soundseek.identity;
import com.soundseek.tournament.domain.GuestSession;import com.soundseek.tournament.repository.GuestSessionRepository;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;import org.springframework.security.core.authority.SimpleGrantedAuthority;import org.springframework.security.core.context.SecurityContextHolder;import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;
@Component public class GuestIdentityFilter extends OncePerRequestFilter {
 public static final String ATTRIBUTE="guestSession";private final GuestIdentityService identities;private final UserAccountRepository users;private final GuestSessionRepository sessions;private final AuthSessions authSessions;
 public GuestIdentityFilter(GuestIdentityService identities,UserAccountRepository users,GuestSessionRepository sessions,AuthSessions authSessions){this.identities=identities;this.users=users;this.sessions=sessions;this.authSessions=authSessions;}
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return r.getRequestURI().startsWith("/internal/v1/")||r.getRequestURI().equals("/actuator/health");}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  var auth=SecurityContextHolder.getContext().getAuthentication();GuestSession guest=null;
  if(auth!=null&&auth.isAuthenticated()&&!auth.getName().startsWith("guest:")){
   if(!authSessions.valid(auth.getName(),r.getSession(false))){authSessions.clear(r,response);auth=null;if(!r.getRequestURI().equals("/api/v1/auth/session")){response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":\"AUTH_SESSION_EXPIRED\",\"message\":\"登录已失效，请重新登录。\"}");return;}}
   else guest=users.findById(UUID.fromString(auth.getName())).flatMap(u->sessions.findById(u.getPrimaryGuestSessionId())).orElseThrow();
  }
  if(guest==null)guest=identities.resolve(r,response);r.setAttribute(ATTRIBUTE,guest);
  if(auth==null)SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("guest:"+guest.getId(),null,List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));chain.doFilter(r,response);
 }
}
