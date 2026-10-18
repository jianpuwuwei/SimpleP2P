// Forge 1.20.1 模块（原来仓库的基线，保持可构建）
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

// MixinGradle：负责为 Mixin 注解处理器生成 refmap（official 名 → SRG 名映射）。
// Forge 1.20.1 运行时使用 SRG 名称，没有 refmap 时 @Inject 的目标方法名会在运行期匹配不到而静默失效。
apply(plugin = "org.spongepowered.mixin")

version = rootProject.property("mod_version").toString()
group = "com.simple_p2p"
base {
    archivesName.set("SimpleP2P-forge-1.20.1")
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
            rootProject.file("shared/forge1.20.1/src/main/java"),
            rootProject.file("shared/mc1.20.1/src/main/java")
        )
    }
}

// ========== Minecraft / Forge 依赖 ==========
val minecraftVersion = "1.20.1"
val forgeVersion = "47.4.22"

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
    maven("https://maven.aliyun.com/repository/public")
    mavenLocal()
    mavenCentral()
    maven("https://libraries.minecraft.net/") { name = "MinecraftLibraries" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "SpongePowered" }
}

dependencies {
    minecraft(group = "net.minecraftforge", name = "forge", version = "$minecraftVersion-$forgeVersion")

    // Mixin 注解处理器：Forge 1.20.1 运行时使用 SRG 名称，必须靠它生成 refmap 做名称映射，
    // 否则 @Mixin/@Inject 中的方法名字符串在运行时解析不到，注入静默失败（Mixin 完全不生效）。
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

// MixinGradle：注册 refmap 输出（AP 会把 official 方法名映射为 SRG 名）。
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
    // 中文元数据必须用 UTF-8 展开，否则 expand() 会用系统默认字符集(如 GBK)导致乱码
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

// 把 reobfJar 绑定到 jar 的 finalizedBy（等任务创建完成后），并把最终产物放入 build/libs/
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
