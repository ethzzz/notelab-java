#!/bin/bash
# 串联 P4 -> P5：仅当 P4 成功(exit code 0)才跑 P5
cd /root/notelab-java
echo "=== CHAIN start P4 at $(date) ==="
bash run-codex-task.sh TASK-PROMPT-BC-P4.md codex-bc-p4.log
if grep -q 'codex exit code: 0' codex-bc-p4.log; then
  echo "=== P4 OK, start P5 at $(date) ==="
  bash run-codex-task.sh TASK-PROMPT-BC-P5.md codex-bc-p5.log
  echo "=== P5 done at $(date) ==="
else
  echo "=== P4 FAILED, skip P5 at $(date) ==="
fi
echo "=== CHAIN DONE at $(date) ==="
