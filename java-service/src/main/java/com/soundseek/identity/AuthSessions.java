package com.soundseek.identity;
import jakarta.servlet.http.*;import java.time.Instant;import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
@Component public class AuthSessions {
 public static final String VERSION="account.authVersion",CREATED="account.created",REAUTH="account.reauth";
 @org.springframework.beans.factory.annotation.Value("${soundseek.auth.session-absolute-days:90}") private int absoluteDays=90;
 @org.springframework.beans.factory.annotation.Value("${soundseek.auth.reauth-minutes:10}") private int reauthMinutes=10;
 public long absoluteSeconds(){return Math.max(1,absoluteDays)*86400L;}
 public long reauthSeconds(){return Math.max(1,reauthMinutes)*60L;}
 private final HttpSessionSecurityContextRepository contexts;private final UserAccountRepository users;
 public AuthSessions(HttpSessionSecurityContextRepository contexts,UserAccountRepository users){this.contexts=contexts;this.users=users;}
 public void save(UserAccount u,HttpServletRequest r,HttpServletResponse response){
  AccountService.requireActive(u);var old=r.getSession(false);if(old!=null)old.invalidate();
  var session=r.getSession(true);r.changeSessionId();session.setAttribute(VERSION,u.getAuthVersion());session.setAttribute(CREATED,Instant.now().getEpochSecond());
  var principal=User.withUsername(u.getId().toString()).password("").roles("USER").build();
  var auth=new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);contexts.saveContext(context,r,response);clearCsrf(r,response);
 }
 public void clear(HttpServletRequest r,HttpServletResponse response){var s=r.getSession(false);if(s!=null)s.invalidate();SecurityContextHolder.clearContext();clearCsrf(r,response);}
 private void clearCsrf(HttpServletRequest r,HttpServletResponse response){var csrf=CookieCsrfTokenRepository.withHttpOnlyFalse();csrf.setCookiePath("/");csrf.saveToken(null,r,response);}
 public boolean valid(String principal,HttpSession s){if(s==null)return false;try{
  var u=users.findById(UUID.fromString(principal)).orElse(null);var version=s.getAttribute(VERSION);var created=s.getAttribute(CREATED);
  return u!=null&&"ACTIVE".equals(u.getStatus())&&version instanceof Long v&&v==u.getAuthVersion()&&created instanceof Long t&&t+absoluteSeconds()>Instant.now().getEpochSecond();
 }catch(IllegalArgumentException|IllegalStateException e){return false;}}
 public void markReauthenticated(HttpServletRequest r){r.getSession().setAttribute(REAUTH,Instant.now().getEpochSecond());}
 public void requireRecent(HttpServletRequest r){var s=r.getSession(false);var t=s==null?null:s.getAttribute(REAUTH);if(!(t instanceof Long value)||value+reauthSeconds()<Instant.now().getEpochSecond())throw AuthFailure.reauth();}
}
