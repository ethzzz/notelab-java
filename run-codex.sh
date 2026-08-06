#!/bin/bash
cd /root/notelab-java
if [ -z "$QWEN_API_KEY" ]; then
  export QWEN_API_KEY=$(grep '^QWEN_API_KEY=' /etc/environment | cut -d= -f2-)
fi
echo "=== codex start at $(date) ==="
codex exec --skip-git-repo-check -s danger-full-access - < /root/notelab-java/TASK-PROMPT.md 2>&1 | tee /root/notelab-java/codex-run.log
code=${PIPESTATUS[0]}
echo "=== codex exit code: $code at $(date) ===" | tee -a /root/notelab-java/codex-run.log