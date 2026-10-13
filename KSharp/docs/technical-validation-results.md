# DotKtR 原生 Rider 技术验证记录

当前能力、边界和产品化建议的汇总见 [技术能力与产品化评估报告](technical-capability-report.md)。本文件保留各阶段验证细节。

验证日期为 2026 年 10 月 13 日。原生路线已经完成内核/API 验证，并修复首次 GUI 试用发现的问题。在真实 Rider 编辑器中，当前样例的语法/语义染色、实时错误标记及恢复、手动和自动成员补全、候选插入、重载参数提示和悬停签名均通过了交互验证。没有切换到独立分析进程；这仍是固定 SDK 与样例输入的原型，不是完整产品支持。

验证代码和运行命令位于 [原生验证工具](../spikes/native-psi/README.md)。它是技术验证工程，尚未接入正式插件模板的发布流程。

随后也完成了 [Kotlin ASP.NET Core 实际项目验证](aspnet-core-validation.md)：最终包含三份 Kotlin 源码和 Controller，307 个框架引用 KLIB、真实 Web 程序构建和 HTTP 响应均通过；同时补齐了此前单文件样例未暴露的跨文件 stub 索引问题。

## GUI 实测结果

使用实验目录的独立 `runIde` 任务启动了带插件的 Rider 图形窗口，打开 `Probe.sln` 中的 `App.ktproj` 和 `Program.kt`。原生编辑器返回 Kotlin token，169 个 KLIB 的 K2 分析仍通过。

首次试用时，用户看到简单染色和类型推导，但没有实时错误标记或预期补全。其根因与后续修复分别是：

1. Java 插件的 `JavaTreeCopyHandler` 在复制 Kotlin 表达式时也引用 `JspxLanguage`，而这项 IDEA 平台接口没有包含在 Rider 中。为复制到沙箱的 Java 插件补入 IDEA 原始的 `JspxLanguage`、`JspLanguage` marker interface 后，真实补全与插入不再抛出该缺类异常。原始类字节摘要记录在沙箱的 `sdk-adaptations.json`。
2. Kotlin 的 `RootKindMatcher` 通过 Rider 文件索引检查源码类型，而 Rider 没有将 `.ktproj` 的 `.kt` 识别为 Kotlin 源码。增加 `DotKtRootKindMatcher`，仅对已导入快照中的源码返回正确的 Kotlin source 归属，其他文件委托原实现；原生 daemon 的 Kotlin 语法、诊断与语义染色由此正常运行。
3. 自动验证还需要真实编辑焦点和正确的鼠标事件。启动器显式激活窗口，检查编辑器已获取焦点，然后通过正常 typed action 输入点号。悬停使用文字区域内的鼠标移动事件，避开刚收起提示后的首个被忽略事件。

后续 [完整 GUI 报告](evidence/native-gui-complete.json) 记录了以下结果：

| GUI 行为 | 实测证据 |
| --- | --- |
| 正确样例 | daemon 完成分析，错误列表为空，编辑器出现 Kotlin 语义颜色与参数名 inlay |
| `Api.Pick(42L)` | `Pick` 上出现 `NONE_APPLICABLE` 错误，明确指出 `Long` 与 `Int` / `String` 形参不匹配 |
| 未闭合字符串 | 编辑器出现 `Expecting '\"'` 等语法错误和实际错误范围 |
| 修复源码 | daemon 错误列表重新为空 |
| 手动成员补全 | 原生可见 lookup 中有 `Read`、`Value`、`Describe`，候选对象来自 Kotlin K2 |
| 候选插入 | 将 `box.` 补为 `box.Read()`，实际修改编辑器文档 |
| 自动补全 | 通过 `TypedAction` 输入 `.`，无需主动调用 completion handler 即出现上述成员 |
| 参数提示 | 真实参数弹窗列出 `value: Int`、`value: String`，选中当前整数重载 |
| 悬停 | 实际鼠标事件触发签名弹窗，展示 `Pick(value: Int): String` |

截图直接来自实际 Swing 控件绘制：[编辑器](evidence/ui-baseline.png)、[类型错误](evidence/ui-long-error.png)、[语法错误](evidence/ui-syntax-error.png)、[补全弹窗](evidence/ui-completion.png)、[参数提示](evidence/ui-parameter-info.png)、[悬停](evidence/ui-hover.png)。没有手工填充候选或绘制诊断。GUI 验证只编辑新建的测试副本并在结束时恢复源码；普通 `runIde` 不执行这些自动编辑。

首份 [GUI 报告](evidence/native-gui-initial.json) 中 `success=true` 只代表导入与打开编辑器成功，应以独立的 `completionSuccess=false` 判断补全状态；后续版本已拆出 `startupSuccess`，不再把启动成功当作整体成功。

