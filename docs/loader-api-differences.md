# SimpleP2P 跨加载器 / 跨版本接口差异

目标矩阵：

| 加载器 | MC 1.20.1 | MC 1.21.1 |
| --- | --- | --- |
| Forge | 已有（分支 `forge1.20.1`） | 不做（Forge 1.21.1 不存在，由 NeoForge 承接） |
| NeoForge | 新增 | 新增 |
| Fabric | 新增 | 新增 |

Java 版本要求：1.20.1 用 Java 17；**1.21.1 必须 Java 21**。

---

## 0. 本模组的加载器耦合现状

全部 39 个 Java 文件中，只有以下 9 个文件碰到 MC 或加载器 API：

| 文件 | 依赖 | 性质 |
| --- | --- | --- |
| `SimpleP2PMod.java` | Forge API + 原版 | 加载器入口，每个加载器各一份 |
| `client/ClientSetup.java` | Forge API + 原版 | 拆成「原版 UI 逻辑」+「加载器事件接线」 |
| `client/AddressModeToggle.java` | 原版 GUI | 全加载器共用 |
| `client/ToastHelper.java` | 原版 GUI | 全加载器共用 |
| `client/config/SimpleP2PConfigScreen.java` | 原版 GUI | 全加载器共用 |
| `client/config/NodePoolScreen.java` | 原版 GUI | 全加载器共用 |
| `mixin/ConnectScreenMixin.java` | 原版 + Mixin | 1.20.1 / 1.21.1 各一份 |
| `mixin/ServerPingerMixin.java` | 原版 + Mixin | 1.20.1 / 1.21.1 各一份 |
| `mixin/ServerLoginTimeoutMixin.java` | 原版 + Mixin | 两版可共用（`@Constant` 写法不依赖签名） |

其余 30 个文件（`util` / `signaling` / `proxy` / `p2p` / `ext` / `enums` / `config` / `command` / `AutoRoomOpener`）**完全不 import `net.minecraft` / `com.mojang` / `net.minecraftforge`**，可以作为纯 Java 代码被所有加载器、所有版本共用。

唯一需要为可移植性做的小改造：`AutoRoomOpener` 直接引用了 `SimpleP2PMod.registeredCommandsRef`，应改为引用一个加载器无关的持有类（`P2P`）。

---

## A. NeoForge 版本矩阵

| 项 | MC 1.20.1 | MC 1.21.1 |
| --- | --- | --- |
| Maven 坐标 | `net.neoforged:forge:1.20.1-47.1.x` | `net.neoforged:neoforge:21.1.x` |
| 包根 | **仍是 `net.minecraftforge.*`** | `net.neoforged.*`，本体 API 是 `net.neoforged.neoforge.*` |
| 现有 Forge 源码能否原样用 | **可以**（NeoForge 1.20.1 就是改了坐标的 Forge 1.20.1，包重命名发生在 1.20.2） | 不行，必须改包名与生命周期 API |
| mixin refmap | 需要（运行期仍是 SRG 名） | **不需要**（1.20.2+ 运行期改用 Mojang 官方名，1.20.4 起换用 Fabric Mixin 实现） |
| 元数据文件 | `META-INF/mods.toml` | `META-INF/neoforge.mods.toml`（1.20.5 起改名，`modLoader="javafml"` 仍必填） |
| 构建插件 | ForgeGradle 或 ModDevGradle | 推荐 ModDevGradle（`net.neoforged.moddev`），NeoGradle（`net.neoforged.gradle`）亦可 |

---

## B. NeoForge 1.21.1 对 Forge API 的对应物

