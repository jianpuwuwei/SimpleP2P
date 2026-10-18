pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net/")
        // NeoForge 官方源 maven.neoforged.net 在本机不可达，改用镜像
        maven("https://mirror.sjtu.edu.cn/bmclapi/")
        // Fabric 的镜像内容陈旧（缺 pom），必须直连官方源
        maven("https://maven.fabricmc.net/")
    }
}

// 不要启用 dependencyResolutionManagement 的仓库限制：ForgeGradle 会在 apply 阶段动态注入
// Minecraft 原版 jar 的“捆绑仓库”，限制项目级仓库会让它找不到 net.minecraft:client。
rootProject.name = "SimpleP2P"

// MC 1.20.1 的 3 个加载器模块；shared 目录只是共用的源码目录（通过各模块的 srcDirs 引入），不是独立工程
include("forge-1.20.1")
include("neoforge-1.20.1")
include("fabric-1.20.1")
