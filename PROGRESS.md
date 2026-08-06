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

## 阶段2（2026-08-06）✅ 完成

### 完成内容
- conversations 全套：GET/POST /api/conversations、GET /api/conversations/{cid}/messages、
  DELETE /api/conversations/{cid}、POST /api/conversations/{cid}/model（默认模型、404 语义与 Python 一致）。
- POST /api/chat：SSE 流式（`data: {"delta":...}` 帧 + 结束 `{"done":true}`），用户消息先入库、
  首条消息自动设为标题（前 30 字符）、助手回复完整入库、上游非 200/超时/异常分别发 error 事件，文案与 Python 一致。
- POST /api/toolbox：4 个动作（summarize/translate/rewrite/sentiment）提示词照抄 Python 版。
- POST /api/extract：结构化抽取，raw + result（首个 `{` 到末尾 `}` 的 JSON 解析）逻辑与 Python 一致。

### 验证（pm2 restart + /tmp/cmp_stage2.sh，8000 vs 8001）
- 创建会话：`{"id":N,"title":"新对话","model":"qwen3.8-max"}` 两端一致；默认模型创建一致 ✅
- 会话列表：键结构、条目字段、updated_at 格式（`2026-08-06 23:29:28`）一致 ✅
- 会话消息：空会话/404 语义一致 ✅
- chat SSE 第一轮：两端事件序列均为 `{"delta":...}* + {"done":true}` ✅
- 多轮历史：第二轮问「刚才记住的数字」，两端均正确回答 42（历史入库生效）✅
- 标题自动命名：两端标题均被设为首条消息前 30 字 ✅；消息数 4 条、角色交替一致 ✅
- chat 错误路径：空消息 400、会话不存在 404，文案一致 ✅
- set-model/delete：200/404 行为一致，删除后查消息 404 ✅
- toolbox：summarize 返回 `{action,name,result}` 结构一致；未知动作 400、空文本 400 文案一致 ✅
- extract：`{raw,result}` 结构一致，抽取结果完全一致（姓名/电话/城市）✅

### 下一步（阶段3）
- RAG：/api/rag/upload（JSON {name,content}，注意不是 multipart，以 main.py 为准）、/api/rag/docs、/api/rag/ask
- 英语：scenarios / conversations（创建时生成开场白）/ messages / delete / chat（JSON 语法纠错，非流式）

## 阶段3（2026-08-06）✅ 完成

