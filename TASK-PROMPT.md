你是 notelab 的 Java 重写代理。先完整阅读当前目录的 AGENTS.md（任务简报：背景、接口范围、技术约定、阶段计划、纪律），然后执行：

1. 按 AGENTS.md 的阶段计划从阶段1开始，本次会话能完成几个阶段就完成几个阶段。
2. 只修改 /root/notelab-java 内的文件；/root/notelab 与 /root/myapp 只读参考（可 curl 8000 端口对比真实响应）。
3. 每完成一个阶段：自验（mvn 构建 + pm2 重启 notelab-java + curl 对比 Python 版），并更新 README.md 与 PROGRESS.md，git commit。
4. 无法完成全部阶段时，在阶段边界停止：确保代码可构建可启动，并在 PROGRESS.md 写清已完成内容、验证结果、下一步。
5. 会话结束前输出总结：完成了哪些阶段、各阶段验证结果、剩余工作。