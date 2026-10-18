// NeoForge 1.20.1 模块
// NeoForge 1.20.1 是 Forge 1.20.1 的延续（包名仍是 net.minecraftforge.*，仅 Maven 坐标改成 net.neoforged），
// 因此源码与 forge-1.20.1 完全共用（shared/forge1.20.1），本模块只换依赖坐标与仓库。
// Java 17, ForgeGradle 6.x, Mojang Mappings(official)
buildscript {
    repositories {
        maven("https://repo.spongepowered.org/repository/maven-public/")
        mavenCentral()
    }
    dependencies {
        classpath("org.spongepowered:mixingradle:0.7-SNAPSHOT")
    }
}

plugins {
    idea
    eclipse
    `java-library`
    id("net.minecraftforge.gradle") version "6.0.27"
}

apply(plugin = "org.spongepowered.mixin")

version = rootProject.property("mod_version").toString()
group = "com.simple_p2p"
base {
    archivesName.set("SimpleP2P-neoforge-1.20.1")
}

java {
    val j17 = JavaVersion.VERSION_17
    sourceCompatibility = j17
    targetCompatibility = j17
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
            // NeoForge 1.20.1 与 Forge 1.20.1 的包名/API 一致，直接共用同一份加载器入口
            rootProject.file("shared/forge1.20.1/src/main/java"),
            rootProject.file("shared/mc1.20.1/src/main/java")
        )
    }
}

// ========== Minecraft / NeoForge(1.20.1) 依赖 ==========
val minecraftVersion = "1.20.1"
val neoforgeVersion = "47.1.106"

minecraft {
    mappings("official", minecraftVersion)

    runs {
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            arg("-mixin.config=simple_p2p.mixins.json")
            mods {
                create("simplep2p") {
                    source(sourceSets.main.get())
                }
            }
        }
        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            mods {
                create("simplep2p") {
                    source(sourceSets.main.get())
                }
            }
        }
    }
}

repositories {
    // NeoForge 官方源 maven.neoforged.net 在本机不可达，走 BMCLAPI 镜像。
    // 镜像选择：sjtu 会把文件 307 重定向到带签名的 S3 地址，而 Gradle 的存在性检查用 HEAD，
    // 对那种签名 URL 会 403；nyist / qlu 直接回源文件（HEAD 200），因此这里用 nyist。
    // 注意：镜像里 net.neoforged.fancymodloader:* 只有 jar 没有 pom，
    // 所以对这一个 group 单独开一个“只按构件名解析”的仓库视图（不查 pom、不解析传递依赖）。
    maven("https://mirror4.nyist.edu.cn/bmclapi/") {
        name = "NeoForgedMirrorArtifacts"
        content { includeGroup("net.neoforged.fancymodloader") }
        metadataSources { artifact() }
    }
    maven("https://mirror4.nyist.edu.cn/bmclapi/") { name = "NeoForgedMirror" }
    maven("https://maven.aliyun.com/repository/public")
    mavenLocal()
    mavenCentral()
    maven("https://libraries.minecraft.net/") { name = "MinecraftLibraries" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "SpongePowered" }
}

dependencies {
    minecraft(group = "net.neoforged", name = "forge", version = "$minecraftVersion-$neoforgeVersion")

    // Mixin 注解处理器（1.20.1 运行期仍是 SRG 名称，必须生成 refmap）
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

extensions.getByName("mixin").withGroovyBuilder {
    "add"(sourceSets.getByName("main"), "simple_p2p.refmap.json")
}

// ========== 编译与资源处理 ==========
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.isDeprecation = true
    options.compilerArgs.add("-Xlint:-rawtypes")
    options.compilerArgs.add("-Xlint:-unchecked")
    options.compilerArgs.add("-Xlint:-deprecation")
    options.compilerArgs.add("-Xlint:-removal")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf(
        "version" to project.version,
        "mc_version" to minecraftVersion,
        "loader_version_range" to "[47,)",
        "minecraft_version_range" to "[1.20.1,1.21)",
        "mod_id" to rootProject.property("mod_id"),
        "mod_name" to rootProject.property("mod_name"),
        "mod_authors" to rootProject.property("mod_authors"),
        "mod_description" to rootProject.property("mod_description")
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(props)
    }
}

tasks.jar {
    exclude("mcmod.info", "fabric.mod.json")

    manifest {
        attributes(
            mapOf(
                "Specification-Title" to rootProject.property("mod_id"),
                "Specification-Vendor" to rootProject.property("mod_authors"),
                "Specification-Version" to "1",
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to rootProject.property("mod_authors"),
                "Implementation-Timestamp" to System.currentTimeMillis().toString(),
                "FMLModType" to "MOD",
                "MixinConfigs" to "simple_p2p.mixins.json"
            )
        )
    }
}

afterEvaluate {
    tasks.findByName("reobfJar")?.let { reobfJar ->
        tasks.jar { finalizedBy(reobfJar) }
        reobfJar.doLast {
            val original = reobfJar.outputs.files.singleOrNull()
                ?: return@doLast
            val targetDir = project.layout.buildDirectory.dir("libs").get().asFile
            targetDir.mkdirs()
            val target = File(targetDir, original.name)
            if (original.absoluteFile != target.absoluteFile) {
                original.copyTo(target, overwrite = true)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    enabled = false
}
