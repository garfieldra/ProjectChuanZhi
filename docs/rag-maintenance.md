# 音乐知识库维护与验证

本功能默认只允许人工维护。候选提取和向量化不赋予自动审核或自动发布权限。当前不启用自动采集器。本文更新于 2026-11-11，按合并 SoundSeek 主干后的配置说明。

旧 `KNOWLEDGE_MAINTAINERS` 邮箱名单不再授予权限；账号升级后需重新登录并按 UUID 配置，见 [账号维护说明](./account-maintenance.md)。本记录中的原有测试数量属于账号升级前的 RAG 验证。

## 配置与启动

在服务端设置 `KNOWLEDGE_MAINTAINER_IDS` 为已经注册、邮箱已验证的维护者账户 UUID，多个 UUID 用英文逗号分隔；默认空值不授予任何账号维护权限。该配置不能由用户注册表单修改。Java 原生运行时也需在运行配置中设置此环境变量。

| 配置 | 当前用途 |
| --- | --- |
| `KNOWLEDGE_MAINTAINER_IDS` | Java 侧已验证账号 UUID 白名单，默认空值；配置后重启对应 Java 服务 |
| `KNOWLEDGE_MIN_SIMILARITY` | Agent 检索最低向量相似度，默认 0.5，不代表事实置信度 |
| `KNOWLEDGE_JAVA_BASE_URL` | 知识 Worker 的 Java 内部地址，Compose 默认 `http://java-service:8080` |
| `AGENT_INTERNAL_SERVICE_TOKEN` | Java、Agent 和知识 Worker 共享的内部服务凭据；不要提交实际值 |

```powershell
docker compose --env-file .env -f infra/compose.yaml -f infra/compose.knowledge.yaml --profile knowledge up -d --build web agent-service agent-worker knowledge-worker
```

当前基础配置中的 `agent-worker` 仍依赖旧 `milvus`，上述命令可能一并启动旧 Milvus/etcd/MinIO；正式 RAG 读取独立的 `knowledge-milvus`，不自动复用旧集合。`/health/ready` 是 Agent 就绪检查，不表示模型已预热或知识索引已追平。

停止知识环境时也使用相同配置：

```powershell
docker compose --env-file .env -f infra/compose.yaml -f infra/compose.knowledge.yaml --profile knowledge down
```

默认保留数据卷。

IDEA 原生 Java 场景可使用本机单独维护的 Compose 覆盖配置，为 Agent 设置 `JAVA_INTERNAL_BASE_URL=http://host.docker.internal:8080`，为知识 Worker 设置 `KNOWLEDGE_JAVA_BASE_URL=http://host.docker.internal:8080`。先启动数据库、Redis、RabbitMQ 和 Agent，再由 IDEA 运行 Java；单独启动知识组件时显式指定 `knowledge-milvus knowledge-worker`，避免启动重复 Java 容器。本机的 `compose.idea.yaml`、`compose.local.yaml` 不属于仓库标准启动所需文件。

知识 Milvus 使用官方 standalone 支持的内嵌 etcd 和本地存储，独立 volume 为 `knowledge-milvus-data`，不复用或迁移旧 MinIO 数据。普通对话不依赖该服务就绪。模型缓存属于正常运行依赖，应持久保存；Docker 数据盘和 Windows 下载/缓存遵循 E 盘偏好。

## 维护操作

登录维护者账号后进入 `/#knowledge` 或 `/explore#knowledge`，也可使用“维护音乐资料”入口。新版首页与工作台在登录/退出后都会重新查询维护权限。录入规范实体 ID、知识表述、适用范围、来源和必要短摘录。保存只产生草稿，审核通过后还需显式发布。修改发布内容创建新版本；旧已发布版本在新版本发布前仍可用。

发布前由 Agent 使用各索引代次的固定模型分词器检查完整文档模板，输入须不超过 480 token。表述、实体、别名和边界都会占用输入预算；超长时请拆分为原子资料或缩短非必要字段，再重新审核发布。检查不可用时保留原发布状态，不创建新索引任务，稍后可重试发布。

“多实体、多来源及别名编辑”中的 JSON 在开始编辑前同步表单最新内容。JSON 编辑后若又修改表单，应用时会提示冲突；点击“载入最新表单内容”后重新编辑，避免旧 JSON 覆盖新内容。

JSON/JSONL 批量导入使用与 `KnowledgeContracts.Submission` 相同的字段：`knowledgeType`、`summary`、`boundary`、`attribution`、`applicability`、`aliases`、`entities`、`support`。每个 support 包含 source、excerpt、locator 和 relation。source 必须提供稳定 identifier、title、rightsNote 和 materialType。对采访观点填写 attribution；对主题卡保留解释边界，不导入歌词原文。