默认 C# 跳转快捷键尚未接入已验证的 RD/ReSharper 桥接；完整 `.ktproj` 自动导入、依赖刷新和分发打包也仍是后续工作。`RootKindMatcher` 的服务适配和对 Java SDK 组件的补齐需要跟随所支持的 IDE 版本维护。

## 环境和输入

- Rider `2026.2.3.1`，build `RD-262.10968.170`，运行时 JBR 25；验证插件使用 JDK 25 编译。
- 从本机 IDEA `2026.2.3` 读取 Kotlin 和 Java 组件。IDE 分析内核为 `2.4.20-ij262-52`。
- dotkt 源码固定为 `400ab50472c0839a9b7de6a94a18db4bc9ad4bb5`，编译器内核为 `2.4.10`。
- 从固定源码构建 `kotc`、`dll2klib` 和真实 CLR stdlib KLIB，未用 JVM stdlib 替代 CLR stdlib。
- C# 样例以 .NET SDK `10.0.401` 构建。MSBuild 提供 167 个框架引用，加上样例 DLL 投影成 168 个 KLIB；与 stdlib 合计 169 个分析依赖。

所有实验均使用独立的 Rider 配置、缓存和插件目录。验证插件及复制的 IDE 组件位于 `spikes/native-psi/build`，没有安装到日常 Rider 的插件目录。

## 实验结果

| 实验 | 结果 | 证据或边界 |
| --- | --- | --- |
| 仅加载 Kotlin 编译器公共组件 | 失败 | `KtFile` 缺少 `com.intellij.psi.PsiClassOwner` |
| 增加 Java PSI 接口模块 | 通过语法验证 | Rider `PsiManager` 返回物理 `KtFile`，识别 `KtClass` 和 `KtNamedFunction`，没有语法错误 |
| 在真实 Rider solution 中运行语法验证 | 通过 | 使用 `ProjectImpl` 和 Rider 自己的 VFS/PSI，未使用编译器 `MockProject` |
| 原样加载 IDEA 的 Java 与 Kotlin 插件 | 失败 | 缺少 `com.intellij.modules.java-capable` 模块声明 |
| 完整 Java/Kotlin 插件加实验性 Java host 声明 | 可启动，沙箱冲突已修正 | 早期发现 Java 与 Rider C++ 的 `CompileFile` 冲突，后续移除沙箱 Java 副本中的同名动作注册；尚未成为可发布的依赖组合 |
| K2 导入 CLR KLIB | 通过样例解析 | common 模块、169 个依赖；两个重载、泛型、构造函数、成员方法和扩展方法均能解析 |
| 普通 K2 分析继承静态成员 | 失败 | `Derived.Echo` 未解析，而同一样例通过 dotkt 编译 |
| 复用 dotkt 的 FIR 静态成员规则 | 通过该调用 | `Derived.Echo` 解析到真实声明 `Base.Echo` |
| CLR stdlib 与 IDE 的 common 平台组合 | 发现语义偏差 | JVM 反序列化扩展给 CLR `String` 添加了 `java.io.Serializable` 父类型 |
| 保持 dotkt 的反序列化边界 | 通过当前样例 | 替换平台反序列化组件后，`String` 父类型恢复为 CLR stdlib 中的两项；7 个调用全部解析成功，诊断为空 |
| Rider 原生补全服务 | 通过当前接收者样例 | 实际 Kotlin contributor 返回 `Read`、`Value`、`Describe`，以及适用的 Kotlin 扩展函数 |
| RD 与 ReSharper 源码定位 | 通过两个重载 | K2 的正式参数类型转换为完整 XML documentation ID，后端找到两个不同的 `CSharpMethod` 声明 |
| 实际源码导航 | 通过两个重载 | 调用 `DeclaredElementNavigationService.Navigate`，Rider 打开 `Api.cs`，光标分别落在声明偏移 `349` 与 `409` |
| `.ktproj` 进入项目模型 | 通过实验适配 | 样例声明 .NET SDK 项目类型与 C# targets 作为项目模型外壳，常规 `.sln` 可同时加载它和 C# 类库；这不等于 ReSharper 已理解 Kotlin 语法 |
| `.slnx` 无界面加载 | 当前启动方式下失败 | 后端报告 `xmlSolutionModel is null` 并创建空模型；改用 `.sln` 后正常加载，尚未定位 `.slnx` 的独立原因 |
| Kotlin 模块生命周期 | 通过修正后的实验 | 持久 `.iml` 模块被 Java 插件延迟的 JPS 配置同步销毁；由 `.ktproj` 导入的模块改为非持久模块后保持有效 |
| 未保存编辑与恢复 | 通过 | 整数实参改为字符串后切换重载，插入语法错误仍可分析已有调用，恢复后回到整数重载；磁盘源码和 KLIB 均未改变 |
| 冷启动 | 通过 | 新建配置与索引，复制不含 `.idea/bin/obj` 的样例项目，重新通过全部启用的断言 |

