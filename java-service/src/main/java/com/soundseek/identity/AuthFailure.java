package com.soundseek.identity;
import org.springframework.http.HttpStatus;
public class AuthFailure extends RuntimeException {
 private final HttpStatus status;private final String code;
 public AuthFailure(HttpStatus status,String code,String message){super(message);this.status=status;this.code=code;}
 public HttpStatus status(){return status;}public String code(){return code;}
 public static AuthFailure invalid(){return new AuthFailure(HttpStatus.BAD_REQUEST,"AUTH_CHALLENGE_INVALID","链接已失效，请重新申请。");}
 public static AuthFailure reauth(){return new AuthFailure(HttpStatus.UNAUTHORIZED,"AUTH_REAUTH_REQUIRED","请先重新验证当前登录方式。");}
}