| Forge 1.20.1 用法 | NeoForge 1.21.1 写法 | 验证状态 |
| --- | --- | --- |
| `net.minecraftforge.fml.common.Mod` | `net.neoforged.fml.common.Mod` | 已确认 |
| `FMLJavaModLoadingContext.get().getModEventBus()` | 构造函数注入 `net.neoforged.bus.api.IEventBus`（1.21 已移除 `FMLJavaModLoadingContext`） | 已确认 |
| `net.minecraftforge.common.MinecraftForge.EVENT_BUS` | `net.neoforged.neoforge.common.NeoForge.EVENT_BUS` | 已确认 |
| `net.minecraftforge.eventbus.api.SubscribeEvent` | `net.neoforged.bus.api.SubscribeEvent` | 已确认 |
| `net.minecraftforge.eventbus.api.IEventBus` | `net.neoforged.bus.api.IEventBus` | 已确认 |
| `FMLCommonSetupEvent` | `net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent`（同路径，仅换包根） | 已确认 |
| `FMLEnvironment.dist.isClient()` | `net.neoforged.fml.loading.FMLEnvironment.dist`；`Dist` 换为 `net.neoforged.api.distmarker.Dist`。也可直接在类上用 `@Mod(value=..., dist=Dist.CLIENT)` | 已确认 |
| `ConfigScreenHandler.ConfigScreenFactory` | `net.neoforged.neoforge.client.gui.IConfigScreenFactory`，在模组构造函数里 `container.registerExtensionPoint(IConfigScreenFactory.class, (mc, screen) -> new ...)`；方法是 `createScreen(Minecraft, Screen)` | 已确认 |
| `net.minecraftforge.client.event.ScreenEvent.Init.Post` | `net.neoforged.neoforge.client.event.ScreenEvent.Init.Post` | 已确认 |
| `net.minecraftforge.client.event.ScreenEvent.Render.Post` | `net.neoforged.neoforge.client.event.ScreenEvent.Render.Post`（仍带 `GuiGraphics` 参数） | 已确认 |
| `net.minecraftforge.event.TickEvent.ClientTickEvent` | 1.20.5 起拆分：`net.neoforged.neoforge.client.event.ClientTickEvent.Pre` / `.Post` | 已确认 |
| `net.minecraftforge.event.RegisterCommandsEvent` | `net.neoforged.neoforge.event.RegisterCommandsEvent` | 包路径由系统重命名推导，**构建时验证** |
| `net.minecraftforge.client.event.RegisterClientCommandsEvent` | `net.neoforged.neoforge.client.event.RegisterClientCommandsEvent` | 同上，**构建时验证** |
| `net.minecraftforge.event.server.ServerStartedEvent` | `net.neoforged.neoforge.event.server.ServerStartedEvent` | 已确认 |
| `net.minecraftforge.event.server.ServerStoppingEvent` | `net.neoforged.neoforge.event.server.ServerStoppingEvent` | 已确认 |

NeoForge 1.20.1 的结论：现有 `SimpleP2PMod.java` / `ClientSetup.java` 的 import 可以把 `net.minecraftforge` 直接换成 `net.neoforged`（NeoForge 1.20.1 的坐标是 `net.neoforged:forge`，但包名仍是 `net.minecraftforge`，这点容易记反）。**在 1.20.1 上，NeoForge 与 Forge 的源码差异仅是包根前缀，甚至可能是 0**——构建时确认。

---

## C. Fabric 对应物

### C.1 元数据与入口

| 项 | 写法 |
| --- | --- |
| 元数据 | `fabric.mod.json`（`id`/`version`/`name`/`environment`/`entrypoints`/`depends`/`mixins`） |
| 主入口 | `"main": ["com.simple_p2p.fabric.SimpleP2PModFabric"]`，实现 `net.fabricmc.api.ModInitializer` |
| 客户端入口 | `"client": ["com.simple_p2p.fabric.SimpleP2PModFabricClient"]`，实现 `net.fabricmc.api.ClientModInitializer` |
| mixin 配置 | `fabric.mod.json` 里加 `"mixins": ["simple_p2p.mixins.json"]`（纯客户端 mixin 可放 `"client"` 段，键名也叫 `mixins`） |
| 环境判定 | `net.fabricmc.api.EnvType` + `@Environment`；`FabricLoader.getInstance().getEnvironmentType()`；`FabricLoader.getInstance().isModLoaded(String)` |

### C.2 版本与构建

| 项 | MC 1.20.1 | MC 1.21.1 |
| --- | --- | --- |
| Fabric Loader | 0.15.x ~ 0.16.x | 0.16.x |
| Fabric API | `0.92.x+1.20.1` 段位 | `0.116.7+1.21.1`（已确认存在该版本） |
| Loom | `fabric-loom` 1.7.x 段位 | `fabric-loom` 1.9~1.10 段位 |
| 映射 | `loom.officialMojangMappings()` | 同左 |
| mixin refmap | 现代 Loom 默认不做 Mixin AP、**不需要 refmap**（构建期就地重映射） | 同左 |
| Java | 17 | 21 |

> 精确版本号以 <https://fabricmc.net/develop/> 为准，构建时锁定。**注意**：Loom 新版文档里插件 id 已改称 `fabric-loom-remap`。

> 用 `officialMojangMappings()` 是本次移植的关键决策：这样 30 个纯 Java 文件里的原版类名/方法名在 Forge、NeoForge、Fabric 三边保持一致，源码可以共用；若用 Yarn 映射就要三份源码。

### C.3 事件对应表

| Forge 用法 | Fabric API 对应 |
| --- | --- |
| `RegisterCommandsEvent`（服务端命令） | `net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT` |
| `RegisterClientCommandsEvent`（客户端命令） | `net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT` |
| `ServerStartedEvent` | `net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED` |
| `ServerStoppingEvent` | `net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING` |
| `TickEvent.ClientTickEvent` | `net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK` |
| `ScreenEvent.Init.Post` | `net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT` |
| `ScreenEvent.Render.Post` | `net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(Screen)`（同一包下另有 `AFTER_RENDER` 事件） |
| 取屏幕上已有按钮 | `net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(Screen)` |
| `ConfigScreenHandler`（mod 列表里的 Config 按钮） | ModMenu：`com.terraformersmc.modmenu.api.ModMenuApi`（实现 `getModConfigScreenFactory()`），依赖坐标 `com.terraformersmc:modmenu`，仓库 `https://maven.terraformersmc.com/` |

