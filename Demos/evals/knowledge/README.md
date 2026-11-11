# 音乐知识检索评测

此运行器只检索已发布资料，不注册账户、不导入或发布测试知识。设置环境变量 `RAG_EVAL_EMAIL` 和 `RAG_EVAL_PASSWORD`，使用已获维护权限的测试账户。

```powershell
python Demos/evals/knowledge/run_eval.py --cases path/to/cases.jsonl --split acceptance --output knowledge-eval.json
```

每行用例包含 `id`、`query`、`entities`、`expectedEntryIds`、`split`，无答案题设置 `expectNoMatch=true`。语料发布后将实际稳定条目 ID 填入 expectedEntryIds；不得把虚构音乐知识导入用户正式库来运行评测。

开发集用于参数校准，独立验收集不能参与阈值调节。用例应覆盖同名、别名、中英文、具体版本、语义背景、多对象、无答案、冲突与相关但不支持的材料。报告保存证据与耗时，不保存登录凭据。Recall/MRR 只衡量检索，不代表事实支持率；答案忠实度需使用音乐分析评测并人工对照原材料。
