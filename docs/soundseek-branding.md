# 寻声 SoundSeek 改名说明

中文名：寻声。英文名：SoundSeek。技术标识：`soundseek`。

仓库继续使用 `garfieldra/ProjectChuanZhi`，本地目录为 `/Users/wangrui/Documents/Projects/ProjectChuanZhi`。

## 覆盖范围

页面标题、首页品牌、输入提示、公开执行状态、缺省封面、导出署名与文件名；Agent 身份、API 元数据与请求标识；前后端包名、Java 启动类、示例配置、消息队列、监控和项目文档统一为新名称。

## 运行配置

新项目默认 Compose 名称为 `soundseek`，数据库名与本地开发用户为 `soundseek`，Java 包为 `com.soundseek`，浏览器临时状态和访客 Cookie 使用 `soundseek` 前缀。演示脚本使用 `SOUNDSEEK_API_BASE_URL`。

Agent 队列为 `soundseek.agent-runs.v1`，死信队列为 `soundseek.agent-runs.dlq`；Java 服务与 Python Worker 必须从同一版本构建。Prometheus 指标、Grafana 查询和 Redis Session 名称同步更新。知识集合默认使用 `soundseek_song_theme_cards_v1`。

这些是新竞赛项目的默认配置。本次未连接或迁移旧项目线上数据库、队列、向量集合和容器卷。已有环境应先备份并显式核对 `.env` 中的数据库、Compose 名称和知识集合；若继续使用旧数据，保留实际数据库连接和 `KNOWLEDGE_COLLECTION` 配置。切换队列前先完成旧任务，切换 Cookie/Session 后需重新登录。不要删除旧容器卷来处理名称变化。

## 来源与部署

本项目基于本人既有的 [IndieSoundQuest](https://github.com/garfieldra/IndieSoundQuest) 继续开发，Git 提交历史保留。文档中的既往验收记录仅描述当时结果，不代表本分支已完成相同验收。

旧域名 `indiesoundquest.cn` 属于原项目演示；新项目尚未声明新的公网域名。部署时使用 `.env.production.example` 中的 `SITE_ADDRESS` 指定实际持有的域名。
