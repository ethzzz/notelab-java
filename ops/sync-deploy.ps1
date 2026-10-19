<#
.SYNOPSIS
  同步并重建某个仓：在本机跑**服务器上的** ops/sync-deploy.sh
  （同步 git → 构建 → 重启 pm2 → HTTP 探活）。

.DESCRIPTION
  为什么经 ssh 跑：服务器才是构建环境 —— 本机没有完整的 mvn，也不该用本机 npm 构建生产；
  「本地构建 + 上传产物」正是被淘汰的那条路（CI 复制产物曾把线上回退到更早的提交）。
  所以这套脚本只做一件事：把参数原样交给服务器脚本，并把退出码透传回来。

  服务器脚本的行为（细节见 ops/sync-deploy.sh 头部注释）：
    · 工作区脏 → 直接拒绝（说明有人在服务器上手改过代码，先查清来源）
    · HEAD 没变 → 打印「已是最新」，不构建
    · 只改了 *.md / ops/* / docs/* → 默认跳过构建与重启，要强制重建加 --build
    · 构建失败 → **不重启**，老进程继续服务（所以"没变化"时先看 /root/ops/sync-deploy-<仓>.log）

  日常不必直接调这个脚本，用同目录的快捷入口（.cmd 垫片，绕开 Windows 执行策略）：
    sync-notelab-java / sync-notelab-b / sync-notelab-c / sync-ai-lab / sync-all
  在当前目录直接敲 `.\sync-notelab-b` 即可（PowerShell 会自动补扩展名）。

.PARAMETER Repo
  要同步的仓：notelab-java / notelab-b / notelab-c / ai-lab / home / all
  （home 已并入 notelab-c，保留只为历史兼容；日常不会用到。）

  其余参数**原样**透传给服务器脚本：--build / --no-build / --dry-run / -h。
  刻意不用 [CmdletBinding()]：那样 `--dry-run` 这种前导横线的透传参数会被当成未知参数名而报错，
  放进自动变量 $args 才能真正原样转发（已实测）。

.EXAMPLE
  .\sync-deploy.ps1 -Repo notelab-b
  同步并（有变更时）重建 B 端。

.EXAMPLE
  .\sync-deploy.ps1 -Repo all --dry-run
  预演五个仓的同步：会真的 fetch/pull 判定，但不构建、不重启。

.NOTES
  嫌每次要写 -ExecutionPolicy Bypass 就用 .cmd 垫片（见上）。
  这些是**本机工具**，服务器上不会用到（随仓库同步过去只是顺带）。
#>
# ⚠️ 刻意只用普通 param（不加 [CmdletBinding()] / [Parameter()]）：
#    一旦加了，脚本就变成"高级脚本"，未知的横线参数会直接报错，
#    --dry-run 这类透传开关就到不了 $args 里（实测踩过）。
param(
    [string]$Repo
)

$ErrorActionPreference = 'Stop'

$Known = @('notelab-java', 'notelab-b', 'notelab-c', 'ai-lab', 'home', 'all')
$SshHost = 'myapp'
$RemoteScript = '/root/Notelab/notelab-java/ops/sync-deploy.sh'

if (-not $Repo -or $args -contains '-h' -or $args -contains '--help') {
    Write-Host "用法：sync-deploy.ps1 -Repo <仓> [--build | --no-build | --dry-run | -h]"
    Write-Host "  仓 ∈ $($Known -join ' / ')"
    Write-Host ""
    Write-Host "快捷入口（推荐）：.\sync-notelab-java / .\sync-notelab-b / .\sync-notelab-c / .\sync-ai-lab / .\sync-all"
    Write-Host "注意：只有文档变更（*.md / ops/* / docs/*）时默认不重建，要强制加 --build。"
    exit 2
}

# 本地先校验仓名：拼错了没必要白跑一趟 ssh
if ($Known -notcontains $Repo) {
    Write-Host "[x] 不认识的仓：$Repo" -ForegroundColor Red
    Write-Host "    支持：$($Known -join ' / ')" -ForegroundColor Red
    exit 2
}

$remoteCmd = "$RemoteScript $Repo"
if ($args -and $args.Count -gt 0) {
    $remoteCmd = $remoteCmd + ' ' + ($args -join ' ')
}

Write-Host "> ssh $SshHost `"$remoteCmd`"" -ForegroundColor DarkGray
& ssh -n $SshHost $remoteCmd

$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "[x] 同步失败（ssh exit $code）。先看 /root/ops/sync-deploy-$Repo.log 的尾部。" -ForegroundColor Red
    exit $code
}
Write-Host "[ok] 完成：$Repo" -ForegroundColor Green