Fabric 侧 **没有** 等价于 Forge ConfigScreenHandler 的官方机制，配置界面入口只能靠 ModMenu 提供；没有装 ModMenu 时，本模组的配置界面仍可通过多人游戏界面的「设置」按钮进入（这条 UI 挂载逻辑用 `ScreenEvents.AFTER_INIT` 实现，不依赖 ModMenu）。

---

## D. 原版类 1.20.1 → 1.21.1 的签名差异

| 类 / 方法 | 1.20.1 | 1.21.1 | 对本模组的影响 | 验证状态 |
| --- | --- | --- | --- | --- |
| `Screen#init()` / `init(Minecraft,int,int)` | 可重写 `protected void init()` | 一致（`init(Minecraft,int,int)` 变 final） | 无 | 已确认（mappings.dev） |
| `Screen#render(GuiGraphics,int,int,float)` | — | 一致 | 无 | 已确认 |
| `Screen#children()` | — | 一致 | 无 | 已确认 |
| `AbstractWidget#setX/setY/setWidth/setHeight` | public | 未改名，仍存在（`resize` 是 `Screen` 的方法） | 无 | 已确认 |
| `AbstractWidget#visible/active` | 公有字段 | 一致 | 无 | 已确认 |
| `EditBox(Font,int,int,int,int,Component)`、`setMaxLength/setValue/setResponder/setHint` | — | 一致 | 无（仍要 `setMaxLength(256)` 绕开默认 32） | 已确认 |
| `Checkbox` 构造函数 | `public (int,int,int,int,Component,boolean)`，可匿名子类化 | `(int,int,int,Component,Font,boolean,OnValueChange)` **包内可见**；公开入口只有 `Checkbox.builder(Component, Font)` | **需改**：1.21.1 只能走 builder（`.pos()` / `.maxWidth()` / `.selected()` / `.onValueChange()` / `.build()`） | 已确认（javap 实测） |
| `Screen#renderBackground` | 只有 `(GuiGraphics)` | 只有 `(GuiGraphics,int,int,float)` | **需改**：抽 `renderBackdrop(g, mouseX, mouseY, partialTick)` 抽象方法，各版本自己接 | 已确认（javap 实测） |
| `Screen#mouseScrolled` | `(double,double,double)`（纵向是第 3 个参数） | `(double,double,double,double)`（纵向是第 4 个参数 `scrollY`） | **需改**：抽 `scrollBy(double)`，各版本子类把滚轮事件接进去 | 已确认（javap 实测） |
| `Button.builder` | — | 一致 | 无 | 已确认 |
| `GuiGraphics#drawString/fill` | — | 一致 | 无 | 已确认 |
| `SystemToast.multiline(Minecraft, SystemToastId, Component, Component)` | `SystemToast.SystemToastIds` | **枚举改名为 `SystemToast.SystemToastId`**，`PERIODIC_NOTIFICATION` 常量保留 | **需改** | 已确认（javap 实测） |
| `Component.literal` / `MutableComponent` / `Style` / `ChatFormatting` | — | 一致 | 无 | 已确认 |
| `ClickEvent` / `HoverEvent` 构造 | `new ClickEvent(ClickEvent.Action.OPEN_URL, String)` | 一致（拆成 OpenUrl 子类是 1.21.5 的事） | 无 | 已确认 |
| `ServerData` / `ServerAddress` | — | 一致 | 无 | 已确认 |
| `ServerStatusPinger#pingServer` | `(ServerData, Runnable)` | **`(ServerData, Runnable, Runnable)`** | **需改 mixin 签名** | 已确认 |
| `ConnectScreen#startConnecting` | `(Screen, Minecraft, ServerAddress, ServerData, boolean)` | **末位再加 `TransferState`** | **需改 mixin 签名** | 已确认 |
| `ServerLoginPacketListenerImpl` | 存在 | 仍存在，**未拆分**；登录超时常量是私有字段 | `@ModifyConstant(intValue=600)` 可能失效，需改为按字段名改写 | 部分确认 |
| `IntegratedServer#getPort()` | — | 一致 | 无 | 已确认 |
| `MinecraftServer#isDedicatedServer()` | — | 一致 | 无 | 已确认 |
| `Commands` / `CommandSourceStack` / `SharedSuggestionProvider` / `sendSuccess(Supplier<Component>, boolean)` | — | 一致 | 无 | 已确认 |
| `JoinMultiplayerScreen` / `DirectJoinServerScreen` / `EditServerScreen` | 均在 `net.minecraft.client.gui.screens(.multiplayer)` | 结构同 1.20.1 | 无 | 已确认 |
| 翻译键 `addServer.add` | 存在 | 存在 | 无 | 已确认 |
| 翻译键 `selectServer.select` | 存在 | **未确认** | 挂按钮时需容错 | 未确认 |

