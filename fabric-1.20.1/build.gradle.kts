// Fabric 1.20.1 模块 — Java 17, Fabric Loom, Mojang 官方映射(official)
plugins {
    id("fabric-loom") version "1.9.2"
    `java-library`
}

val mcVersion = "1.20.1"
val loaderVersion = "0.16.14"
val fabricApiVersion = "0.92.12+1.20.1"

version = rootProject.property("mod_version").toString()
group = "com.simple_p2p"
base {
    archivesName.set("SimpleP2P-fabric-$mcVersion")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

// ========== 共用源码目录 ==========
sourceSets {
    main {
        java.srcDirs(
            rootProject.file("shared/core/src/main/java"),
            rootProject.file("shared/client/src/main/java"),
            // Fabric 两个版本共用同一份加载器入口（Fabric API 的这几个事件在 1.20.1/1.21.1 签名一致）
            rootProject.file("shared/fabric/src/main/java"),
            rootProject.file("shared/mc1.20.1/src/main/java")
        )
    }
}

repositories {
    mavenCentral()
    // Fabric 的镜像内容陈旧（缺 pom），必须直连官方源
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }
    maven("https://libraries.minecraft.net/") { name = "MinecraftLibraries" }
    maven("https://maven.aliyun.com/repository/public")
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    // 用 official 映射是关键决策：这样 30 多个纯 Java 文件里的原版类名/方法名在
    // Forge / NeoForge / Fabric 三边保持一致，源码可以共用（若用 Yarn 就要三份源码）
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-rawtypes")
    options.compilerArgs.add("-Xlint:-unchecked")
    options.compilerArgs.add("-Xlint:-deprecation")
    options.compilerArgs.add("-Xlint:-removal")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf(
        "version" to project.version,
        "mc_version" to mcVersion,
        "mod_id" to rootProject.property("mod_id"),
        "mod_name" to rootProject.property("mod_name"),
        "mod_authors" to rootProject.property("mod_authors"),
        "mod_description" to rootProject.property("mod_description")
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "pack.mcmeta")) {
        expand(props)
    }
}

tasks.withType<Test>().configureEach {
    enabled = false
}
