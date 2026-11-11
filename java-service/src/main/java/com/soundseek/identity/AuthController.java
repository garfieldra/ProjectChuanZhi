package com.soundseek.identity;

import com.soundseek.tournament.domain.GuestSession;import com.soundseek.redis.RedisRateLimitService;
import jakarta.servlet.http.*;import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import java.security.Principal;import java.util.*;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.http.*;import org.springframework.security.authentication.*;import org.springframework.security.core.context.SecurityContextHolder;import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
 private final AccountService accounts;private final AuthenticationManager authentication;private final AuthSessions sessions;private final RedisRateLimitService rateLimit;private final AuthLifecycleService lifecycle;private final boolean githubEnabled;
 public AuthController(AccountService accounts,AuthenticationManager authentication,AuthSessions sessions,RedisRateLimitService rateLimit,AuthLifecycleService lifecycle,ObjectProvider<ClientRegistrationRepository> clients){this.accounts=accounts;this.authentication=authentication;this.sessions=sessions;this.rateLimit=rateLimit;this.lifecycle=lifecycle;this.githubEnabled=clients.getIfAvailable()!=null;}
 @GetMapping("/session") SessionView session(Principal p){return p==null||p.getName().startsWith("guest:")?SessionView.guest(githubEnabled):view(account(p));}
 @PatchMapping("/profile") SessionView profile(@Valid @RequestBody Profile body,Principal p){return view(accounts.updateProfile(account(p).getId().toString(),body.displayName()));}
 @PostMapping("/register") ResponseEntity<Map<String,String>> register(@Valid @RequestBody Register b,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){ensureGuest();mailLimit(r,b.email(),key);lifecycle.registration(b.email(),b.password(),b.displayName(),browser(r),key);return accepted();}
 @PostMapping("/registration/confirm") SessionView confirmRegistration(@RequestBody Confirm b,HttpServletRequest r,HttpServletResponse response){ensureGuest();rateLimit.assertAccountLoginAllowed(r.getRemoteAddr(),"registration-confirm");var u=lifecycle.confirmRegistration(b.token(),b.password(),browser(r),guest(r));sessions.save(u,r,response);return view(u);}
 @PostMapping("/login") SessionView login(@Valid @RequestBody Login b,HttpServletRequest r,HttpServletResponse response){
  ensureGuest();String email=AccountService.normalize(b.email());rateLimit.assertAccountLoginAllowed(r.getRemoteAddr(),email);
  long version=accountsForEmailVersion(email);authentication.authenticate(new UsernamePasswordAuthenticationToken(email,b.password()));
  var u=lifecycle.login(email,b.password(),version,guest(r));sessions.save(u,r,response);return view(u);
 }
 private long accountsForEmailVersion(String email){return accounts.loginVersion(email);}
 @PostMapping("/logout") SessionView logout(HttpServletRequest r,HttpServletResponse response){sessions.clear(r,response);return SessionView.guest(githubEnabled);}
 @PostMapping("/logout-all") SessionView logoutAll(Principal p,HttpServletRequest r,HttpServletResponse response){var u=account(p);sessions.requireRecent(r);lifecycle.logoutAll(u.getId());sessions.clear(r,response);return SessionView.guest(githubEnabled);}
 @PostMapping("/password-reset/request") ResponseEntity<Map<String,String>> resetRequest(@Valid @RequestBody Email b,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){mailLimit(r,b.email(),key);lifecycle.requestReset(b.email(),key);return accepted();}
 @PostMapping("/password-reset/confirm") Map<String,String> reset(@RequestBody Confirm b,HttpServletRequest r){ensureGuest();rateLimit.assertAccountLoginAllowed(r.getRemoteAddr(),"password-reset");lifecycle.reset(b.token(),b.password());return Map.of("message","密码已更新，请重新登录。");}
 @PostMapping("/email-verification/request") ResponseEntity<Map<String,String>> verifyRequest(Principal p,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){var u=account(p);sessions.requireRecent(r);mailLimit(r,u.getEmailNormalized()==null?u.getId().toString():u.getEmailNormalized(),key);lifecycle.verifyRequest(u.getId(),key);return accepted();}
 @PostMapping("/email-verification/confirm") SessionView verify(@RequestBody Confirm b,Principal p,HttpServletRequest r,HttpServletResponse response){var u=lifecycle.confirmEmail(account(p).getId(),b.token(),"VERIFY");return view(u);}
 @PostMapping("/reauth") Map<String,String> reauth(@RequestBody(required=false) Reauth b,Principal p,HttpServletRequest r){
  var u=account(p);rateLimit.assertAccountLoginAllowed(r.getRemoteAddr(),u.getId().toString());
  if(lifecycle.hasPassword(u.getId())){lifecycle.checkPassword(u,b==null?null:b.password());sessions.markReauthenticated(r);return Map.of("status","confirmed");}
  try{sessions.requireRecent(r);return Map.of("status","confirmed");}catch(AuthFailure ignored){}
  if(!githubEnabled)throw AuthFailure.reauth();r.getSession().setAttribute("account.oauthReauth",u.getId().toString());r.getSession().setAttribute("account.oauthVersion",u.getAuthVersion());r.getSession().setAttribute("account.oauthExpires",java.time.Instant.now().plusSeconds(600).getEpochSecond());return Map.of("redirectUrl","/oauth2/authorization/github");
 }
 private void mailLimit(HttpServletRequest r,String email,String key){rateLimit.assertMailAllowed(r.getRemoteAddr(),AccountService.normalize(email),key);}
 private ResponseEntity<Map<String,String>> accepted(){return ResponseEntity.accepted().body(Map.of("message","请求已受理。如信息符合条件，你将收到邮件，请检查收件箱。"));}
 private SessionView view(UserAccount u){return SessionView.account(u,githubEnabled,lifecycle.hasPassword(u.getId()));}
 private UserAccount account(Principal p){if(p==null||p.getName().startsWith("guest:"))throw AuthFailure.reauth();return accounts.current(p.getName()).filter(u->"ACTIVE".equals(u.getStatus())).orElseThrow(AuthFailure::reauth);}
 private GuestSession guest(HttpServletRequest r){return (GuestSession)r.getAttribute(GuestIdentityFilter.ATTRIBUTE);}
 private String browser(HttpServletRequest r){var s=r.getSession(true);String value=(String)s.getAttribute("account.registrationBrowser");if(value==null){value=AuthMailService.token();s.setAttribute("account.registrationBrowser",value);}return AuthMailService.hash(value);}
 private void ensureGuest(){var a=SecurityContextHolder.getContext().getAuthentication();if(a!=null&&a.isAuthenticated()&&!a.getName().startsWith("guest:"))throw new IllegalStateException("请先退出当前账户。");}
 record Profile(@NotBlank @Size(max=80)String displayName){}
 record Register(@jakarta.validation.constraints.Email @NotBlank @Size(max=320)String email,@NotBlank @Size(min=10,max=128)String password,@Size(max=80)String displayName){}
 record Login(@jakarta.validation.constraints.Email @NotBlank @Size(max=320)String email,@NotBlank @Size(max=128)String password){}
 record Email(@jakarta.validation.constraints.Email @NotBlank @Size(max=320)String email){}
 record Confirm(String token,String password){}record Reauth(String password){}
 public record SessionView(boolean authenticated,String displayName,String email,String avatarUrl,boolean githubEnabled,String userId,boolean emailVerified,List<String> loginMethods){
  static SessionView guest(boolean github){return new SessionView(false,null,null,null,github,null,false,List.of());}
  static SessionView account(UserAccount u,boolean github,boolean password){return new SessionView(true,u.getDisplayName(),u.getEmailNormalized(),u.getAvatarUrl(),github,u.getId().toString(),u.isEmailVerified(),password?List.of("PASSWORD"):List.of("GITHUB"));}
 }
}
