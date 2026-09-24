pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net/")
    }
}

// 不要启用 dependencyResolutionManagement 的仓库限制：ForgeGradle 会在 apply 阶段动态注入
// Minecraft 原版 jar 的“捆绑仓库”，限制项目级仓库会让它找不到 net.minecraft:client。
rootProject.name = "SimpleP2P"


