#!/bin/bash
# 用法: run-codex-task.sh [prompt文件] [日志文件]，默认 TASK-PROMPT.md / codex-run.log
cd /root/notelab-java
PROMPT_FILE=${1:-TASK-PROMPT.md}
LOG_FILE=${2:-codex-run.log}
if [ -z "$QWEN_API_KEY" ]; then
  export QWEN_API_KEY=$(grep '^QWEN_API_KEY=' /etc/environment | cut -d= -f2-)
fi
echo "=== codex start at $(date) prompt=$PROMPT_FILE log=$LOG_FILE ==="
codex exec --skip-git-repo-check -s danger-full-access - < "/root/notelab-java/$PROMPT_FILE" 2>&1 | tee "/root/notelab-java/$LOG_FILE"
code=${PIPESTATUS[0]}
echo "=== codex exit code: $code at $(date) ===" | tee -a "/root/notelab-java/$LOG_FILE"