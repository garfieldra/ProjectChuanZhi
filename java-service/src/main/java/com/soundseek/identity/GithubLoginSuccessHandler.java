package com.soundseek.identity;
import jakarta.servlet.http.*;import java.io.IOException;import java.time.Instant;import java.util.UUID;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;import org.springframework.security.web.authentication.AuthenticationSuccessHandler;import org.springframework.stereotype.Component;
@Component public class GithubLoginSuccessHandler implements AuthenticationSuccessHandler {
 private final GuestIdentityService guests;private final AccountService accounts;private final AuthSessions sessions;private final AuthIdentityRepository identities;private final AuthMailService mail;
 public GithubLoginSuccessHandler(GuestIdentityService guests,AccountService accounts,AuthSessions sessions,AuthIdentityRepository identities,AuthMailService mail){this.guests=guests;this.accounts=accounts;this.sessions=sessions;this.identities=identities;this.mail=mail;}
 @Override public void onAuthenticationSuccess(HttpServletRequest r,HttpServletResponse response,org.springframework.security.core.Authentication authentication)throws IOException{
  var oauth=(OAuth2AuthenticationToken)authentication;var a=oauth.getPrincipal().getAttributes();String subject=String.valueOf(a.get("id"));var s=r.getSession(false);
  try{
   if(s!=null&&s.getAttribute("account.oauthReauth") instanceof String id){
    var expected=identities.findByProviderAndProviderSubject("GITHUB",subject).orElseThrow(AuthFailure::reauth);var u=accounts.current(id).orElseThrow(AuthFailure::reauth);
    var until=s.getAttribute("account.oauthExpires");var version=s.getAttribute("account.oauthVersion");
    if(!expected.getUserId().equals(UUID.fromString(id))||!(until instanceof Long t)||t<Instant.now().getEpochSecond()||!(version instanceof Long v)||v!=u.getAuthVersion())throw AuthFailure.reauth();
    String target="/?login=success#account-security";
    if(s.getAttribute("account.oauthReturn") instanceof String encrypted){
     target=mail.decrypt(encrypted);
     if(!target.matches("/#auth-confirm[?]purpose=CHANGE_EMAIL&token=[A-Za-z0-9_-]{1,100}"))throw AuthFailure.invalid();
    }
    sessions.save(u,r,response);sessions.markReauthenticated(r);response.sendRedirect(target);return;
   }
   String name=a.get("name")==null?String.valueOf(a.getOrDefault("login","GitHub 用户")):String.valueOf(a.get("name"));String avatar=a.get("avatar_url")==null?null:String.valueOf(a.get("avatar_url"));
   var u=accounts.completeGithubLogin(subject,name,avatar,guests.resolve(r,response));sessions.save(u,r,response);response.sendRedirect("/?login=success");
  }catch(RuntimeException e){sessions.clear(r,response);response.sendRedirect("/?login=failed");}
 }
}
