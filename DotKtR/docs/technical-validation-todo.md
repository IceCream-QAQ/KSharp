# DotKtR 技术验证清单

> 插件工程补充：当前已有 `0.1.0-dev` 独立插件、MSBuild 导入和标准 Gradle ZIP 构建；使用条件与边界以 [插件 README](../README.md) 为准。下文保留原型阶段的验证范围和历史证据。

验证 `DLL → KLIB → Kotlin 语义分析 → ReSharper 导航` 能否在 Rider 中形成可维护的闭环。按当前验证顺序，先尝试 Rider 进程内的 Kotlin PSI/K2 与 ReSharper 后端桥接；遇到证据明确的阻碍后再评估独立 JVM 分析服务。

运行结果持续记录在 [原生 Rider 技术验证记录](technical-validation-results.md)。清单中的整项只有在其全部条件满足后才勾选，局部通过的证据见该记录。

当前整体能力与优先级见 [技术能力与产品化评估报告](technical-capability-report.md)。这里保留覆盖完整项目生命周期和语言语义的长期清单，未勾选整项不等于其中所有子能力都未实现。

首轮原生 PSI、K2/KLIB、直接调用真实补全服务、两个 C# 重载的实际导航、未保存编辑和冷启动复验已通过。首次 GUI 试用缺少错误标记与补全的问题已修复；后续真实窗口验证了诊断与恢复、手动及自动补全、候选插入、参数提示和悬停。以下清单仍保留完整导入、更多语言规则、通用符号映射和产品集成的后续要求。

当前优先项：

- [x] 将原型迁为独立 `0.1.0-dev` 插件：标准 Gradle ZIP、设置、MSBuild 导入、作用域隔离、源码增删刷新，并从安装包完成回归；固定兼容宿主仍是发布限制。
- [x] 在真实编辑器中输入 `box.` 后出现 `Read`、`Value`、`Describe` 补全，并能正确插入。
- [x] 输入未闭合字符串时出现语法错误，闭合后消失。
- [x] 给只接收 `Int` 的重载传入 `42L` 时出现类型/重载错误，修复后消失。
- [x] 单独验证参数提示和悬停的实际弹窗及签名内容。
- [x] 将跨语言导航接入 Cmd＋左键 / Go to Declaration；验证两个 C# 重载和五个 ASP.NET Core 外部源码目标，保留原生 Kotlin 导航。
- [x] 让顶层普通 `main` 使用 Rider 原生 `.NET Project` 配置；验证全新沙箱 Build、实际 HTTP 响应和 Stop。
- [x] 验证 Kotlin 编译失败时不启动旧产物；修复并修改 Kotlin 源码后 Run 会构建并运行新内容。
- [ ] 扩展投影来源映射、复杂签名、多 TFM、快照失效与离线外部导航验证。
- [ ] 扩展多 TFM / 多入口运行，单独验证 Kotlin 断点、PDB 映射、单步和 Hot Reload。

首轮完成标准：在 Rider 中打开一个引用 C# 类库的 `.ktproj`，正确分析 Kotlin 中的泛型与重载调用，提供基础成员补全，并跳转到实际选中的 C# 声明。首轮允许 C# 符号在构建后刷新。

## 已知基线

以下来自 2026 年 10 月 13 日的源码与安装包检查，不能替代运行验证。

| 项目 | 基线 |
| --- | --- |
| dotkt 源码 | `400ab50472c0839a9b7de6a94a18db4bc9ad4bb5` |
| dotkt Kotlin 内核 | `2.4.10` |
| 模板 Rider 目标 | `2025.3` |
| 模板 ReSharper SDK | `2025.3.0.1` |
| 本机 Rider | `2026.2.3.1`，build `262.10968.170` |
| 本机 IDEA | `2026.2.3`，内置 Kotlin 编译器 `2.4.20-ij262-52` |
| Kotlin 插件依赖 | 本机 IDEA 的 Kotlin 插件依赖 Java 插件；本机 Rider 未安装这两项语言插件 |
| dotkt 编辑器接口 | `DotKtResolveKlibReferences`、`DotKtResolvedKlibReference`、`DotKtStdlib`、`DotKtKotlinVersion` |

Kotlin PSI 提供语法结构；类型推断、引用和重载解析需要 Analysis API/FIR。不能把创建 `KtFile` 或成功读取 KLIB 视为语义分析验证通过。

