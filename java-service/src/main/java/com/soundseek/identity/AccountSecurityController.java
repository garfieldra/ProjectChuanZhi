package com.soundseek.identity;
import java.security.Principal;import java.util.*;import jakarta.servlet.http.*;import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import com.soundseek.redis.RedisRateLimitService;
@RestController @RequestMapping("/api/v1/me")
public class AccountSecurityController {
 private final AuthLifecycleService lifecycle;private final AccountService accounts;private final AuthSessions sessions;private final RedisRateLimitService limits;
 public AccountSecurityController(AuthLifecycleService lifecycle,AccountService accounts,AuthSessions sessions,RedisRateLimitService limits){this.lifecycle=lifecycle;this.accounts=accounts;this.sessions=sessions;this.limits=limits;}
 private UserAccount account(Principal p){return p==null?null:accounts.current(p.getName()).orElseThrow(AuthFailure::reauth);}
 private AuthController.SessionView view(UserAccount u){return AuthController.SessionView.account(u,false,lifecycle.hasPassword(u.getId()));}
 @PostMapping("/password") Object password(@Valid @RequestBody Password b,Principal p,HttpServletRequest r,HttpServletResponse response){var u=account(p);if(u==null)throw AuthFailure.reauth();limits.assertAccountLoginAllowed(r.getRemoteAddr(),u.getId().toString());var updated=lifecycle.changePassword(u.getId(),b.currentPassword(),b.password());sessions.save(updated,r,response);return Map.of("message","密码已更新，其他设备已退出。当前设备仍保持登录。");}
 @PostMapping("/email-change/request") Object email(@Valid @RequestBody Email b,@RequestHeader("Idempotency-Key")String key,Principal p,HttpServletRequest r){var u=account(p);if(u==null)throw AuthFailure.reauth();sessions.requireRecent(r);limits.assertMailAllowed(r.getRemoteAddr(),AccountService.normalize(b.email()),key);lifecycle.changeEmailRequest(u.getId(),b.email(),key);return ResponseEntity.accepted().body(Map.of("message","确认邮件已受理，请在新邮箱中确认。"));}
 @PostMapping("/email-change/confirm") Object confirm(@RequestBody Token b,Principal p,HttpServletRequest r,HttpServletResponse response){var u=account(p);if(u==null)throw AuthFailure.reauth();sessions.requireRecent(r);var updated=lifecycle.confirmEmail(u.getId(),b.token(),"CHANGE_EMAIL");sessions.save(updated,r,response);return Map.of("message","邮箱已更新，其他设备已退出。");}
 record Password(@NotBlank @Size(max=128)String currentPassword,@NotBlank @Size(max=128)String password){}record Email(@NotBlank @jakarta.validation.constraints.Email @Size(max=320)String email){}record Token(String token){}
}
