// NeoForge 1.21.1 模块 — Java 21, ModDevGradle
// 注意：Forge 没有 1.21.1 版本，NeoForge 承担 1.21.1 的 Forge 生态位。
// 1.20.2 起 NeoForge 的包名改成 net.neoforged.*，运行期也不再需要 mixin refmap。
plugins {
    id("net.neoforged.moddev") version "1.0.14"
    `java-library`
}

val mcVersion = "1.21.1"
val neoVersion = "21.1.209"

version = rootProject.property("mod_version").toString()
group = "com.simple_p2p"
base {
    archivesName.set("SimpleP2P-neoforge-$mcVersion")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

// ========== 共用源码目录 ==========
sourceSets {
    main {
        java.srcDirs(
            rootProject.file("shared/core/src/main/java"),
            rootProject.file("shared/client/src/main/java"),
            rootProject.file("shared/mc1.21.1/src/main/java")
        )
    }
}

repositories {
    // 官方源 maven.neoforged.net 在本机不可达，走 BMCLAPI 镜像（nyist 直接回源文件，可被 HEAD 探测）。
    // 镜像里 net.neoforged.fancymodloader:* 只有 jar 没有 pom，故对该 group 单开“只按构件名解析”的视图。
    maven("https://mirror4.nyist.edu.cn/bmclapi/") {
        name = "NeoForgedMirrorArtifacts"
        content { includeGroup("net.neoforged.fancymodloader") }
        metadataSources { artifact() }
    }
    maven("https://mirror4.nyist.edu.cn/bmclapi/") { name = "NeoForgedMirror" }
    mavenCentral()
}

neoForge {
    version = neoVersion

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }

    mods {
        register("simplep2p") {
            sourceSet(sourceSets.main.get())
        }
    }
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
        "loader_version_range" to "[21.1,)",
        "minecraft_version_range" to "[1.21.1,1.22)",
        "mod_id" to rootProject.property("mod_id"),
        "mod_name" to rootProject.property("mod_name"),
        "mod_authors" to rootProject.property("mod_authors"),
        "mod_description" to rootProject.property("mod_description")
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta")) {
        expand(props)
    }
}

tasks.withType<Test>().configureEach {
    enabled = false
}