## P0 固定环境与测试样例

- [ ] 固定首个 Rider 验证版本，同步前端平台依赖、后端 SDK 和 RD 生成器的兼容组合。
- [ ] 分开记录插件自身的 Kotlin 编译版本与 dotkt 分析内核版本，避免混用 IDE 内置编译器和 dotkt 内核。
- [ ] 将内层 `DotKtR/build.gradle.kts` 作为 Gradle 项目导入，确认 SDK 依赖可索引、模板可构建、开发沙箱可启动。
- [ ] 定义一个最小 RD 请求，验证 Rider 前端与 ReSharper 后端通信及 solution 关闭时的生命周期清理。
- [ ] 建立最小混合 solution：C# 类库、引用它的 `.ktproj`、可导航的外部程序集。
- [x] 为样例加入构造函数、实例方法、同名重载、泛型类型与方法、属性、扩展方法、继承静态成员。
- [x] 用固定版本 dotkt 构建样例，保存预期调用目标与预期诊断，作为编辑器分析的对照。

验收：能够在开发沙箱打开样例，RD 往返成功，样例通过真实 dotkt 编译；记录所有工具链版本和启动命令。

## P1 验证项目模型与 KLIB 输入

- [ ] 检查 Rider 对 `.ktproj` 的识别、solution 加载、项目引用、构建入口及文件归属；列出需要补充的项目注册或导入适配。
- [x] 调用公开的 `DotKtResolveKlibReferences` target，获取 KLIB 绝对路径以及 `SourceAssembly`、`TargetFramework`、`RuntimeIdentifier`。ASP.NET Core 单 TFM 样例已验证，见对应验证记录。
- [ ] 将 `DotKtStdlib` 与返回的引用 KLIB 组成分析依赖；读取 `DotKtKotlinVersion` 选择分析内核。
- [ ] 获取实际求值后的 `KotlinCompile`、common source 集合及相关编译选项，形成一个可序列化的项目快照。
- [ ] 检查首次 restore、缺少 C# 项目输出、引用增删和引用项目重新构建时的行为。
- [ ] 明确调用 target 是否会触发引用项目构建；验证设计时刷新路径，避免编辑事件隐式触发完整编译链。
- [ ] 在受支持的多 TFM 样例上验证结果分组，确认不同 TFM/RID 的同名程序集不会混入同一个分析模块。
- [ ] 保留 MSBuild 的引用选择结果；确认实现没有扫描相邻目录重建依赖集合，也没有绑定 `_DotKt` 私有变量或内部工具目录布局。

验收：导出可重复加载的项目快照；其源码、stdlib 和引用集合与对应 dotkt 构建一致。依赖缺失有明确状态，引用移除后不会继续解析旧符号。

## P2 验证 Kotlin 分析内核

- [ ] 在 Rider 中加载原生 Kotlin PSI/K2，接入真实 dotkt stdlib KLIB 和 P1 项目快照；记录宿主分析内核与 dotkt 内核的版本差异。
- [ ] 按 dotkt 的 metadata/common 前端构建模块与依赖，验证 KLIB metadata 版本、平台设置和标准库选择。
- [ ] 若进程内接入受阻，评估独立 JVM 与 Analysis API Standalone 对 KLIB、模块模型以及 dotkt FIR 定制的接入能力，记录阻碍和切换依据。
- [ ] 若 Standalone 接入也受阻，用 dotkt 现有前端建立最小诊断与引用解析入口，明确它与可交互语言服务之间仍缺少的能力。
- [ ] 复用或抽出 dotkt 的语言版本配置、`CompanionBlocksAndExtensions`、默认导入、静态成员 scope、可见性、重载选择及诊断策略。
- [ ] 比较编辑器和编译器对重载、泛型、可空性、扩展方法、继承静态成员、受保护静态成员和 value class 的判断。
- [ ] 验证未完成的表达式、语法错误和未保存 Kotlin 文档不会让分析进程退出，已有代码仍能返回有意义的结果。
- [ ] 实现最小成员补全实验，确认返回结果来自当前接收者类型和作用域；分别记录成员补全与自动导入补全的实现成本。
- [ ] 检查现有前端的全局状态和 reset 行为，验证不同项目/TFM 的会话不会污染彼此。
- [ ] 对 Rider 同进程方案做有界的依赖验证：列出承载 Kotlin PSI/K2 所需模块、类加载冲突和服务注册要求，再决定是否值得继续移植。

