// 标准 Forge 1.20.1 MDK 构建脚本
// Java 17, ForgeGradle 6.x, Parchment(可选) Mojang Mappings
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

version = property("mod_version").toString()
group = "com.simple_p2p"
base {
    archivesName.set("SimpleP2P")
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

// ========== Minecraft / Forge 依赖 ==========
minecraft {
    // mappings channel: "official"(Mojang映射, 1.14.4+), 或 "parchment"(需指定version)
    mappings("official", project.property("minecraft_version").toString())

    // runClient / runServer / runData / runGameTestServer 均由ForgeGradle按默认生成
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
    // 让额外依赖也使用国内镜像(如果将来加的话); Forge 依赖自己会从它指定的位置下载
    maven("https://maven.aliyun.com/repository/public")
    mavenLocal()
    mavenCentral()
    maven("https://libraries.minecraft.net/") { name = "MinecraftLibraries" }
    maven("https://maven.minecraftforge.net/") { name = "Forge" }
    // Mixin 注解处理器来源
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "SpongePowered" }
}

dependencies {
    minecraft(group = "net.minecraftforge", name = "forge",
            version = "${project.property("minecraft_version")}-${project.property("forge_version")}")

    // Mixin 注解处理器：Forge 1.20.1 运行时使用 SRG 名称，必须靠它生成 refmap 做名称映射，
    // 否则 @Mixin/@Inject 中的方法名字符串在运行时解析不到，注入静默失败（Mixin 完全不生效）。
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

// MixinGradle：注册 refmap 输出（AP 会把 official 方法名映射为 SRG 名）。
// 缺少这一步 AP 会报 "Unable to locate obfuscation mapping for @Inject target ..."。
extensions.getByName("mixin").withGroovyBuilder {
    "add"(sourceSets.getByName("main"), "simple_p2p.refmap.json")
}

// ========== 编译与资源处理 ==========
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.isDeprecation = true
    // 关闭 noise：-rawtypes / -unchecked / -deprecation(因为 Xlint:all 默认包含) / -removal
    // 本轮核心修复引入的 FMLJavaModLoadingContext.get() 仅 @Deprecated(forRemoval=true) 但仍稳定可用。
    options.compilerArgs.add("-Xlint:-rawtypes")
    options.compilerArgs.add("-Xlint:-unchecked")
    options.compilerArgs.add("-Xlint:-deprecation")
    options.compilerArgs.add("-Xlint:-removal")
}

// 把 gradle.properties 里的 mod 信息替换进 mods.toml/pack.mcmeta
tasks.processResources {
    // 中文元数据必须用 UTF-8 展开，否则 expand() 会用系统默认字符集(如 GBK)导致乱码
    filteringCharset = "UTF-8"
    val props = mapOf(
        "version" to project.version,
        "mc_version" to project.property("minecraft_version"),
        "loader_version_range" to project.property("loader_version_range"),
        "minecraft_version_range" to project.property("minecraft_version_range"),
        "mod_id" to project.property("mod_id"),
        "mod_name" to project.property("mod_name"),
        "mod_authors" to project.property("mod_authors"),
        "mod_description" to project.property("mod_description")
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(props)
    }
}

tasks.jar {
    // Forge 1.13+ 只认 META-INF/mods.toml；mcmod.info(旧版元数据) 与 fabric.mod.json 属于
    // build-mod.ps1 旧版/其他加载器打包流程，混入会误导 Forge 判定为"旧版本 mod"，故排除。
    exclude("mcmod.info", "fabric.mod.json")

    // ForgeGradle 6.x 会为 userdev 项目生成名为 "reobfJar" 的任务，但它的创建时机在
    // afterEvaluate/约定映射阶段；为避免配置期找不到任务，这里用 lazy finalize 方式。
    manifest {
        attributes(
            mapOf(
                "Specification-Title" to project.property("mod_id"),
                "Specification-Vendor" to project.property("mod_authors"),
                "Specification-Version" to "1",
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to project.property("mod_authors"),
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

// P2P mod 本身不写单元测试，跳过
tasks.withType<Test>().configureEach {
    enabled = false
}
