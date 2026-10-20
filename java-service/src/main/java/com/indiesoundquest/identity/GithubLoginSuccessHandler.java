package com.indiesoundquest.identity;

import jakarta.servlet.http.*; import java.io.IOException;
import org.springframework.security.core.context.*;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
public class GithubLoginSuccessHandler implements AuthenticationSuccessHandler {
  private final GuestIdentityService guests; private final AccountService accounts; private final HttpSessionSecurityContextRepository contexts;
  public GithubLoginSuccessHandler(GuestIdentityService guests,AccountService accounts,HttpSessionSecurityContextRepository contexts){this.guests=guests;this.accounts=accounts;this.contexts=contexts;}
  @Override public void onAuthenticationSuccess(HttpServletRequest request,HttpServletResponse response,org.springframework.security.core.Authentication authentication)throws IOException{var oauth=(OAuth2AuthenticationToken)authentication;var attributes=oauth.getPrincipal().getAttributes();var subject=String.valueOf(attributes.get("id"));var name=String.valueOf(attributes.getOrDefault("name",attributes.getOrDefault("login","GitHub 用户")));var avatar=attributes.get("avatar_url")==null?null:String.valueOf(attributes.get("avatar_url"));var account=accounts.completeGithubLogin(subject,name,avatar,guests.resolve(request,response));var principal=User.withUsername(account.getEmailNormalized()).password("").roles("USER").build();var authenticated=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authenticated);contexts.saveContext(context,request,response);response.sendRedirect("/?login=success");}
}
