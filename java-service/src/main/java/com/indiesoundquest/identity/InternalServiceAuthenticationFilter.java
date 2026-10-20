package com.indiesoundquest.identity;

import jakarta.servlet.*; import jakarta.servlet.http.*; import java.io.IOException; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.List;
import org.springframework.beans.factory.annotation.Value; import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; import org.springframework.security.core.authority.SimpleGrantedAuthority; import org.springframework.security.core.context.SecurityContextHolder; import org.springframework.stereotype.Component; import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {
  private final byte[] expected;
  public InternalServiceAuthenticationFilter(@Value("${agent.internal.service-token}")String token){expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);}
  @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/internal/v1/");}
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{var supplied=request.getHeader("Authorization");if(supplied!=null&&MessageDigest.isEqual(expected,supplied.getBytes(StandardCharsets.UTF_8))){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-service",null,List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));}chain.doFilter(request,response);}
}