### 完成内容
- RAG：POST /api/rag/upload（JSON {name,content}，文件名清洗规则与 Python 一致）、GET /api/rag/docs、
  POST /api/rag/ask（bigram 重叠打分 top3 + 引用编号提示词，照抄 Python）。文档持久化在 data/uploads/*.txt，
  启动时自动加载；与 Python 版 uploads 目录互相独立。
- 英语学习全套：GET /api/english/scenarios（8 场景常量照抄）、GET|POST /api/english/conversations
  （创建时调用模型生成开场白、非法场景回退 free）、GET .../messages（含 correction/error_note 字段）、
  DELETE、POST /api/english/chat（语法纠错 JSON 提示词照抄 Python；注意该接口在 Python 版即为非流式 JSON，
  任务简报中的「SSE」描述以 main.py 源码为准）。

### 验证（pm2 restart + /tmp/cmp_stage3.sh，8000 vs 8001）
- RAG upload：名称清洗 `我的 文档@v1 → 我的_文档_v1` 两端一致；长文分块数一致（2 块）；空内容 400 一致 ✅
- RAG docs：条目结构一致 ✅
- RAG ask：**citations 完全一致（同一片段、同分 0.438）**，证明 chunk/bigram/round 逻辑逐位对齐；
  两端回答都正确引用 [1]；空问题 400 一致 ✅
- English scenarios：diff 无任何差异 ✅
- English 创建会话：结构一致、开场白正常生成、非法 scenario 回退 free ✅
- English chat：两端均把 "I go ... yesterday and eat noodles" 纠错为 "I went ... and ate noodles"，
  error_note 为中文说明；响应键集合一致 ✅
- English messages/list/delete：字段结构、404、删除语义一致 ✅

### 下一步（阶段4）
- POST /api/arena（并行多模型 + 10s 心跳）
- GET|POST /api/ui-config（30s 缓存 + 失效逻辑）
- 全量回归所有接口

## 阶段4（2026-08-06）✅ 完成

### 完成内容
- POST /api/arena：并行调用最多 6 个模型（CachedThreadPool + 队列聚合），SSE 事件序列与 Python 一致：
  `{"started":true,"models":[...]}` → 每个模型完成即发 `{"model","content"}` 或 `{"model","error"}`（按完成顺序），
  期间每 10s 无结果发 `{"keepalive":true}` 心跳，最后 `{"done":true}`。
- GET /api/ui-config：`{config, defaults}`，config 走 30s 缓存（DB → 默认值兜底）。
- POST /api/ui-config：原始入参解析（非法 JSON → 400 {"error":"请求体不是合法 JSON"}，与 Python 一致），
  `config = body.config 或 body`，仅保留 background/menus 两键（缺省用默认值），入库后缓存失效。

### 验证（pm2 restart + /tmp/cmp_stage4.sh，8000 vs 8001）
- arena 双模型并行：两端事件序列完全一致（started → qwen-plus 404 error 事件（该网关无此模型，两端同样报错，
  文案 `HTTP 404: {"error":{"message":"Model not exist."...` 一致）→ qwen3.8-max content 事件 → done）✅
- arena 坏模型名：error 事件结构一致 ✅
- arena 校验：空消息 400、无模型 400 文案一致 ✅
- **arena 心跳实测**：单模型长任务（约 70s）期间每 10s 收到一个 `{"keepalive":true}`（共 7 个），
  随后 content + done ✅
- ui-config GET：键结构与 defaults 完全一致 ✅
- ui-config POST 往返：保存 `{"menus":{"chat":{"name":"对话Pro","icon":"🚀"}}}` 后 /api/menu 立即生效，
  background 颜色同步生效；还原默认成功 ✅
- ui-config 非法 JSON / config 非对象：两端均 400 且文案一致 ✅

## 全量回归（2026-08-06）✅ 通过

重跑阶段1~4 全部对比脚本（/tmp/cmp_stage1.sh ~ cmp_stage4.sh），所有接口 8000 vs 8001 行为一致：

| 模块 | 接口 | 结果 |
|---|---|---|
| 认证 | register/login/logout/me | ✅ 校验文案、状态码、Cookie 双向互认一致 |
| 菜单/模型 | menu / models | ✅ 结构、顺序、缓存行为一致 |
| 智能对话 | conversations* / chat SSE | ✅ 流式帧、历史、自动命名、404/400 一致 |
| 文本工具箱 | toolbox | ✅ 4 动作 + 错误路径一致 |
| RAG | upload/docs/ask | ✅ 名称清洗、分块数、检索打分（0.438）逐位一致 |
| 英语学习 | scenarios/conversations/messages/delete/chat | ✅ scenarios diff 为空、纠错结构一致 |
| 结构化抽取 | extract | ✅ raw/result 一致 |
| 模型竞技场 | arena | ✅ 事件序列 + 心跳一致 |
| 界面配置 | ui-config GET/POST | ✅ 往返 + 错误路径一致 |

期间还验证：共享 Redis 限流（Python 用尽配额后 Java 同样 429；本轮回归 Java 登录也被同一限流键拦下，
换 X-Forwarded-For 后通过——与 Python 行为完全一致）。

### 剩余工作（切流前，属后续步骤）
1. 数据迁移：Python MySQL 的 users/conversations/messages/english_*/ui_config → data/notelab-java.db
   （用户密码哈希与 id 需原样迁移，迁移后交叉登录态即可无缝衔接）。
2. 人工切流：/root/myapp/next.config.ts 的 rewrites 目标 8000 → 8001，重启 myapp。
3. 观察期后 pm2 stop notelab。
