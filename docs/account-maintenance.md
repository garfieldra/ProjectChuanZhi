# 账号与 SMTP 维护说明

账号方案见 [精简设计](./account-system-design.md)。本版本包含邮箱确认注册、已有账户补验证、忘记/修改密码、邮箱变更、GitHub 再认证、当前/全部退出、会话状态复核和运维停用/清理预览。不含 MFA、身份绑定、物理删除和数据导出。

## 本地邮件收件箱

`.env` 内设置 `AUTH_PUBLIC_BASE_URL` 为实际前端地址，为 `AUTH_MAIL_ENCRYPTION_KEY` 生成随机 32 字节 Base64 值（例如 Python `secrets.token_bytes(32)` 后 Base64 编码）。密钥仅保留本机配置，不提交仓库、不显示在截图或日志。

```powershell
docker compose --env-file .env -f infra/compose.yaml -f infra/compose.auth-mail.yaml --profile auth-mail up -d --build web agent-service agent-worker
```

打开前端 `http://localhost:5173`，注册后在 `http://127.0.0.1:8025` 查看邮件并点击确认。Mailpit 仅截获本地测试邮件，不向真实邮箱投递。其 SMTP 默认端口 1025、网页端口 8025；本编排只将网页绑定宿主机回环地址。[Mailpit 官方 Docker 说明](https://mailpit.axllent.org/docs/install/docker/)

知识库可同时叠加 `-f infra/compose.knowledge.yaml --profile knowledge` 并显式启动 `knowledge-worker`。本地 IDEA 覆盖文件由用户维护；通过宿主机运行 Java 时，可将 SMTP_HOST 设为对应已发布的本机 SMTP 端口，不能把容器内的 localhost 当作另一个容器。

停止时使用同样的叠加配置与 profile，默认保留业务卷。不要将 Mailpit 验收收件箱暴露到公网或作为正式邮件转发服务。

## 正式 SMTP

| 配置 | 用途 |
| --- | --- |
| `AUTH_MAIL_ENABLED` | 默认 false，正式启用前完成实际收件测试 |
| `AUTH_MAIL_FROM` | 供应商允许的发信地址 |
| `AUTH_PUBLIC_BASE_URL` | 本部署固定前端 origin，例如 https://music.example.com；同时用于 GitHub 回调构造 |
| `AUTH_MAIL_ENCRYPTION_KEY` | Base64 的随机 32 字节密钥，用于加密待发送参数 |
| `SMTP_HOST` / `SMTP_PORT` | 发信服务器与端口，按供应商提供的配置填写 |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | 专用 SMTP 账号/授权码，仅服务端注入 |
| `SMTP_AUTH` | 正式服务通常需要身份验证，测试收件箱可关闭 |
| `SMTP_STARTTLS` / `SMTP_SSL` | 按供应商选择 STARTTLS 或隐式 TLS，不同时开启 |
| `KNOWLEDGE_MAINTAINER_IDS` | 显式允许的已验证账户 UUID，英文逗号分隔；默认无人有权限 |

Java 使用 `spring-boot-starter-mail`，连接/读/写超时均为 5 秒。[Spring Boot 邮件说明](https://docs.spring.io/spring-boot/reference/io/email.html)

SMTP 故障不阻断普通音乐功能的健康检查。申请响应只表示已受理；投递异步重试最多 5 次，失败记录 SMTP_DELIVERY_FAILED，不记录原始邮件或供应商异常。检查 auth_mail_job 中的状态/次数/脱敏原因即可，禁止将 ciphertext 解密内容贴进工单。修复配置后用户可重新申请；到期和失败任务会清除投递密文。

服务端不得信任任意请求 Host 或转发头构造验证链接。默认不处理转发头；如启用代理感知，应在反代/容器网络限制可信来源。生产会话必须启用 Secure Cookie，GitHub 注册的回调需匹配固定公开地址 `/login/oauth2/code/github`。

## 升级与会话

追加 V26，保留账户 UUID、主资产空间及业务数据。真实旧邮箱标为未验证，原 GitHub 内部占位邮箱改为无真实联系邮箱。不会把历史邮箱自动标成已验证。

升级后的 Session namespace 为 soundseek:session:v2，旧会话需重新登录；邮件未配置时，访客和已有密码/GitHub 登录仍可用，新注册/找回/邮箱变更返回明确不可用提示。旧邮箱维护白名单不再生效，按账户设置中的 UUID 核实并配置新名单；服务端重启后生效，多实例需统一配置。

当前退出只使当前会话失效；密码找回、改密和全端退出递增 auth_version，使旧会话即使残留 Redis 也不可继续使用。退出不取消 ACTIVE 账户后台音乐任务；停用则取消任务并拒绝旧租约回调。登录归并会明确停止在途访客请求，保留历史消息，提示重新发送。

## 运维：仅预览与停用

运维命令只允许本机/容器操作权限持有人运行，不提供 HTTP 管理接口。复用部署环境的数据库、Redis、RabbitMQ 配置；独立 Java 进程只监听本机随机端口，操作完成退出。

```text
java -jar app.jar --server.address=127.0.0.1 --server.port=0 --soundseek.account.cli.enabled=true --account-action=preview --account-id=<UUID>
java -jar app.jar --server.address=127.0.0.1 --server.port=0 --soundseek.account.cli.enabled=true --account-action=disable --account-id=<UUID> --account-confirm=<同一个UUID> --account-reason=<理由>
```

preview 只列状态和资产数量；disable 停用并保留数据，撤销会话/挑战、取消任务并写审计。没有 delete 命令，任何物理清理均需另行确定范围和授权，不能把预览或停用当作已删除数据。

## 验证

Java：`mvn test -f java-service/pom.xml`，需要可用 Docker；Docker 新版 API 与旧客户端不兼容时，本机可追加 `-Dapi.version=1.44`。集成测试使用独立 MySQL/Redis/RabbitMQ 与测试内 SMTP 接收器，绝不配置为用户业务数据库。

Web：`npm --prefix web test`、`npm --prefix web run build`。实际结果与浏览器验收范围写入 [账号验证记录](./account-validation.md)，不把模拟 SMTP 收件宣称为生产邮箱投递成功。