验收：同一样例在编辑器分析与 dotkt 编译中得到一致的调用目标和关键诊断；能够处理未完成代码。仅能解析 PSI 或枚举 KLIB 声明不算通过。

决策：优先验证 Rider 原生 PSI/K2。若依赖、服务冲突或语义适配成本使其不可维护，记录具体失败证据后切换独立分析进程。普通 Kotlin LSP 只有在通过上述 CLR 语义样例后，才能作为候选内核。

## P3 验证符号身份与跨语言导航

已完成默认入口的局部验收：通过现有 `ClrExternal` / `ClrTypeAlias` 和 `SourceAssembly` 定位正式声明；ReSharper 查找与导航使用消费项目的模块范围及显式编译上下文。五个 ASP.NET Core 类型/方法/属性实际打开原始 C# 源码，两个项目内重载也通过 Cmd＋左键；以下未勾选项保留其完整覆盖要求。证据见 [外部库](evidence/dotnet-navigation-web.json) 和 [项目内重载](evidence/dotnet-navigation-mixed.json)。

- [ ] 在 `dll2klib` 投影阶段为样例输出最小声明来源映射，验证 Kotlin 投影声明到 CLR 原始成员的关联。
- [ ] 区分语义声明身份与 DLL 快照定位信息：记录程序集、CLR 类型、成员种类、完整签名，以及具体快照的 MVID/token。
- [ ] 将项目、TFM/RID、文档版本和依赖快照版本纳入请求上下文，拒绝使用已经失效的解析结果导航。
- [ ] 检查现有 `ClrExternal`、`KotlinDeclarationIdentity` 等信息能覆盖哪些声明；对缺少来源的投影明确补充方案。
- [ ] 验证静态调用与扩展调用映射到同一物理方法，以及属性、访问器、事件等一对多或多对一关系。
- [ ] 验证泛型元数、嵌套类型、重载、类型转发和 reference assembly 与实现程序集不同的情况。
- [ ] 通过 RD 将 CLR 标识传给后端，在正确的 ReSharper 模块中解析到 `IDeclaredElement`，调用现有导航服务。
- [ ] 验证 solution 内源码、具有多个声明位置的 partial 类型，以及仅有 DLL 的外部类型导航。
- [ ] 重新构建 C# 类库后再次导航，确认不把旧 MVID/token 或旧行号当作永久身份。
- [x] 将 JVM 与 .NET 之间的数据限制为 DTO；不跨进程传递 PSI、FIR、KaSymbol 或 ReSharper 对象。

验收：Kotlin 调用准确跳转到选中的 C# 重载。通过修改签名、重新构建和切换 TFM 检查映射更新；失效或歧义情况下返回明确结果，不按名称猜测目标。

## P4 验证编辑体验与资源开销

- [ ] 原生路线验证实际 Kotlin contributor、诊断和悬停服务；若切换独立分析进程，再接入 Rider LSP。CLR 导航通过 RD 桥接。
- [ ] 明确 `.kt` 文件类型、词法高亮及 LSP 功能的归属，验证不会与其他文件类型或语言服务重复接管。
- [ ] 同步打开、修改、关闭文档及版本号，验证 UTF-16 偏移、中文、代理对字符和 CRLF 下的诊断/导航范围。
- [ ] 对快速连续编辑实施合并、取消和旧结果丢弃；检查耗时分析不阻塞 Rider UI 线程。
- [x] 复用进程内分析会话，或使用常驻独立分析进程；确认补全请求不会启动新 JVM、运行完整构建或重建全部 KLIB。
- [ ] 验证依赖投影更新后会话失效、文件句柄释放及快照切换，避免混读新旧 KLIB。
- [ ] 测量冷启动、首次导入、暖态补全/悬停/跳转、增量诊断和工作集内存，记录样例规模及 p50/p95。
- [ ] 在首轮测量后设定交互延迟与内存预算，增加一个引用较多的实际项目验证预算是否可达。
- [ ] 验证 solution 关闭、分析进程崩溃与重启，确认请求能够取消且不会残留子进程。

验收：连续编辑时无明显 UI 阻塞，无旧诊断覆盖新文档；两次相同实验可复现性能数据，资源可随 solution 生命周期释放。

## P5 后续能力的可行性实验