最终证据为 [冷启动完整报告](evidence/native-cold-closed-loop.json)、[已有会话完整报告](evidence/native-warm-closed-loop.json)和[输入版本及文件摘要](evidence/native-inputs.json)。完整报告同时保留 KLIB 声明位置、ReSharper 返回的 C# 声明位置以及导航后实际编辑器的文件和光标，三者没有混为同一种导航结果。

首次语义与补全报告 [native-psi-k2-completion.json](evidence/native-psi-k2-completion.json) 和中间的 [RD 定位报告](evidence/native-k2-resharper-lookup.json) 用于区分各阶段证据。

## 已确认的集成要点

Kotlin PSI 可以在 Rider 中复用，完整 Kotlin IDE 插件并不是获得 `KtFile` 的必要条件。若要复用现有 K2 分析与补全实现，则还需要 Kotlin 插件的分析服务、Java 组件和正确的模块模型。

KLIB 必须进入 Kotlin source module 的依赖图。只创建 PSI、把文件放进 VFS，或让分析器将文件视为 `KaNotUnderContentRootModule`，都不能替代项目导入。验证程序同时检查 Rider 文件索引、source root、所属 module 和实际分析 module。

派生的 Kotlin 模块使用 `newNonPersistentModule`，避免被 JPS 从磁盘加载的项目状态清理。模块工厂与内容域扩展只把输入快照中的 Kotlin 源文件分配给该模块；KLIB 同样按快照的明确列表导入。

dotkt 的 CLR 静态成员规则可以通过 `LLFirSessionConfigurator` 接入，但该扩展点属于内部 API。当前实验直接编译固定版本 dotkt 的相关源码，配合宿主 IDE 的 Kotlin API；这还需要版本兼容与语义回归验证。

`FirDeserializationExtension` 是可组合组件。默认的泛型 `register` 会保留 JVM 扩展，而 dotkt 的 CLI metadata 前端明确关闭了 JVM 反序列化。实验适配通过 `FirSessionComponent` 重载替换组件后，再安装 dotkt 的静态成员扩展，消除了 `Serializable` 语义污染。

默认导入组件的初始化时序也与 CLI 不同：source session 的扩展回调发生时，`FirDefaultImportsProviderHolder` 还未注册。完整默认导入和诊断策略仍需专门验证，不能从这 7 个调用的成功推断所有 dotkt 语义都已覆盖。

原生补全依赖 IntelliJ 的非物理 PSI 副本及编辑器生命周期。验证工具提供这些调用上下文，但候选来自实际 Kotlin contributor 和 K2 会话，没有手工构造预期候选。

补全断言检查候选对象确实来自 Kotlin K2，避免单词补全中的同名字符串造成假阳性。导航断言检查编辑器实际文件与光标，而非仅检查后端返回的路径。

冷启动后的本次编辑实验中，暖态单个调用解析约为 `1.5 ms`，修改实参后的解析约为 `5.3 ms`，错误代码中的有效调用解析约为 `19.1 ms`。这些是小样例的单次观测，不是整项目分析耗时或 p95 性能承诺。

## 后续验证

- [x] 接通实际 GUI 的补全触发、弹窗与插入；分别验收自动触发和手动触发。
- [x] 接通实时语法/类型诊断；以未闭合字符串、`Long` 传入 `Int` 参数及修复后的标记消失作为验收。
- [x] 验收当前样例的参数提示、悬停信息、编辑器语义染色。
- [ ] 将 RD/ReSharper 桥接接入默认 C# 跳转快捷键，并扩展导航来源映射。
- [x] 完成样例中两个 K2 重载符号经 RD 到 ReSharper 精确源码位置的验证，并触发实际导航。
- [ ] 扩展来源映射，覆盖泛型成员、扩展函数、属性访问器、类型转发及 reference assembly 与源码的关系。
- [x] 在全新的项目和索引目录重复验证，排除已有 `.idea` 或索引状态带来的偶然成功。
- [x] 验证当前样例的未保存编辑和错误恢复会刷新重载解析，且不会修改磁盘源码或重建 KLIB。
- [ ] 验证依赖增删、程序集重新构建、多 TFM 和模块切换时的缓存失效。
- [ ] 验证 CLR 默认导入、value class 诊断、源代码中的继承静态成员及 protected 规则。
- [ ] 收敛所需 Java/Kotlin 模块并解决 action/service 冲突，再接入正式模板构建流程。
- [ ] 为 IDE 与 dotkt Kotlin 内核版本差异建立明确的支持矩阵。

下一步应沿原生路线收敛模块依赖、导入器和符号来源契约。完整默认导入与诊断策略、通用 CLR 来源映射、C# 未编译变更、跨语言查找引用与重命名、外部程序集导航和正式打包仍未完成；不能把本次首轮闭环视作完整 IDE 支持。
