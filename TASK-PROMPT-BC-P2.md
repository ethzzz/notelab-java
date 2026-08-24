# B/C 端拆分 · 阶段 2：后端 C 端数据域与内容发布（notelab-java）

你在服务器 /root/notelab-java（Spring Boot 3.4.5 / Java 17 / MyBatis-Plus 3.5.9，pm2 `notelab-java` :8001）。
这是「B/C 端拆分」的**阶段 2**：C 端游玩数据域 + 内容发布机制 + C 端游玩/配置接口。

## 背景事实（已核实，直接采信）

- 阶段 1 已完成：`c_users`/`c_user_groups` 表、`Session.makeCToken/parseCToken`（4 段 `c.<uid>.<exp>.<sig>`）、Cookie `notelab_c_session`、`CAuthUtil`、`/api/c/auth/*`、`/api/c-admin/*`（commit 见 git log）。先读这些代码沿用同一套模式。
- TRPG：`TrpgController`（/api/trpg/*）→ `TrpgService` → `TrpgDao`/Mapper；表 `trpg_scenarios(id,user_id,title,genre,summary,config_json,scenario_json,...)`、`trpg_playthroughs(id,scenario_id,user_id,current_node,state,ending_title,steps,history_json,...)`。
- Spire 工坊内容存 `ui_config` 表的 JSON 顶层键 `"spire"`（{cards,characters,skills}），经 `SpireContentController` + `UiConfigService.getConfig()` 读写，**不新建表**。
- `/vs`（吸血鬼幸存者）后端无任何接口与表——纯前端，本阶段对它零改动。
- 背景配置：`ui_config` 顶层键 `"background"`，`MenuController` 已把它放进 /api/menu 响应。
- 持久层模式：静态门面 DAO（Map 出口、snake_case、`RowUtil`）、`DaoSupport` 持有 Mapper/TransactionTemplate、建表在 `DbSchema`。

## 硬性纪律

1. **共享 MySQL**：开工前 `mysqldump notelab > /root/notelab-java/data/bc-p2-dump.sql`；只允许 ALTER TABLE ADD COLUMN（向后兼容、带默认值），禁止改列/删列/改现有表语义。
2. 不动 /root/notelab（Python 版）、/root/myapp、阶段 0 的 nginx/壳、阶段 1 已完成代码的既有行为。
3. B 端既有接口的请求/响应契约**逐字节不变**（唯一例外见下：B 端 plays 列表补 scope='b' 过滤——存量数据默认 'b'，结果集不变）。
4. 每步构建（`mvn -f /root/notelab-java/pom.xml -DskipTests -q package`）+ `pm2 restart notelab-java` + curl 验证通过后再继续；失败必须回滚修复。

## 实施内容

### 1. 数据归属（DbSchema 里只增不改地补列 + 一次性 ALTER）
- `trpg_playthroughs` 加 `scope CHAR(1) NOT NULL DEFAULT 'b'`（存量即 B 归属，零数据迁移），并加索引 `KEY idx_trpg_p_scope_user (scope, user_id)`。
- `trpg_scenarios` 加 `published TINYINT NOT NULL DEFAULT 0`。
- 实现方式：DbSchema 里用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 不可用（MySQL 不支持）——按现有风格先查 information_schema 判断列不存在再 ALTER（可参考项目内已有守护写法；没有则新增一个私有方法），保证重启幂等。

### 2. B 端发布接口（登录态即可，路由自动进 perm_routes）
- `POST /api/trpg/scenarios/{id}/publish` → `published=1`；`POST /api/trpg/scenarios/{id}/unpublish` → `published=0`。剧本不存在 404。
- `POST /api/spire-content/publish`：把当前 `spire` 键整体快照写入 ui_config 新顶层键 `spire_published`（合并写，保留 background/menus/spire 等其它键，参考 SpireContentController.save 的写法）；`POST /api/spire-content/unpublish` 删除该键。
- B 端剧本列表/详情接口响应里带出 `published` 字段（新增字段，不改原有字段）。

### 3. C 端游玩接口（新 `controller/CTrpgController`，前缀 /api/c/trpg，全部经 CAuthUtil）
复用 `TrpgService` 逻辑（允许给它加 scope 参数/重载，不得改变 B 端调用路径的行为）：
- `GET /api/c/trpg/scenarios`：仅 `published=1`，字段与 B 端列表同风格（RowUtil）。
- `GET /api/c/trpg/scenarios/{id}`：未发布或不存在 → 404。
- `POST /api/c/trpg/scenarios/{id}/play`：仅已发布剧本可开局，新存档 `scope='c'`、`user_id=C端uid`。
- `GET /api/c/trpg/plays` / `GET /api/c/trpg/plays/{id}` / `POST /api/c/trpg/plays/{id}/choose` / `DELETE /api/c/trpg/plays/{id}`：一律 `scope='c' AND user_id=当前C用户`，不属于自己的存档 404。
- 已开局后剧本被取消发布：允许继续该存档（不中断玩家），仅新开局受限。
- B 端既有 `/api/trpg/plays` 系列补 `scope='b'` 过滤（存量默认 'b'，行为等价）。

### 4. C 端配置接口（新 `controller/CConfigController`，前缀 /api/c/config）
- `GET /api/c/config/background`：**匿名开放**，返回 ui_config 的 `background` 节点（为空时返回 UiConfigService 的默认 background）。
- `GET /api/c/spire/content`：**匿名开放**，返回 `spire_published`（未发布时返回 `{cards:[],characters:[],skills:[]}`）。
- 这两个接口不要求登录（C 端外壳首屏与登录页都要用）。

### 5. 验证（全部通过才算完成）
超管 B Cookie 按 Session.java 签发；C Cookie 经 /api/c-admin 建测试用户后 /api/c/auth/login 获得（沿用阶段 1 方法）。
1. 构建重启正常、启动日志无 ERROR、perm_routes 数增加；两张表新列存在且默认值正确（SHOW CREATE TABLE）。
2. B 建一个测试剧本 → 默认 unpublished → C 列表为空、详情 404 → publish → C 列表可见、可开局、choose 走通至少 2 步 → unpublish → 该存档仍可继续、但新开局 404。
3. 隔离性：C 的 /api/c/trpg/plays 只看到自己的 scope='c' 存档；B 的 /api/trpg/plays 完全看不到 C 存档（用相同数字 uid 构造对抗用例：给某 B 用户 uid 恰好等于 C 测试用户 uid 的场景，验证两端列表互不串）。
4. 背景：匿名 `GET /api/c/config/background` 200 且内容与 B 端 ui_config background 一致。
5. Spire：发布前 C 接口返回空三数组 → publish → 返回与 B 工坊当前内容一致 → unpublish → 回到空。
6. 回归：B 端 /api/trpg/scenarios、/plays、/api/menu、/api/ui-config、/api/spire-content 行为不变（至少逐接口 curl 对比）；Python 版 :8000 正常。
7. 清理验证产生的测试剧本/存档/测试 C 用户（ctest 前缀），恢复干净状态。

### 6. 提交
`git add -A && git commit -m "B/C拆分阶段2：C端数据域（scope/发布标记）+ /api/c/trpg 游玩接口 + /api/c/config 背景与Spire发布"`。
最终输出简明报告：改动清单、验证结果逐条、遗留事项。
