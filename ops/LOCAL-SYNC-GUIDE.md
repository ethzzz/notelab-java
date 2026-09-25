# 本地同步指引（B/C 拆分后，2026-08-25）

> **⛔ 已废弃（2026-09-25）：本文描述的「服务器 → 本地」反向同步已停用，不要按本文操作。**
> 现行流程是 **本地改 → commit/push → 服务器执行 `/root/notelab-java/ops/sync-deploy.sh <仓>` 从 git 同步**
> （见根 `AGENTS.md`「开发流程」）。不再使用 `docs\sync-from-server.ps1`，也不再 scp 覆盖本地目录。
> 保留本文仅为历史参考；其中「`/root/myapp` 在服务器上不是 git 仓库」这一事实仍然成立——它不在这套 git 同步范围内。

本地镜像（Windows）通过 `docs\sync-from-server.ps1` 从服务器 117.72.32.87 同步。
**该脚本当前只同步 `myapp` 与 `notelab-java` 两个目录**；B/C 拆分新增了
`/root/notelab-c` 与 `/root/notelab-b` 两个仓库（均为独立 git 仓库，各 1 个提交），需要补进同步范围。

## 服务器侧状态（同步前确认）

- /root/notelab-java：git 工作区状态以 `git status` 为准（阶段5 提交后应干净）。
- /root/myapp：**服务器上不是 git 仓库**（纯目录 + .gitignore），按原脚本方式整目录同步即可。
- /root/notelab-c、/root/notelab-b：git 仓库（各 1 提交），工作区干净；node_modules/.next 为构建产物，不需同步。

## 建议新增的同步命令（供代理执行，追加到 sync-from-server.ps1 或手工运行）

方案 A（推荐：服务端打包排除 node_modules/.next，scp 传输，本地解包）：

```powershell
# notelab-c
ssh root@117.72.32.87 "cd /root && tar czf /tmp/notelab-c.tgz --exclude=notelab-c/node_modules --exclude=notelab-c/.next notelab-c"
scp root@117.72.32.87:/tmp/notelab-c.tgz $env:TEMP\notelab-c.tgz
tar xzf $env:TEMP\notelab-c.tgz -C <本地镜像根目录>

# notelab-b
ssh root@117.72.32.87 "cd /root && tar czf /tmp/notelab-b.tgz --exclude=notelab-b/node_modules --exclude=notelab-b/.next notelab-b"
scp root@117.72.32.87:/tmp/notelab-b.tgz $env:TEMP\notelab-b.tgz
tar xzf $env:TEMP\notelab-b.tgz -C <本地镜像根目录>
```

方案 B（若脚本惯用 scp -r 直连镜像目录，可先排除再传；scp 本身不支持排除，
可先本地 robocopy 清理或接受体积——两个仓库含 node_modules 各约数百 MB，不建议直传）：

```powershell
# 仅供参考（不推荐，体积大）：
# scp -r root@117.72.32.87:/root/notelab-c <本地镜像根目录>\notelab-c
# scp -r root@117.72.32.87:/root/notelab-b <本地镜像根目录>\notelab-b
```

## 同步后本地构建（如需在本地运行）

```powershell
cd <本地镜像根目录>\notelab-c; npm install; npm run build   # :3010
cd <本地镜像根目录>\notelab-b; npm install; npm run build   # :3020（basePath=/admin）
```

注意：两个前端均依赖同源 /api（由 nginx 代理到 :8001）。本地直跑时需自行处理 /api 指向
（next.config 无 rewrites，可用本地反代或 hosts + 服务器 :80）。