以下工作在 P1 至 P4 的最小闭环通过后开展。

- [ ] C# 未编译修改：评估从 ReSharper 源码符号生成统一声明模型，再复用 `dll2klib` 投影逻辑的成本。
- [ ] 源码投影一致性：对泛型约束、可空性、可见性、扩展方法和生成代码，对比源码投影与真实编译 DLL 的投影。
- [ ] 源码覆盖策略：验证新的源码投影能够替换对应程序集的旧投影，避免重复声明；明确源码错误时的降级状态。
- [ ] C# 到 Kotlin 源码导航：验证编译产物到 Kotlin 声明的来源信息，列出需要编译器补充的映射；不假定 PDB 已包含所有声明定位信息。
- [ ] 跨语言查找引用：验证 Kotlin 引用结果如何接入 ReSharper 的查找流程，并保持快照一致性。
- [ ] 跨语言重命名：验证两侧引用集合、冲突检测、修改预览和编辑事务，不以导航成功推断重命名可用。

验收：分别给出继续投入、暂缓或受接口限制的结论及证据，不将这些能力混入首轮完成标准。

## 验证结果记录

每项实验保留版本、命令、输入样例、实际输出、预期差异及日志位置。通过条件满足后再勾选对应条目；失败项保留最小复现。

| 实验 | 状态 | 证据位置 | 结论与下一步 |
| --- | --- | --- | --- |
| P0 环境与样例 | 沙箱及样例通过，正式 Gradle 集成待做 | `spikes/native-psi` | 固定 Rider 262；模板发布构建仍独立于实验 |
| P1 项目与 KLIB | 公共 target 和 Web 三文件样例通过，完整自动导入与刷新待做 | `docs/evidence/aspnet-controller-inputs.json` | Web 项目为 307 个引用 KLIB 加 CLR stdlib；多 TFM 和依赖更新待验收 |
| P2 分析内核 | 混合样例和 Web / Controller 样例通过，完整语义待扩展 | `docs/evidence/aspnet-controller-rider.json` | 原生 K2、跨文件解析可工作；内部 API、版本和会话隔离仍需收敛 |
| P3 跨语言导航 | 默认 Cmd＋左键、两个项目内重载与五个外部源码目标通过 | `docs/evidence/dotnet-navigation-web.json`、`dotnet-navigation-mixed.json` | 完整来源映射、复杂签名、多 TFM、快照失效与离线降级待做 |
| P4 编辑与性能 | GUI 补全、诊断、参数提示、悬停、未保存编辑和冷启动通过 | `docs/evidence/native-gui-complete.json` | 性能数据仅为小样例单次计时；大项目、并发取消、生命周期与多 TFM 待验收 |
| P5 后续能力 | 待执行 | 待填写 | 分别评估增量投入 |

## 依据

- [dotkt MSBuild 集成契约](https://github.com/tmyt/dotkt/blob/400ab50472c0839a9b7de6a94a18db4bc9ad4bb5/packaging/DotKt.README.md#msbuild-integration-contract)
- [dotkt 应用前端](https://github.com/tmyt/dotkt/blob/400ab50472c0839a9b7de6a94a18db4bc9ad4bb5/toolchain/kotc/src/main/kotlin/kotc/pipeline/ClrAppFrontendPipelinePhase.kt)
- [dotkt 语言配置与流水线](https://github.com/tmyt/dotkt/blob/400ab50472c0839a9b7de6a94a18db4bc9ad4bb5/toolchain/kotc/src/main/kotlin/kotc/pipeline/ClrCliPipeline.kt)
- [dll2klib 投影实现](https://github.com/tmyt/dotkt/blob/400ab50472c0839a9b7de6a94a18db4bc9ad4bb5/toolchain/dll2klib/Program.cs)
- [Kotlin PSI 与 Analysis API](https://kotlin.github.io/analysis-api/fundamentals.html)
- [Analysis API Standalone 支持状态](https://kotlin.github.io/analysis-api/index_md.html)
- [IntelliJ Platform LSP 接口](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html)
- [Rider 与 RD 协议](https://www.jetbrains.com/help/resharper/sdk/Rider.html)
- [ReSharper 导航模型](https://www.jetbrains.com/help/resharper/sdk/NavigateCode.html)
- [官方 Kotlin LSP 的能力与状态](https://github.com/Kotlin/kotlin-lsp/blob/main/README.md)
