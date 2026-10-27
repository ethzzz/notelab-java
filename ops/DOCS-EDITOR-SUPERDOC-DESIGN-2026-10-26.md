# B 端文档编辑改造：Quill + HTML → SuperDoc + DOCX（一期）

> 日期：2026-10-26　状态：已确认，实施中
> 涉及仓库：`notelab-java`、`notelab-b`

## 1. 背景与目标

现有 `/admin/documents/editor` 用 Quill 编辑富文本，正文以 HTML 存 `documents.content_html`；
导入 .docx 时服务端用 POI 解析成简化 HTML（页眉页脚丢失、图片降级为 `[图片]`），导出时再用 `docx` 库从 DOM 重新生成。

这条 `docx → HTML → docx` 的往返链路决定了版式上限：**任何 PDF 导出方案都还原不回原文档**。
目标是换成 DOCX 原生编辑器，让分页、页眉页脚、表格、图片按 OOXML 原样呈现，并在此基础上提供 PDF 导出。

选型：**SuperDoc 社区版**（`superdoc@2.26.0` + `@superdoc/react@2.21.0`，AGPL-3.0）。
理由：分页 / 页眉页脚 / 批注 / 修订都在社区版内，浏览器内编辑、零服务端内存；
Univer 的 DOCX 交换与打印属 `@univerjs-pro/*`，OnlyOffice 需要独立 ≥4GB 机器（本机余量不足）。

## 2. 数据模型

`documents` 表（生产库当前 **0 行**，无历史数据迁移）：

| 列 | 变更 |
|---|---|
| `content_html LONGTEXT` | **删除** |
| `content_docx LONGBLOB` | 新增，存 DOCX 原始字节 |
| `size_bytes` | 改存 DOCX 字节数 |

- 迁移落在 `DbSchema`，用 `hasColumn` 判断后执行，重启幂等。
- 部署前对 `documents` 表做 `mysqldump` 留底（共享生产库纪律）。
- `Document` 实体、`DocumentDao` 同步改为 `byte[]`；列表查询继续排除正文大字段。

## 3. 接口契约（前缀不变：`/api/docs`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/docs` | 列表（元数据，不含正文），分页与 `q` 不变 |
| GET | `/api/docs/{id}` | 元数据 + `docx_base64` |
| GET | `/api/docs/{id}/download` | 直出 `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| POST | `/api/docs` | `{title, docx_base64}` → `{ok, id}` |
| PUT | `/api/docs/{id}` | `{title, docx_base64}` → `{ok}` |
| DELETE | `/api/docs/{id}` | 不变 |
| ~~POST~~ | ~~`/api/docs/import`~~ | **删除**（POI 解析链路一并移除） |

校验：标题非空且 ≤200 字；Base64 可解码；DOCX ≤10MB；首 4 字节为 `PK\x03\x04`。
`size_bytes` 取解码后字节数。

## 4. 前端改造（notelab-b）

- 新增 `src/components/docx-editor.tsx`：`dynamic(..., { ssr: false })` 包 `@superdoc/react`，
  `forwardRef` 暴露 `exportDocx(): Promise<Blob>`。
- 编辑页 `/admin/documents/editor`：
  - 新建 → 加载 `public/templates/blank.docx`（空白 A4）；
  - 导入 → 本地文件直接进编辑器（不再请求服务端解析），保存时才入库；
  - 保存 → `export()` 取 Blob → Base64 → POST/PUT；
  - 导出 docx → 直接下载 `export()` 的 Blob；
  - 导出 PDF → `window.print()`，打印时隐藏后台外壳与工具栏，由浏览器「另存为 PDF」。
- 列表页补「下载」按钮，指向 `/api/docs/{id}/download`。
- 移除：`react-quill-new`、`docx`、`src/components/doc-editor.tsx`、`src/lib/export-docx.ts`。

### PDF 分两期
一期用浏览器打印（SuperDoc 本身按真实分页渲染，屏幕即打印结果）；二期再评估服务器 LibreOffice 一键下载。

## 5. 合规与风险

- SuperDoc 社区版为 AGPL-3.0：本站是个人内部管理后台、不对外分发，在 `notelab-b/README.md` 注明来源与许可证。
- SuperDoc 为 2.x，API 尚在演进；首次接入需实测 Turbopack 打包与中文分页。
- 产物体积明显增大（superdoc 未压缩约 13.6MB），只进入管理端页面，可接受。
- 版式度量依赖浏览器字体（与 WPS 本身跨设备差异同理），不作为缺陷处理。

## 6. 验收

- Java：服务器 `mvn package`；curl 走「新建 → 读取 → 下载」往返，校验 DOCX 魔数与大小。
- B 端：`tsc --noEmit`、`next build`；无头 Chrome 走「新建 → 输入中文 → 保存 → 重开内容仍在」，
  打开带表格/图片的真实 DOCX 核对版式，生成打印 PDF 核对页数与 A4 尺寸，控制台无报错。
- 回滚：Java 回退提交并恢复 `documents` 备份；B 端回退提交即可（两端口径必须一起回退）。

## 7. 实施记录（2026-10-26）

本地验收：`mvn -DskipTests package` 通过；B 端 `tsc --noEmit` + `next build` 通过；无头 Chrome 端到端 **32/32**
（新建→中文输入→保存→重开、导入表格/图片/两页 docx→保存→重开、列表下载链接、控制台无报错、PDF A4 且页数精确）。

浏览器打印踩到的两个坑（改打印样式前先看这里）：

1. **SuperDoc 每张纸自带 `break-after: page`**。直接打印会按「张数 + 1」出页（空白文档 2 页、两页文档 3 页）。
   正确写法：`.superdoc-page { break-after: auto }` + `.superdoc-page:not(:last-of-type) { break-after: page }`，
   非末页强制换页、末页自然结束，页数才与张数一致。
2. **Tailwind v4 的层叠顺序**：`!important` 工具类在 `@layer utilities`，无层级的 `!important` 优先级最低，
   压不住 `main` 的 padding 和 `layout` 的 `margin-left: 220px`；打印覆盖必须写进 `@layer base`
   （important 声明在层叠层里反向排序，base 的 important 才能赢 utilities）。否则打印根不会贴到页面原点。
