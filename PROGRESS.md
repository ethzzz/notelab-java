# PROGRESS

## 阶段1（2026-08-06）✅ 完成

### 完成内容
- Spring Boot 3.4.5 骨架：端口 8001，绑定 127.0.0.1（同 Python 版 uvicorn host）。
- 配置加载：`进程环境变量 → ./.env → /root/notelab/.env（只读）→ 默认值`；实测读到
  QWEN_BASE_URL=token-plan 网关、QWEN_MODEL=qwen3.8-max、QWEN_API_KEY、SECRET_KEY（与 Python 版同源，保证 Cookie 兼容）。
- SQLite 独立数据文件 `data/notelab-java.db`：users / conversations / messages / english_conversations /
  english_messages / ui_config 全表结构（后阶段接口直接使用）。
- 认证四接口：register（用户名/密码/邮箱校验、限流 5次10分钟、PBKDF2-HMAC-SHA256 120000 轮）、
  login（限流 10次5分钟）、logout、me；HMAC 会话 Cookie 与 Python 版算法完全一致。
- GET /api/menu（含 ui-config 覆盖逻辑 + 30s 缓存）、GET /api/models（600s 缓存 + image/audio/tts/wan2/vl 过滤）。
- 限流：与 Python 版共用 Redis（键 rl:*），Redis 不可用退回内存滑动窗口。
- 404/405/422 错误响应风格对齐 FastAPI（{"detail": ...}）。
- CORS：允许 `https?://.*:3000` 携带 Cookie。
- pm2 进程 `notelab-java` 已启动；README.md 已更新。

### 验证（构建 + pm2 + curl 对比 8000 vs 8001）
```bash
mvn -DskipTests package                      # BUILD SUCCESS（target/notelab-java.jar）
pm2 start "java -jar target/notelab-java.jar" --name notelab-java
bash /tmp/cmp_stage1.sh                      # 对比脚本（内容见下）
```
结果：
- 未登录 /api/me、/api/menu、/api/models：两端均 `{"error":"请先登录"} | 401` ✅
- register：两端均 `{"ok":true} | 200` 并下发 notelab_session Cookie ✅
- **Cookie 双向互认**：
  - Python 签发的 cookie 经 Java 侧 HMAC 验签通过（用 Python 算法为 Java 用户 id=1 生成 token，
    Java /api/me 返回 `{"id":1,"username":"crosstest..."} | 200`）✅
  - Java 签发的 cookie 打 Python /api/me 验签通过并解析出用户（uid=2 → Python 库用户）✅
- /api/me 键集合一致：["email","id","username"] ✅
- /api/menu 完全一致：10 个菜单项、首项 dashboard、background 配置相同 ✅
- /api/models 完全一致：16 个模型、顺序相同、首项 qwen3.6-plus、默认模型 qwen3.8-max 在 index 15 ✅
- login 成功/失败、logout：响应体一致；Set-Cookie 细节差异见 README「已知差异」 ✅
- register 校验：用户名/密码/邮箱/非法JSON 错误文案与状态码一致（400/400/400/422）✅
- 404：两端均 `{"detail":"Not Found"} | 404` ✅
- 限流联动：Python 用尽 `rl:register:127.0.0.1` 配额后 Java 同样返回 429（共用 Redis 生效）✅

### 下一步（阶段2）
- conversations 全套（list/create/messages/delete/set-model）
- POST /api/chat SSE 流式（历史入库、首条消息自动命名）
- POST /api/toolbox、POST /api/extract