**结论**：1.20.1 → 1.21.1 的破坏性改动集中在三处原版签名（`pingServer`、`startConnecting`、`SystemToastId`）与加载器生命周期 API，GUI/命令/组件类基本一致。

---

## E. 本模组按此报告需要改的点（清单）

1. `ServerPingerMixin`：1.21.1 版把 `pingServer(ServerData, Runnable, CallbackInfo)` 改成 `(ServerData, Runnable, Runnable, CallbackInfo)`。
2. `ConnectScreenMixin`：1.21.1 版 `startConnecting` 注入方法末尾补 `TransferState` 参数，重定向调用也要传。
3. `ToastHelper`：1.21.1 用的 `SystemToast.SystemToastId` 常量名要按实际枚举核对。
4. `ServerLoginTimeoutMixin`：1.21.1 若 `@ModifyConstant(intValue=600)` 注入失败（`require=0` 会静默降级），改为按字段/常量名定位。
5. 加载器入口：Forge/NeoForge 用 `@Mod`，Fabric 用 `ModInitializer` + `ClientModInitializer` + ModMenu。
6. `AutoRoomOpener` 改为引用加载器无关的 `P2P` 持有类。
7. 元数据：`mods.toml`（Forge/NeoForge 1.20.1）、`neoforge.mods.toml`（NeoForge 1.21.1）、`fabric.mod.json`（Fabric）。

> 未确认项一律在构建/运行时实测后再定稿，不靠推测写死。

---

## F. 已联网核实的构建坐标（2026-10 查询）

| 用途 | 坐标 / 插件 | 核实结果 |
| --- | --- | --- |
| Forge 1.20.1 | `net.minecraftforge:forge:1.20.1-47.4.22` + `net.minecraftforge.gradle` 6.0.54 | 现有工程用 6.0.27；FG 最新为 6.0.54（changelog 提到适配到 Gradle 8.13） |
| NeoForge 1.20.1 | `net.neoforged:forge:1.20.1-47.1.106` | 已在镜像站确认该目录与 `-userdev.jar` 存在 |
| NeoForge 1.21.1 | `net.neoforged:neoforge:21.1.209` | 已确认存在，且带 `-moddev-config.json`（即支持 ModDevGradle） |
| ModDevGradle | 插件 `net.neoforged.moddev` version `1.0.14` | 已确认插件 marker 存在；ModDevGradle 仅支持 NeoForge 21.0.x+ |
| NeoGradle | 插件 `net.neoforged.gradle.userdev` | 未在插件门户/镜像找到 marker，仅在 maven.neoforged.net 发布 |
| Fabric Loader | `net.fabricmc:fabric-loader:0.19.5` | 1.20.1 与 1.21.1 的 meta 接口均返回该版本 |
| Fabric API 1.20.1 | `net.fabricmc.fabric-api:fabric-api:0.92.5+1.20.1` | 已确认 POM 存在 |
| Fabric API 1.21.1 | `net.fabricmc.fabric-api:fabric-api:0.116.7+1.21.1` | 已确认 POM 存在 |
| Loom 插件 id | `net.fabricmc.fabric-loom-remap`（混淆版本 1.21.11 及更早）；`fabric-loom` 为旧 id，仅作兼容保留 | 官方文档已确认；`fabric-loom-remap` 从 1.14.0 起才有，最新 release 1.18.3 |
| 映射 | `loom.officialMojangMappings()` | 关键决策：让三边原版类名一致，源码可共用 |

### 已知冲突与对策

- 多子项目需要统一升级 Wrapper：最终定为 **Gradle 8.11.1**（腾讯镜像分发）。原因是 Loom 1.9.2 强制要求 Gradle ≥ 8.11（实测用 8.10.2 报 `requires at least Gradle 8.11`），而 ModDevGradle 1.0.14 只要求 ≥ 8.8，8.11.1 同时满足两者。
- Forge 侧 ForgeGradle 保持 **6.0.27 不变**（实测在 Gradle 8.11.1 上仍正常构建），未升 6.0.54——避免动已经可用的基线。
- `plugins {}` 解析 NeoForge/Loom 插件时，`pluginManagement.repositories` 加入了镜像与 `https://maven.fabricmc.net/`（它们不在 Gradle Plugin Portal 上）。
- NeoForge 1.20.1 与 Forge 1.20.1 包名同为 `net.minecraftforge.*`，两者源码**完全共用**（`shared/forge1.20.1/`），仅依赖坐标不同——已实测构建通过。

---

## G. 网络实测：仓库可达性与镜像（2026-10-17 本机实测）

**重要**：`maven.neoforged.net` 在本机**完全不可达**（TCP 443 连接超时 21s+，curl/Java 均失败）。F 节中“加入 `https://maven.neoforged.net/releases`”的写法**不可行**，必须改用镜像。

