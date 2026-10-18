// 根工程：只做聚合，不含源码。
// 各加载器 / 版本模块见 forge-1.20.1、neoforge-1.20.1、neoforge-1.21.1、fabric-1.20.1、fabric-1.21.1。
// 共用源码目录（由各模块的 srcDirs 引入）：
//   shared/core      —— 纯 Java：信令 / P2P 隧道 / 外部客户端管理 / 配置 / 命令逻辑
//   shared/client    —— 只依赖原版类的客户端界面与连接逻辑
//   shared/mc1.20.1  —— MC 1.20.1 的 Mixin
//   shared/mc1.21.1  —— MC 1.21.1 的 Mixin

plugins {
    base
}

tasks.register("buildAll") {
    group = "build"
    description = "构建全部加载器 / 版本模块"
    dependsOn(
        ":forge-1.20.1:build",
        ":neoforge-1.20.1:build",
        ":neoforge-1.21.1:build",
        ":fabric-1.20.1:build",
        ":fabric-1.21.1:build"
    )
}
