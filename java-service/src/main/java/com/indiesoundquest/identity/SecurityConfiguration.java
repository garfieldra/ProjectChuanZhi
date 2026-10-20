package com.indiesoundquest.identity;

import jakarta.servlet.*; import jakarta.servlet.http.*; import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
  @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception { return configuration.getAuthenticationManager(); }
  @Bean HttpSessionSecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
  @Bean FilterRegistrationBean<GuestIdentityFilter> disableContainerGuestFilter(GuestIdentityFilter filter) { var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration; }
  @Bean FilterRegistrationBean<InternalServiceAuthenticationFilter> disableContainerInternalFilter(InternalServiceAuthenticationFilter filter) { var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration; }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, GuestIdentityFilter guests, InternalServiceAuthenticationFilter internal, ObjectProvider<ClientRegistrationRepository> clients, GithubLoginSuccessHandler github, @Value("${indie.auth.secure-cookies:false}") boolean secureCookies) throws Exception {
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieName("XSRF-TOKEN"); csrf.setHeaderName("X-XSRF-TOKEN"); csrf.setCookiePath("/");
    csrf.setCookieCustomizer(cookie -> cookie.secure(secureCookies).sameSite("Lax"));
    var csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
    csrfRequestHandler.setCsrfRequestAttributeName("_csrf");
    http.authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/api/v1/auth/**", "/oauth2/**", "/login/**", "/actuator/health", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
            .requestMatchers("/internal/v1/**").hasRole("INTERNAL")
            .requestMatchers("/api/v1/**").hasAnyRole("GUEST", "USER")
            .anyRequest().permitAll())
        .csrf(value -> value.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrfRequestHandler).ignoringRequestMatchers(new AntPathRequestMatcher("/internal/v1/**")))
        .securityContext(value -> value.requireExplicitSave(true))
        .addFilterBefore(internal, AnonymousAuthenticationFilter.class)
        .addFilterAfter(guests, InternalServiceAuthenticationFilter.class)
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .logout(value -> value.disable());
    if (clients.getIfAvailable() != null) http.oauth2Login(value -> value.successHandler(github).failureUrl("/?login=failed"));
    return http.build();
  }

  static class CsrfCookieFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{var token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());if(token!=null)token.getToken();chain.doFilter(request,response);}
  }
}
