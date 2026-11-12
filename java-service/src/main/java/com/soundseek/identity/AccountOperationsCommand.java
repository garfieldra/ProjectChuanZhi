package com.soundseek.identity;
import java.util.UUID;import org.springframework.boot.ApplicationArguments;import org.springframework.boot.ApplicationRunner;import org.springframework.boot.SpringApplication;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.context.ConfigurableApplicationContext;import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="soundseek.account.cli.enabled",havingValue="true")
public class AccountOperationsCommand implements ApplicationRunner {
 private final AccountOperations operations;private final ConfigurableApplicationContext context;
 public AccountOperationsCommand(AccountOperations operations,ConfigurableApplicationContext context){this.operations=operations;this.context=context;}
 public void run(ApplicationArguments args){String action=one(args,"account-action"),raw=one(args,"account-id");UUID id=UUID.fromString(raw);
  if(action.equals("preview"))System.out.println(operations.preview(id));
  else if(action.equals("disable")){if(!raw.equals(one(args,"account-confirm")))throw new IllegalArgumentException("确认 UUID 不匹配。");operations.disable(id,one(args,"account-reason"));System.out.println("Account disabled: "+id);}
  else throw new IllegalArgumentException("只支持 preview 和 disable；本版本没有物理删除能力。");
  System.exit(SpringApplication.exit(context,()->0));
 }
 private String one(ApplicationArguments args,String key){var values=args.getOptionValues(key);if(values==null||values.size()!=1)throw new IllegalArgumentException("需要参数 --"+key);return values.getFirst();}
}
