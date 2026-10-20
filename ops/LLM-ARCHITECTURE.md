# 大模型层架构（2026-10-20 决策）

> 状态：**设计已定，P1 待实现**（目前生产是"单平台 + 全局一把 key 池"的旧结构）。
> 目标：调用方只说"我要哪种模型"，**不知道也不关心** key 来自哪个平台；加平台/换模型不改调用代码。

---

## 1. 需求（来自产品侧，原文口径）

1. 现在**只有 DeepSeek 一把 key**，但架构要能容纳后续的 OpenAI / Claude 等平台，**迭代成本要低**。
2. 后台要能**管理模型与平台**，但**配置阶段不把 API key 放进后台**——key 继续由服务器本地环境变量读取，避免暴露。
   （若将来后台配 key，必须做到"没有泄露风险"才算数，见 §7。）

---

## 2. 现状与三个耦合点（为什么要改）

| 耦合点 | 现状 | 代价（真实事故） |
|---|---|---|
| key 是方法参数 | `QwenClient.complete(model, messages, key, timeout)`；Arena/Chat/English×3/Extract/Models/Rag/Toolbox/Trpg/Translate×2 各自 `AppConfig.llmKey()` | 调用方要知道"有 key 这回事"；`/api/models` 曾因单值 key 与列表 key 不同源而 401 |
| 模型名是裸字符串 | 调用方传 `AppConfig.llmModel()` 或厂商模型名 | 老会话里存的 `qwen3.8-max` 换平台后稳定 400 |
| base URL 全局单值 + key 池全局一份 | `AppConfig.llmBaseUrl()`、`QwenKeys` 一个 `UNUSABLE` 表 | 多平台时两家 key 混池，排障不知道是谁的 key |

调用点数量：`complete` 8 处、`streamChat` 3 处；自己取 key 再传的 12 处。

---

## 3. 目标分层

```
Controller / Service            ← 只知道"要用哪种模型"：Llm.chat("chat", msgs)
        │  别名；无 key、无 modelId、无 baseUrl、无平台名
        ▼
LlmGateway（唯一门面）
   ├─ ModelRegistry    别名 → [{provider, modelId}]（可带 fallback 链）   ← ui_config，后台可管
   ├─ ProviderRegistry provider → {protocol, baseUrl, enabled}           ← ui_config，后台可管
   ├─ KeyRing          provider 名 → key 列表（**只来自环境变量**）      ← 不在后台
   ├─ 用量记账 / 日限额（复用 LlmUsageService，账本加 provider 维度）
   └─ 错误归一          LlmException(用户可读文案 + 上游状态码)
        ▼
LlmTransport（按 protocol 分实现）
   ├─ OpenAiCompatTransport   现 QwenClient 的 HTTP 部分（deepseek / openai / qwen 都走它）
   └─ AnthropicTransport      Claude 专用（差异见 §6）——**先只留接口，不实现**
```

**公共 API（定死，不再变）**：

```java
String chat(String alias, List<Msg> messages)
String chat(String alias, List<Msg> messages, int timeoutSec)
String stream(String alias, List<Msg> messages, StreamHandler h)
String stream(String alias, List<Msg> messages, boolean disableThinking, StreamHandler h)
List<ModelInfo> models()      // {alias, provider, modelId, available}，给 /api/models 与后台下拉
```

`ModelInfo` 让前端"可以调用什么模型"来自**注册表**，而不是去 provider 拉（现在 `/api/models` 直连
provider 的 `/models`，换平台就变；DeepSeek 的 `/models` 还只列了两个 id）。

---

## 4. 配置分两处（关键决策）

| 内容 | 存哪 | 谁改 | 为什么 |
|---|---|---|---|
| **API key** | 服务器环境变量（`/root/Notelab/notelab-java/.env`，chmod 600） | 运维 ssh 改文件 | 不落库、不出现在任何接口响应里；页面只显示"已配置 + 掩码尾部" |
| 平台（protocol/baseUrl/enabled/label） | `ui_config.llm_providers` | 后台页面 | 非机密，改了立即生效 |
| 模型别名（alias → provider+modelId+fallback+default） | `ui_config.llm_models` | 后台页面 | 换模型/换平台 = 改这里，**不动代码不重启** |
| 每日限额 | `ui_config.llm_quota` | 后台页面 | 已实现 |

### 环境变量命名（可扩展，加平台只加变量）

```bash
# 列出启用哪些平台（逗号分隔，名字即 provider key）
LLM_PROVIDERS=deepseek

# 每个平台三条，PROVIDER 大写
LLM_DEEPSEEK_PROTOCOL=openai        # openai | anthropic（默认 openai）
LLM_DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
LLM_DEEPSEEK_API_KEYS=sk-xxx,sk-yyy # 逗号分隔，KeyRing 轮换用

# 以后加 OpenAI / Claude 只需再加两组：
# LLM_OPENAI_PROTOCOL=openai
# LLM_OPENAI_BASE_URL=https://api.openai.com/v1
# LLM_OPENAI_API_KEYS=sk-...
# LLM_ANTHROPIC_PROTOCOL=anthropic
# LLM_ANTHROPIC_BASE_URL=https://api.anthropic.com
# LLM_ANTHROPIC_API_KEYS=sk-ant-...
```