Markdown/TXT 可载入人工整理区；超过单条限制时必须由维护者整理为原子知识，不会自动截断后发布。指定网页读取只返回候选正文，维护者应选择必要短摘录并确认使用方式。读取文件或网页不会自动调用模型；需要时再点击“辅助提取候选（使用模型）”，生成结果仍须人工确认。

在“多实体、多来源及别名编辑”中可设置多个支持/冲突来源及实体角色；来源状态、索引失败、重试、重建和操作记录均可从维护页查询。公开引用可以点击展开并重新核对当前状态。列表会分别显示“第 1 版已发布 · 第 2 版待审核/审核通过”，避免将新草稿误当作已发布版本。

### 最小 JSON 导入示例

以下为合成格式示例，不是可用于真实音乐回答的资料。正式导入前必须换成已核验实体、实际支持材料和允许的使用说明。JSON 文件使用单条对象或对象数组，JSONL 每行放一条对象；通过 HTTP 导入时再包在 `{ "origin": "MANUAL_FILE", "candidateItems": [...] }` 中，并提供 `Idempotency-Key` 请求头。

```json
{
  "knowledgeType": "FACT",
  "summary": "此条仅演示知识导入结构，不构成真实音乐事实。",
  "boundary": "合成示例，仅用于隔离验收。",
  "entities": [{"type": "WORK", "id": "00000000-0000-4000-8000-000000000001", "role": "SUBJECT"}],
  "support": [{
    "source": {"identifier": "documentation:synthetic-example", "title": "合成格式示例", "rightsNote": "自行编写的测试材料", "materialType": "TEST_FIXTURE"},
    "excerpt": "此条仅演示知识导入结构，不构成真实音乐事实。",
    "locator": "示例段落",
    "relation": "SUPPORTS"
  }]
}
```

## 索引与撤回

撤回先在 MySQL 权威状态生效，不等待向量删除。旧版本命中不会替换成新版本正文。重新发布已撤回的内容必须创建并审核新版本，避免同一版本的旧删除任务与重新发布冲突。

索引任务最多尝试五次，失败按退避重试。失败任务可人工重试；定时对账补发未完成任务，并周期重应用不可变版本以修复索引丢失。重建生成独立代次，已发布资料全部索引成功并记录质量验收后才允许切换，旧代次继续同步以支持回退。可在测试检索中指定待验收代次，任务成功不能替代质量评测。

嵌入模型固定到上游不可变快照。更换模型时须使用支持的模型名、实际 40 位快照修订、正确维度和新模板版本。最低向量相似度通过 `KNOWLEDGE_MIN_SIMILARITY` 配置，默认 0.5；必须用开发集标定，不将它当作事实正确率。

## 验证

- Java：`mvn test -f java-service/pom.xml`，知识服务与迁移测试使用隔离 MySQL，完整 Spring 合并集成测试还会创建 Redis/RabbitMQ；需要可用 Docker，不能指向真实用户库。
- Agent：`pytest -q`，在 agent-service 目录运行；覆盖权威回查、降级、版本和稳定引用。
- Web：`npm --prefix web test` 与 `npm --prefix web run build`。
- 检索质量：使用 `Demos/evals/knowledge/run_eval.py`，报告检索命中、支持条目及耗时；语义事实与归因仍需对照材料审阅。

最近一次合并复验为 Java 51 项、Agent 124 项、Web 66 项通过；各次范围与浏览器证据见 [验证记录](./rag-validation.md) 和 [浏览器验收](./rag-browser-acceptance.md)。

历史主题卡必须重新确认可用文件、实体和审核记录，再经正式导入入口发布。旧的直接 Milvus 写入脚本不能替代权威审核闭环。

## 与主干合并后的版本约定

主干的 `V23__create_saved_song.sql` 保持不变，RAG 新迁移顺延为 `V24__music_evidence_knowledge.sql` 和 `V25__pin_knowledge_model_revision.sql`。Java 知识模块随主干迁入 `com.soundseek.knowledge`；知识队列、死信和新建集合统一采用 `soundseek` 前缀。

主干 V23 数据库升级及空库完整迁移均通过隔离 MySQL 验证。本次合并不对现有业务数据库执行迁移。已执行合并前 RAG V23/V24 的试验库不能直接套用新编号或自动 repair；此类试验库应先备份，再单独处理迁移历史与数据兼容。