| 仓库 / 主机 | 实测结果 | 处置 |
| --- | --- | --- |
| `maven.minecraftforge.net` | 可达 | Forge 1.20.1 直接用（基线已构建通过） |
| `maven.fabricmc.net` | **可达**（curl 与 Gradle 均 200） | Fabric 直接使用，无需镜像 |
| `maven.neoforged.net` | **不可达**（连接超时） | 必须用镜像 |
| `plugins.gradle.org/m2` | 不稳定（时通时不通） | 用阿里云 gradle-plugin 镜像兜底 |
| `services.gradle.org` | 可达 | Wrapper 可用；另有 `mirrors.cloud.tencent.com/gradle/` |
| `libraries.minecraft.net` | 可达 | MC 库 |
| `piston-meta.mojang.com` | 可达 | MC 版本清单 |

> 注意：PowerShell 的 `Invoke-WebRequest` 在本机会出现假性超时（受系统代理设置影响），判断可达性请用 `curl.exe`。

### NeoForge 镜像（实测可用）

镜像根：`https://mirror.sjtu.edu.cn/bmclapi/`（备选：`https://mirror4.nyist.edu.cn/bmclapi/`、`https://mirrors.qlu.edu.cn/bmclapi/`）

已实测存在且内容新鲜的路径：

- `net/neoforged/neoforge/21.1.209/neoforge-21.1.209.pom`
- `net/neoforged/forge/1.20.1-47.1.106/{.pom, -universal.jar, -userdev.jar, -installer.jar, -sources.jar}`
- `net/neoforged/moddev-gradle/`
- `net/neoforged/neoform/`

**该镜像同时含有 `net/fabricmc/`，但内容陈旧**（`fabric-loader` 只到 0.10.x，且版本目录里只有 jar、没有 pom/module）。**Fabric 绝不要走这个镜像。**

### Gradle 解析实测（探针工程，Gradle 8.1.1）

| 依赖 | 结果 |
| --- | --- |
| `net.fabricmc:fabric-loader:0.16.14` | 成功（直连） |
| `net.fabricmc.fabric-api:fabric-api:0.92.5+1.20.1` | 成功（含全部子模块 + 传递依赖） |
| `net.fabricmc.fabric-api:fabric-api:0.116.7+1.21.1` | 成功 |
| `net.fabricmc:intermediary:1.20.1` | 成功 |
| `net.neoforged:neoforge:21.1.209` | 成功 → `neoforge-21.1.209-universal.jar`（走镜像） |
| `net.neoforged:moddev-gradle:1.0.14` | 成功（走镜像）；但其传递依赖 `gradle.plugin.org.jetbrains.gradle.plugin.idea-ext:gradle-idea-ext:1.1.8` 需要插件门户，用阿里云 gradle-plugin 镜像兜底 |
| `net.neoforged:forge:1.20.1-47.1.106` | POM 可解析但**不产出普通 jar**（打包类型非普通 jar）；要拿 jar 需加 classifier `:universal`，或交给构建插件内部处理 |

### 由此确定的构建策略

- `settings.gradle.kts` 的 `pluginManagement.repositories` 用镜像替代 neoforged：
  `maven("https://mirror.sjtu.edu.cn/bmclapi/") { content { includeGroupByRegex("net\\.neoforged.*") } }`
  并保留 `maven("https://maven.fabricmc.net/")`、`maven("https://maven.aliyun.com/repository/gradle-plugin")`、`gradlePluginPortal()`。
- **NeoForge 1.20.1 首选方案**：直接复用现有 ForgeGradle 6.x 工程，只把 `minecraft(...)` 依赖坐标换成 `net.neoforged:forge:1.20.1-47.1.106`（仓库指向镜像），源码可 0 改动——因为 NeoForge 1.20.1 的包名仍是 `net.minecraftforge.*`。ModDevGradle 对 1.20.1 的兼容性未确认，先用 ForgeGradle 路线，失败再换。
- **NeoForge 1.21.1**：`net.neoforged.moddev` 1.0.14 + 镜像仓库，需要 Gradle ≥ 8.8。
- **Fabric 1.20.1 / 1.21.1**：Loom + `maven.fabricmc.net`（直连）+ `officialMojangMappings()`。

### 镜像使用的两个坑（实测踩到，务必照抄）

1. **镜像选 `nyist`，不要选 `sjtu`**：`mirror.sjtu.edu.cn` 对已镜像的文件会返回 307 重定向到带签名的 S3 预签名地址，而该签名是按 `GET` 计算的；Gradle 会先发 **HEAD** 做存在性检查，于是收到 `403 Forbidden`（报 `Could not HEAD ... Received status code 403`）。`mirror4.nyist.edu.cn` / `mirrors.qlu.edu.cn` 直接回源文件，HEAD 返回 200，可用。
2. **`net.neoforged.fancymodloader:*` 在镜像里只有 jar、没有 pom**（BMCLAPI 只镜像启动器会下载的构件，而启动器不下载 pom）。Gradle 找不到 pom 就无法解析该模块。对策是给这一个 group 单开一个“只按构件名解析”的仓库视图：

