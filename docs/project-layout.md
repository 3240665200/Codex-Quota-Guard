# 项目目录与缓存清理

```text
pingche/
├── apps/                 后台、桌面前端和 Rust 统计服务源码
├── deploy/               Docker 部署配置
├── scripts/windows/      启动、打包和清理脚本
├── docs/                 API、架构、产品和版本说明
├── demos/                示例和界面模板
├── dist/                 可交付产物
│   ├── desktop/0.4.3/    最新桌面安装包
│   └── backend/0.4.2/    最新后台包
├── .cache/               可重新生成的编译缓存
├── .local/               本机数据，不进入 Git
│   ├── backups/          数据及清理时保留的统计备份
│   ├── logs/             启动日志
│   ├── reports/          校验、清理结果
│   └── runtime/          本机运行信息
└── 一键*.cmd             常用操作入口
```

已经把最新发布包复制到分类目录并核对 SHA-256；启动、打包脚本也使用新位置。缓存清理脚本执行后会去掉 `dist` 中剩余的旧诊断文件和旧目录入口。根目录的界面模板已归到 `demos/templates/warm-dashboard-template`。

## 执行清理

关闭正在进行的构建和开发服务器，双击项目根目录的 **一键清理-项目缓存.cmd**。一般不需要管理员权限；占用或无权访问的目录会跳过，不会停止已安装的统计服务。

也可以在项目根目录打开 PowerShell 执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\clean-project-cache.ps1 -IncludeLegacyCaches -OrganizeDist
```

先预览、完全不删除文件：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\clean-project-cache.ps1 -IncludeLegacyCaches -OrganizeDist -WhatIf
```

本机 D 盘当前约有 50 GB 旧编译和打包缓存。脚本只选择已核对的项目缓存目录；Git 源码、`node_modules`、最新桌面及后台包、`D:\BuildCache\pingche\private`、原始源码备份和安装在 Program Files 的统计服务均保留。旧测试目录里发现的凭证、统计队列、数据库快照会先复制到 `.local/backups`，校验成功后再清理所在缓存。

清理结果写入 `.local/reports/cache-cleanup-result.json`。下次构建会重新生成缓存，首次重新构建会较慢；不会影响已安装的桌面客户端和后台服务器。

桌面打包默认缓存路径为 `.cache/rust`，也可通过 `package-client.ps1 -TargetDirectory D:\BuildCache\pingche\cache\rust` 指定 D 盘缓存位置。可交付安装包统一收集到 `dist/desktop/<版本>/`。
