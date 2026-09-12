pluginManagement {
    repositories {
        // 国内镜像优先，避免 JDK 自带证书链与 Gradle/Forge Maven 的握手错误
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        // 兜底
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net/")
    }
}

// 重要：**不要**启用 dependencyResolutionManagement.repositoriesMode=FAIL / PREFER_SETTINGS。
// ForgeGradle 会在 apply 阶段动态把 Minecraft 原版 client / server jar 通过“捆绑仓库”注入；
// 任何限制项目级仓库的做法都会导致它找不到 net.minecraft:client 这个“只存在于 ForgGradle 内部生成树里”
// 的坐标（不在任何公开 Maven，所以阿里云 / MavenCentral 都不可能有）。
rootProject.name = "AllInOneP2P"


