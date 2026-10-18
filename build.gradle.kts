// 根工程：只做聚合，不含源码。
// 本分支只包含 MC 1.20.1 的模块：forge-1.20.1、neoforge-1.20.1、fabric-1.20.1。
// 共用源码目录（由各模块的 srcDirs 引入）：
//   shared/core      —— 纯 Java：信令 / P2P 隧道 / 外部客户端管理 / 配置 / 命令逻辑
//   shared/client    —— 只依赖原版类的客户端界面与连接逻辑
//   shared/forge1.20.1 —— Forge / NeoForge 1.20.1 共用的加载器入口
//   shared/fabric    —— Fabric 两个版本共用的加载器入口
//   shared/mc1.20.1  —— MC 1.20.1 的 Mixin

plugins {
    base
}

// 各模块都把 shared/core 的资源目录并入 main：内置的 aria2c 等原生二进制放在这里随 JAR 打包。
// shared/* 只是共用源码目录（不是子工程），所以由根工程统一注入。
subprojects {
    plugins.withId("java") {
        extensions.configure<SourceSetContainer>("sourceSets") {
            named("main") {
                resources.srcDir(rootProject.file("shared/core/src/main/resources"))
            }
        }
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "构建全部加载器 / 版本模块"
    dependsOn(
        ":forge-1.20.1:build",
        ":neoforge-1.20.1:build",
        ":fabric-1.20.1:build"
    )
}
