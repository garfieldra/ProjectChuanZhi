# 寻声 SoundSeek

通过自然对话帮助用户发现音乐、理解歌曲并探索听歌偏好的音乐探索应用。

## Language

**寻声（SoundSeek）**：
本项目的正式产品名称，中文使用「寻声」，英文使用「SoundSeek」。
_Avoid_: IndieSoundQuest、WebSurfing、SoundQuest（仅在来源说明中保留原项目名）

**统一音乐探索 Agent**：
面向用户的音乐探索助手，结合对话上下文自主选择发现、核验、推荐与研究能力。

**歌曲世界杯**：
通过歌曲两两对决探索偏好的可选互动模式。

## 当前实现（2026-11-10）

- `/` 为首页，`/explore` 为音乐探索工作台；主场景包含多轮研究、推荐、反馈、账号同步和“我的音乐”。
- 歌曲世界杯是统一 Agent 按需调用的 Skill，公开网络发现与 MusicBrainz 身份核验仍是主链路。
- 音乐证据型 RAG 已实现人工导入、编辑、审核、发布、版本更新、撤回、检索与引用。自动收集仅预留候选提交设计，没有启用采集器或自动发布。
- MySQL/Java 是知识权威中心，Milvus 是可重建索引；知识 Worker 独立于 Agent Worker。包名为 `com.soundseek.knowledge`，新知识集合按代次隔离。
- 维护入口为 `/#knowledge` 或 `/explore#knowledge`；维护者由 `KNOWLEDGE_MAINTAINERS` 指定，普通注册账号没有管理权限。登录/退出会刷新维护权限。
- 数据库迁移顺序为 V23 收藏、V24 知识库、V25 固定嵌入模型修订。已有旧版 RAG 试验库需另行处理迁移历史，不自动 repair 或重建业务库。

## 验证与边界

合并主干后的回归：Java 51 项、Agent 124 项、Web 66 项通过，前端生产构建通过。浏览器验收覆盖权限、JSON 冲突、审核发布、超长拒绝、重发及撤回，材料均为合成测试数据；真实音乐语料的 Recall、引用支持率与延迟仍待标注评测。

本地环境及临时端口不等于线上部署。文档优先入口：[README](./README.md)、[RAG 设计](./docs/rag-design.md)、[RAG 维护](./docs/rag-maintenance.md)、[回归记录](./docs/rag-validation.md)、[浏览器验收](./docs/rag-browser-acceptance.md)。
