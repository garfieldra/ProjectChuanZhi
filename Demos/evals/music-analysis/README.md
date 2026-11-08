# 音乐分析与推荐边界 E2E 评测

从项目根目录运行（使用已构建的 Agent 镜像；测试会创建独立访客会话，不读正式用户数据）：

```bash
docker run --rm --network soundseek_internal \
  -v "$PWD/Demos:/demos" -e PYTHONDONTWRITEBYTECODE=1 \
  soundseek-agent-service \
  python /demos/evals/music-analysis/run_eval.py \
  --base-url http://java-service:8080
```

可用 `--case creative-context` 只运行一个用例，或用 `--baseline /demos/evals/music-analysis/reports/<旧报告>.json` 比较。完整 JSON 报告保存在 `reports/`，已忽略提交；它包含用户问题、模型回答和公开运行事件，但不包含 Cookie 或密钥。

2026-10-05 首轮真实验收覆盖六类问题：单曲描述、创作背景、双曲对比、多轮追问、资料不足、普通推荐。基线发现并修复了分析 Claim 引用未随卡片展示、Last.fm 空结果退回凭印象推荐、DeepSeek 函数调用失败时推荐卡丢失、明确要求“其他艺人的三首歌”却混入原艺人且数量超出的错误。最终同一构建的六例全通过。耗时分别为 264、85、135、92、59、32 秒；最慢一例的公开事件出现 Worker 恢复，不能把它当作正常研究耗时，也不能把这组小样本当性能承诺。

结构检查只能验证可见引用关系、卡片类型和数量等，不能证明音乐事实真实。事实准确性仍需来源精读与人工或独立评审；研究预算应在扩大样本后再调优，不能仅凭一次运行盲目缩短搜索。