```kotlin
maven("https://mirror4.nyist.edu.cn/bmclapi/") {
    name = "NeoForgedMirrorArtifacts"
    content { includeGroup("net.neoforged.fancymodloader") }
    metadataSources { artifact() }   // 不查 pom、不解析传递依赖，直接找 xxx-<ver>.jar
}
```

依赖的 7 个构件（`loader` / `earlydisplay` / `events` / `core` / `language-java` / `language-lowcode` / `language-minecraft`）都由 userdev 配置直接声明，因此跳过传递依赖解析不影响编译。

---

## H. 实现定稿（构建实测通过）

### H.1 目录结构（单仓库多子项目）

共用源码目录（不作为独立工程，各模块用 `srcDirs` 引入）：

| 目录 | 内容 | 谁用 |
| --- | --- | --- |
| `shared/core` | 纯 Java：信令 / P2P 隧道 / 外部客户端管理 / 配置 / `P2P` 持有类 / `P2PCommandTree` | 全部 5 个模块 |
| `shared/client` | 只依赖原版类的客户端逻辑：`ClientUiHooks` / `ServerListUIHelper` / 两个配置界面 / `AddressModeToggle` | 全部 5 个模块 |
| `shared/forge1.20.1` | Forge/NeoForge 1.20.1 共用的 `SimpleP2PMod` + `ClientSetup`（包名同为 `net.minecraftforge.*`） | forge-1.20.1、neoforge-1.20.1 |
| `shared/fabric` | Fabric 两个版本共用的 `SimpleP2PModFabric` + `SimpleP2PModFabricClient` | fabric-1.20.1、fabric-1.21.1 |
| `shared/mc1.20.1` | MC 1.20.1 版特有代码：3 个 Mixin + `ToastHelper` | forge-1.20.1、neoforge-1.20.1、fabric-1.20.1 |
| `shared/mc1.21.1` | MC 1.21.1 版特有代码：3 个 Mixin + `ToastHelper` | neoforge-1.21.1、fabric-1.21.1 |

5 个加载器模块：`forge-1.20.1` / `neoforge-1.20.1` / `neoforge-1.21.1` / `fabric-1.20.1` / `fabric-1.21.1`，各自只放元数据资源（+ 1.21.1 NeoForge 自己的入口类）与构建脚本。

### H.2 版本特有代码只有两类

共用源码在两个 MC 版本之间**唯一的差异**是：

1. 三个 Mixin 的目标签名（见 D 节）；
2. `ToastHelper` 里的枚举名 `SystemToast.SystemToastIds`（1.20.1）→ `SystemToast.SystemToastId`（1.21.1）。

其余（`ClientUiHooks` 用的 `Minecraft#getSingleplayerServer`、`IntegratedServer#getPort`、`Screen#children`、`Button.builder`、`Component.translatable`，以及全部命令 / 组件 / 配置屏幕代码）在 1.20.1 与 1.21.1 之间**完全一致**。

### H.3 1.21.1 精确签名（从 Mojang 官方映射 `client-1.21.1.txt` 逐条核对，非推测）

| 目标 | 1.21.1 实际签名 |
| --- | --- |
| `ConnectScreen#startConnecting` | `(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)`；`TransferState` = `net.minecraft.client.multiplayer.TransferState` |
| `ServerStatusPinger#pingServer` | `(ServerData, Runnable, Runnable)` |
| `SystemToast` 枚举 | `SystemToast$SystemToastId`（`PERIODIC_NOTIFICATION` 常量保留） |
| `Checkbox` | 唯一构造 `Checkbox(int,int,int,Component,Font,boolean,Checkbox$OnValueChange)` 是**包内可见**（外部无法继承，`new Checkbox(...) { }` 会报“不是公有的”）；公开入口只有 `Checkbox.builder(Component, Font)` |
| `Screen#renderBackground` | `(GuiGraphics, int mouseX, int mouseY, float partialTick)`（1.20.1 只有 1 参版） |
| `Screen#mouseScrolled` | `(double mouseX, double mouseY, double scrollX, double scrollY)` |
| `ServerLoginPacketListenerImpl` 登录超时 | 私有字段 `MAX_TICKS_BEFORE_LOGIN`，`tick` 里以字面量 600 出现 → `@ModifyConstant(intValue=600)` 写法仍可用 |

### H.4 NeoForge 1.21.1 精确类路径（直接从 `neoforge-21.1.209-universal.jar` 内翻出，非推测）

