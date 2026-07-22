# 日志目录

App 上传的运行日志会保存在此目录（`alarm_checkin.log`）。

- 上传日志的 commit 带 `[skip ci]`，且 workflow 配置 `paths-ignore: logs/**`，不会触发 CI
- 每次上传覆盖同名文件，保留最新日志
