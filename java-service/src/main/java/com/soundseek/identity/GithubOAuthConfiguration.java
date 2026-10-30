package com.soundseek.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

@Configuration
@ConditionalOnExpression("'${soundseek.auth.github-client-id:}'.length() > 0 and '${soundseek.auth.github-client-secret:}'.length() > 0")
public class GithubOAuthConfiguration {
  @Bean
  ClientRegistrationRepository clientRegistrationRepository(@Value("${soundseek.auth.github-client-id}") String clientId,@Value("${soundseek.auth.github-client-secret}") String clientSecret) {
    var github = ClientRegistration.withRegistrationId("github").clientId(clientId).clientSecret(clientSecret)
        .clientName("GitHub").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").scope("read:user", "user:email")
        .authorizationUri("https://github.com/login/oauth/authorize").tokenUri("https://github.com/login/oauth/access_token")
        .userInfoUri("https://api.github.com/user").userNameAttributeName("id").build();
    return new InMemoryClientRegistrationRepository(github);
  }

}