| 用途 | 实际类名 |
| --- | --- |
| mod 入口注解 | `net.neoforged.fml.common.Mod`；mod 总线改为构造参数注入 `net.neoforged.bus.api.IEventBus` |
| 通用事件总线 | `net.neoforged.neoforge.common.NeoForge.EVENT_BUS` |
| 配置界面扩展点 | `net.neoforged.neoforge.client.gui.IConfigScreenFactory`；方法是 **`createScreen(ModContainer, Screen)`**（注意不是 `(Minecraft, Screen)`），在 mod 构造期 `modContainer.registerExtensionPoint(...)` |
| 界面初始化事件 | `net.neoforged.neoforge.client.event.ScreenEvent$Init$Post`；挂控件用 `addListener(GuiEventListener)` |
| 界面渲染事件 | `net.neoforged.neoforge.client.event.ScreenEvent$Render$Post` |
| 客户端 tick | `net.neoforged.neoforge.client.event.ClientTickEvent$Post`（1.20.5 起由单一事件拆成 Pre/Post） |
| 客户端命令 | `net.neoforged.neoforge.client.event.RegisterClientCommandsEvent` |
| 服务端命令 | `net.neoforged.neoforge.event.RegisterCommandsEvent` |
| 服务器生命周期 | `net.neoforged.neoforge.event.server.{ServerStartedEvent, ServerStoppingEvent}` |
| 运行端判定 | `net.neoforged.fml.loading.FMLEnvironment.dist` |

NeoForge 1.21.1 的运行期**不需要 mixin refmap**（1.20.2+ 运行期改用 Mojang 官方名），因此 1.21.1 的 `simple_p2p.mixins.json` 里没有 `refmap` 字段。

### H.5 Fabric 侧决策

- 用 `officialMojangMappings()`，因此共用源码里的原版类名与 Forge/NeoForge 完全一致。
- **没有引入 ModMenu**：Fabric 没有等价于 `ConfigScreenHandler` 的官方机制，但本模组本来就在「多人游戏」界面挂了「设置」按钮，配置界面不依赖 ModMenu 也能进。少一个依赖 = 少一层兼容风险。
- Fabric API 事件映射：`CommandRegistrationCallback` / `ClientCommandRegistrationCallback`（v2）、`ServerLifecycleEvents`、`ClientTickEvents.END_CLIENT_TICK`、`ScreenEvents.AFTER_INIT` + `Screens.getButtons`。
- **`ScreenEvents` 没有全局 `AFTER_RENDER`**（1.20.1 的 2.0.9 与 1.21.1 的 2.0.25 都只有 `BEFORE_INIT` / `AFTER_INIT` 两个全局事件，已翻 fabric-api 缓存 jar 核实）。刷新模式按钮的做法是：在 `AFTER_INIT` 里挂完按钮后，用 `ScreenEvents.afterRender(screen)` 给该界面单独注册渲染回调。
- **`ClientCommandRegistrationCallback` 的泛型不协变**：它要求 `CommandDispatcher<FabricClientCommandSource>`，而 `FabricClientCommandSource extends CommandSourceStack`，分派器泛型不能直接转。做法是加一个只做强制转型的桥接方法：

```java
@SuppressWarnings({"unchecked", "rawtypes"})
private static CommandDispatcher<CommandSourceStack> asSourceStackDispatcher(
        CommandDispatcher<FabricClientCommandSource> dispatcher) {
    return (CommandDispatcher<CommandSourceStack>) (CommandDispatcher) dispatcher;
}
```

  这样 `P2PCommandTree.build(true)` 这一份命令树能被两边共用。

---

## I. 实测构建结论（2026-10-17，5 个模块全部通过）

### I.1 产物

`./gradlew buildAll` → `BUILD SUCCESSFUL`，5 个子项目各自产出 jar：

| 子项目 | 编译用 Java | 产物 |
| --- | --- | --- |
| `forge-1.20.1` | 17 | `SimpleP2P-forge-1.20.1-1.1.0.jar` |
| `neoforge-1.20.1` | 17 | `SimpleP2P-neoforge-1.20.1-1.1.0.jar` |
| `fabric-1.20.1` | 17 | `SimpleP2P-fabric-1.20.1-1.1.0.jar` |
| `neoforge-1.21.1` | 21 | `SimpleP2P-neoforge-1.21.1-1.1.0.jar` |
| `fabric-1.21.1` | 21 | `SimpleP2P-fabric-1.21.1-1.1.0.jar` |

### I.2 守护进程 JVM 必须统一到 21

`gradle/gradle-daemon-jvm.properties` 写 `toolchainVersion=21`，把守护进程固定到 JDK 21。

原因：**Loom 的 Java 版本校验看的是运行 Gradle 的那个 JVM**（`JavaVersion.current()`），不是编译用的 toolchain。MC 1.21.1 要求 Java 21，所以只要守护进程还是 17，`fabric-1.21.1` 会在配置阶段直接失败：`Minecraft 1.21.1 requires Java 21 but Gradle is using 17`，且 Loom 没有开关能绕过（已 javap 核对 `LoomGradleExtension` 无相关方法，`MinecraftProvider` 用的是 `JavaVersion.current().isCompatibleWith(...)`）。各模块的源码级别仍由自己的 toolchain 决定，所以 1.20.1 的模块在 JDK 21 守护进程下用 JDK 17 编译，不受影响（已实测三个 1.20.1 模块在 JDK 21 守护进程下仍 `BUILD SUCCESSFUL`）。

