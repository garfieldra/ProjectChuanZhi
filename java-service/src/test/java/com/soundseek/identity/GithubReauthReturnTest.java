package com.soundseek.identity;

import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import java.time.Instant;import java.util.*;import org.junit.jupiter.api.Test;import org.springframework.mock.web.*;import org.springframework.beans.factory.ObjectProvider;import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.authority.SimpleGrantedAuthority;import org.springframework.security.oauth2.core.user.DefaultOAuth2User;import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
class GithubReauthReturnTest {
 @Test void encryptedEmailConfirmationReturnSurvivesSessionRotation()throws Exception{
  var accounts=mock(AccountService.class);var identities=mock(AuthIdentityRepository.class);var sessions=mock(AuthSessions.class);var guests=mock(GuestIdentityService.class);
  var user=UserAccount.create(null,"test",null,UUID.randomUUID());String id=user.getId().toString();
  when(accounts.current(id)).thenReturn(Optional.of(user));when(identities.findByProviderAndProviderSubject("GITHUB","123")).thenReturn(Optional.of(AuthIdentity.github(user.getId(),"123")));
  @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> senders=mock(ObjectProvider.class);
  var mail=new AuthMailService(null,new ObjectMapper(),senders,false,"","http://localhost",Base64.getEncoder().encodeToString(new byte[32]));
  String target="/#auth-confirm?purpose=CHANGE_EMAIL&token=synthetic-confirmation-token";
  var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();var old=request.getSession();
  old.setAttribute("account.oauthReauth",id);old.setAttribute("account.oauthVersion",user.getAuthVersion());old.setAttribute("account.oauthExpires",Instant.now().plusSeconds(600).getEpochSecond());old.setAttribute("account.oauthReturn",mail.encrypt(target));
  doAnswer(call->{request.getSession().invalidate();request.getSession(true);return null;}).when(sessions).save(user,request,response);
  new GithubLoginSuccessHandler(guests,accounts,sessions,identities,mail).onAuthenticationSuccess(request,response,oauth("123"));
  assertEquals(target,response.getRedirectedUrl());verify(sessions).markReauthenticated(request);
 }
 @Test void differentGithubIdentityCannotResumeOriginalAccountsAction()throws Exception{
  var accounts=mock(AccountService.class);var identities=mock(AuthIdentityRepository.class);var sessions=mock(AuthSessions.class);var guests=mock(GuestIdentityService.class);
  var user=UserAccount.create(null,"test",null,UUID.randomUUID());String id=user.getId().toString();when(accounts.current(id)).thenReturn(Optional.of(user));
  when(identities.findByProviderAndProviderSubject("GITHUB","999")).thenReturn(Optional.of(AuthIdentity.github(UUID.randomUUID(),"999")));
  var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();request.getSession().setAttribute("account.oauthReauth",id);request.getSession().setAttribute("account.oauthVersion",0L);request.getSession().setAttribute("account.oauthExpires",Instant.now().plusSeconds(600).getEpochSecond());
  new GithubLoginSuccessHandler(guests,accounts,sessions,identities,mock(AuthMailService.class)).onAuthenticationSuccess(request,response,oauth("999"));
  assertEquals("/?login=failed",response.getRedirectedUrl());verify(sessions).clear(request,response);verify(sessions,never()).save(any(),any(),any());
 }
 @Test void normalReauthReturnsToPendingActionPage()throws Exception{
  var accounts=mock(AccountService.class);var identities=mock(AuthIdentityRepository.class);var sessions=mock(AuthSessions.class);var guests=mock(GuestIdentityService.class);
  var user=UserAccount.create(null,"test",null,UUID.randomUUID());String id=user.getId().toString();when(accounts.current(id)).thenReturn(Optional.of(user));
  when(identities.findByProviderAndProviderSubject("GITHUB","123")).thenReturn(Optional.of(AuthIdentity.github(user.getId(),"123")));
  var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();var old=request.getSession();
  old.setAttribute("account.oauthReauth",id);old.setAttribute("account.oauthVersion",user.getAuthVersion());old.setAttribute("account.oauthExpires",Instant.now().plusSeconds(600).getEpochSecond());
  new GithubLoginSuccessHandler(guests,accounts,sessions,identities,mock(AuthMailService.class)).onAuthenticationSuccess(request,response,oauth("123"));
  assertEquals("/?login=success#account-security",response.getRedirectedUrl());verify(sessions).markReauthenticated(request);
 }
 @Test void encryptedReturnCannotRedirectOutsideConfirmationPage()throws Exception{
  var accounts=mock(AccountService.class);var identities=mock(AuthIdentityRepository.class);var sessions=mock(AuthSessions.class);var guests=mock(GuestIdentityService.class);var mail=mock(AuthMailService.class);
  var user=UserAccount.create(null,"test",null,UUID.randomUUID());String id=user.getId().toString();when(accounts.current(id)).thenReturn(Optional.of(user));
  when(identities.findByProviderAndProviderSubject("GITHUB","123")).thenReturn(Optional.of(AuthIdentity.github(user.getId(),"123")));when(mail.decrypt("ciphertext")).thenReturn("https://example.invalid");
  var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();var old=request.getSession();
  old.setAttribute("account.oauthReauth",id);old.setAttribute("account.oauthVersion",user.getAuthVersion());old.setAttribute("account.oauthExpires",Instant.now().plusSeconds(600).getEpochSecond());old.setAttribute("account.oauthReturn","ciphertext");
  new GithubLoginSuccessHandler(guests,accounts,sessions,identities,mail).onAuthenticationSuccess(request,response,oauth("123"));
  assertEquals("/?login=failed",response.getRedirectedUrl());verify(sessions,never()).save(any(),any(),any());
 }
 private OAuth2AuthenticationToken oauth(String subject){var authorities=List.of(new SimpleGrantedAuthority("ROLE_USER"));return new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities,Map.of("id",subject),"id"),authorities,"github");}
}