**兼容**：旧的 `LLM_BASE_URL / LLM_MODEL / LLM_API_KEYS / QWEN_*` 继续识别，
在"没配 `LLM_PROVIDERS`"时视为单个名为 `default` 的平台（引导期用），保证升级瞬间不炸。

---

## 5. 别名语义（别拿厂商模型名当别名）

| 别名 | 用途 | 现在映射 |
|---|---|---|
| `chat` | 通用对话/抽取/翻译判分 | `deepseek:deepseek-flash` |
| `reasoning` | 需要更强推理（博客 AI 起草、RAG 长文） | `deepseek:deepseek-v4-pro` |
| `fast` | 高频、短输出（判分、分类） | `deepseek:deepseek-flash`（可加 fallback） |

换平台 = 改 `models.chat.provider`；换模型 = 改 `models.chat.id`；两个都**不用改调用代码**。
`chat` 保留为默认别名（`models.default`），所以 `Llm.chat("chat", …)` 永远可用。

---

## 6. 加 Claude 时到底差在哪（现在留 seam，别预先实现）

OpenAI 兼容协议与 Anthropic Messages API 的差异（决定 transport 必须是可插拔的）：

| 项 | OpenAI 兼容 | Anthropic |
|---|---|---|
| 鉴权头 | `Authorization: Bearer <key>` | `x-api-key: <key>` + `anthropic-version: 2023-06-01` |
| system 提示 | messages 里 `role:"system"` | 顶层 `system` 字段 |
| 响应正文 | `choices[0].message.content` | `content[0].text` |
| 流式事件 | `data: {choices[0].delta.content}` + `[DONE]` | `content_block_delta` / `message_stop`，字段名不同 |
| 用量 | `usage.prompt_tokens/completion_tokens` | `usage.input_tokens/output_tokens` |
| 思考链参数 | `enable_thinking`（DeepSeek/Qwen 各自命名） | `thinking.budget_tokens` |

→ 结论：**协议差异隔离在 transport 内**，`LlmGateway` 与调用方完全无感。
现在只有 DeepSeek，所以 `AnthropicTransport` **只留接口不写实现**（Rule of Three：第二个平台真出现再写）。

---

## 7. 后台管理"key"的边界（当前不做写入）

- **P3 只做只读**：显示 provider、baseUrl、protocol、`key 数量`、`掩码尾部`（如 `sk-972f…1c97`）、连通性测试按钮。
- **不提供回显明文**的接口，也不提供"把 key 读出来"的接口 —— 一旦有这种接口，后台就成了密钥泄露源。
- 将来若真要后台写 key，必须同时满足：① 落库前 AES-GCM 加密（密钥在环境变量 `LLM_KEY_ENC_SECRET`，不在库里）；
  ② 接口**只写不读**，读只返回掩码；③ 每次改动写审计日志；④ 与 env 的优先级明确（建议：库里有就用库，否则回落 env）。
  这四条缺一条就不要做。

---

## 8. 迁移阶段（每步可独立上线、可回滚）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P1** | 建 `LlmGateway` + `ProviderRegistry`（env 驱动）+ `KeyRing`（provider 粒度）；`LlmTransport` 抽出 openai 兼容部分；`QwenClient` 旧签名保留并 `@Deprecated` 转发 | 行为零变化：聊天/判分/TRPG 全通；`/api/health/llm` 仍 available |
| **P2** | 11 个调用点改成 `Llm.chat("chat", …)`，删掉 12 处 `AppConfig.llmKey()`；`/api/models` 改读 `ModelRegistry` | 全站功能回归；`/api/models` 返回别名列表（不再依赖 provider 的 /models） |
| **P3** | 注册表落 `ui_config.llm_providers` + `llm_models`；后台"模型与平台"页（**只读 key**） | 后台改别名/切换默认 → 立即生效，无需重启 |
| **P4** | 账本加 `provider`（+`alias`）列，用量页按平台/别名分组；限额可按别名设；`fallback` 链 | 切平台后账本仍可分辨 |
| **P5**（可选） | 独立进程 `llm-service`（:8003，`/v1/chat` `/v1/stream` `/v1/models`，SSE 透传），java 与 ai-lab(Python) 共用 | 只有跨语言复用才值得：多一个进程 + SSE 治理成本 |

**P1+P2 是一天量级**，做完"调用方不需要知道 key 从哪来"就已成立；P3 之后"后台管模型"成立。

---

## 9. 必须守住的口径（踩过的坑）

1. **所有出网调用只能经过 Gateway**：新增出网点必须复用它，否则账本漏记、限额漏拦。
2. **`LlmHealth` 的探活也得走 Gateway**（现在它自己发 HTTP，不记账、也不受限额约束）——P2 一起收口。
3. **流式必须带 `stream_options.include_usage`**（OpenAI 兼容侧），否则流式调用的 usage 拿不到，
   而聊天/TRPG 全是流式（漏记大头）。
4. **改 `.env` 变量名必须与读新名的代码同一次发布**（2026-10-20 事故：先改 env 后上代码 → 回落到默认值 + 旧 key）。
5. **账本与限额已实现**（表 `llm_usage` + `ui_config.llm_quota`），本次改造复用，不重写。
6. `/etc/environment` **不要再放 key**：它是全局环境变量，优先级高于 `.env`，曾把 `/api/models` 打成 401。