### I.3 NeoForge 1.21.1 的真正阻塞点：不是镜像缺构件，而是 ModDevGradle 硬编码官方源

`neoforge-1.21.1` 曾两次挂在 `createMinecraftArtifacts` 之前（CPU 归零、日志停在 `createArtifactManifest` 之后）。jstack 拿到的阻塞栈是：

```
Execution worker ... sun.nio.ch.Net.pollConnect / timedFinishConnect
  <- org.gradle.internal.resource.transport.http.HttpClientHelper...（HTTP HEAD）
  <- MavenResolver.doResolveComponentMetaData -> resolveStaticDependency -> downloadStaticResource
```

即**卡在 Gradle 对某个仓库做 HEAD 元数据检查**；`Get-NetTCPConnection -OwningProcess <daemon>` 显示一条 `SynSent` 状态的连接指向黑洞 IP。原因有两条：

- `maven.neoforged.net` 在本机 **DNS 被污染**：同一域名多次解析分别返回 `199.59.149.136`、`199.16.158.12`、`23.225.141.210` 等互不相干、连不上的第三方 IP（target 与 hosts 文件里都没有这些记录），443 直连必然超时。
- ModDevGradle 的 `net.neoforged.moddevgradle.internal.RepositoriesPlugin` 把 `https://maven.neoforged.net/releases/` 与 `https://maven.neoforged.net/mojang-meta/` 用 `sortFirst` 排到仓库列表**最前面**；同时 NFRT 的下载地址也是这个硬编码常量。所以“换镜像仓库”绕不开它——Gradle 仍会先去连这个不可达主机。`NeoFormRuntime` DSL 只有 `version/useEclipseCompiler/enableCache/verbose/analyzeCacheMisses`，**没有仓库配置项**（已 javap 核对）。

### I.4 解法：本机代理 + nonProxyHosts

`gradle.properties` 里加：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7899
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7899
systemProp.http.nonProxyHosts=repo.maven.apache.org|repo1.maven.org|*.maven.apache.org|localhost|127.0.0.1
systemProp.https.nonProxyHosts=repo.maven.apache.org|repo1.maven.org|*.maven.apache.org|localhost|127.0.0.1
```

两个要点：

1. ModDevGradle 会把 Gradle JVM 上 `http.` / `https.` / `java.net.` / `javax.net.ssl.` / `jdk.tls.` 前缀的系统属性**转发给 NFRT 子进程**（见 `net.neoforged.moddevgradle.internal.utils.NetworkSettingPassthrough` 的常量表），所以同一份设置对 Gradle 解析与 NFRT 下载同时生效，不用给 NFRT 单独配。
2. 挂上代理后 **Maven Central 会 403**：`Could not GET https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl/3.3.2/lwjgl-3.3.2.pom. Received status code 403 from server: Forbidden`（代理出口 IP 被 Central 拒）。因此必须用 `nonProxyHosts` 把 Maven Central 排除掉走直连。实测对照（`curl -x` 与直连各打一次）：

   | 主机 | 直连 | 走代理 |
   | --- | --- | --- |
   | `repo.maven.apache.org` | 200 | **403** |
   | `maven.neoforged.net` | 超时（000） | **200** |
   | `libraries.minecraft.net` | 404 | 404 |
   | `piston-meta.mojang.com` | 200 | 200 |
   | `maven.fabricmc.net` | 200 | 200 |
   | `mirror4.nyist.edu.cn` | 200 | 200 |
   | `repo.spongepowered.org` | 200 | 200 |

> 代理属于本机环境。换机器或代理端口不同时改端口两行；本机没开代理时把这段注释掉即可（前提是那台机器能直连 `maven.neoforged.net`）。

### I.5 镜像补充实测

- `https://mirror.sjtu.edu.cn/bmclapi/` **有** `net/neoforged/neoform/1.21.1-20240808.144430/`（含 `neoform-1.21.1-20240808.144430.zip`、pom、module）；`https://mirror4.nyist.edu.cn/bmclapi/` **没有** `net/neoforged/neoform/`（`maven-metadata.xml` 与目录都 404）。
- 但这条**不能替代 I.4**：NFRT 走的是硬编码的 `maven.neoforged.net`、不经过 Gradle 仓库，所以镜像里有 neoform 也不影响它。镜像只对**由 Gradle 解析**的依赖（`net.neoforged:neoforge`、`net.neoforged:neoform`、`net.neoforged.fancymodloader:*` 等）有用；工程里仍用 `nyist`，因为它对 HEAD 返回 200，而 `sjtu` 对已镜像文件会 307 到 S3 预签名地址、HEAD 拿到 403（见 G 节“两个坑”）。
