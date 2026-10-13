# Rider 原生 Kotlin 验证工具

在独立 Rider 进程中测试真实 Kotlin PSI、K2、CLR KLIB、原生补全，以及通过 RD 调用 ReSharper。配置、缓存、临时插件和报告均位于本目录的 `build` 下。

验证使用本机 IDEA 的 Java/Kotlin 组件，并在实验插件中声明 Java host 能力。该组合仍需收敛依赖并做版本兼容验证；不要把实验插件目录直接当作发布包。

## 环境

- macOS arm64，Rider `2026.2.3.1` 与 IDEA `2026.2.3`。
- JDK 25 用于编译验证插件，JDK 21 用于构建 dotkt。
- .NET SDK 10，正常可用的 Rider 授权。
- dotkt checkout 固定到 `400ab50472c0839a9b7de6a94a18db4bc9ad4bb5`。这里不修改 dotkt 源码。

IDE 路径、JDK 和 dotnet 路径可通过脚本参数调整，使用 `--help` 查看。

## 准备真实编译输入

```bash
python3 prepare_inputs.py --dotkt-source /absolute/path/to/dotkt
```

该命令构建 dotkt 工具链和 CLR stdlib，构建 C# 样例，通过 MSBuild 取得框架引用，调用真实 `dll2klib`，然后用 dotkt 编译同一份 Kotlin 样例作为对照，并编译验证后端。

如果对应 checkout 的 `kotc`、CLR stdlib 和本实验的 `dll2klib` 已构建，可传 `--reuse-toolchain`。脚本输出来源文件摘要、引用集合和执行命令到 `build/input-snapshot.json`；`expectedDotktCommit` 是要求的基线，不能代替调用者核对 checkout 的版本与本地修改。

样例 `App.ktproj` 包含用于 Rider/.NET solution 导入的项目类型声明。设置 `DotKtSourceRoot` 后才能导入 dotkt 的构建 targets；未设置时仅用于项目模型实验。编译器对照由准备脚本直接调用真实 `kotc` 完成。

## 运行完整实验

从本目录执行：

```bash
python3 run_probe.py \
  --dotkt-source /absolute/path/to/dotkt \
  --full-kotlin --experimental-java-host --clr-sessions \
  --completion --backend --navigate --edits \
  --solution fixtures/mixed/Probe.sln \
  --analysis-source fixtures/mixed/App/Program.kt \
  --license-config "$HOME/Library/Application Support/JetBrains/Rider2026.2"
```

`--license-config` 让沙箱通过符号链接沿用本机的 `rider.key`，走正常授权检查。脚本不会输出或打包授权内容。

报告包含真实宿主 build、PSI 类型、分析模块、KLIB 数量、解析出的调用签名、诊断、补全候选对象、ReSharper 声明位置、导航后的编辑器光标，以及未保存编辑的分析结果。进程只有在启用的断言全部通过时才返回成功。重复运行时，上一份报告和启动参数保存在对应 `build` 子目录的 `history` 中。

在完整命令中增加 `--fresh`，会创建全新的配置和索引目录，并复制不含 `.idea`、`bin`、`obj` 的样例项目，用于冷启动验证。KLIB 输入始终读取 `build/input-snapshot.json` 的明确列表，不扫描目录拾取旧投影。

## 分离实验

```bash
# 负面对照：只有编译器公共组件，缺少 Java PSI 接口。
python3 run_probe.py --license-config /path/to/Rider2026.2

# 最小原生 PSI：不安装完整 Java/Kotlin 插件。
python3 run_probe.py --with-java-psi --license-config /path/to/Rider2026.2

# 负面对照：不声明 Java host 能力，检查产品模块依赖。
python3 run_probe.py --full-kotlin --license-config /path/to/Rider2026.2
```

语义实验可逐步添加 `--analysis-source`、`--clr-sessions`、`--completion`、`--backend`、`--navigate`、`--edits`。`--backend` 会在复制插件前构建当前后端，`--navigate` 同时需要 `--backend`。

命令行 `--navigate` 保留最初的两个 C# 重载验证。GUI 已接入默认声明导航，使用 CLR 来源、程序集与完整 XML documentation ID 匹配；已验证两个项目内重载及五个 ASP.NET Core 外部源码目标。复杂投影与完整快照身份仍是后续工作。

## 打开交互式 Rider

完成输入准备后，在本目录运行 `gradle runIde`，或从模板根目录运行：

```bash
./gradlew -p spikes/native-psi runIde
```

这是本实验独立的 Gradle 启动任务，调用 `run_probe.py --ui`，使用已安装的 Rider、IDEA 和已准备的 KLIB。模板根项目的 `runIde` 尚未整合本实验。

任务会创建独立沙箱，复制样例，启动真正的 Rider 窗口，导入 Kotlin 模块，并打开 `Program.kt`。窗口保持运行，关闭该 Rider 后任务结束。默认尝试在 `box.` 后显示原生补全；也可通过 **Tools → DotKt: Show Demo Completion** 再次打开。

当前 GUI 实测已通过：简单及语义染色、类型推导、实时语法/类型错误与恢复、手动及自动成员补全、候选插入、重载参数提示和悬停签名。可以试 `box.`、`Api.Pick(42L)` 及未闭合字符串。Cmd＋左键已接入 .NET 源码导航，可点击 `Api.Pick` 的两个重载；输入依赖仍是准备脚本生成的快照，不会自动重新编译 C#。

启动器支持 Rider 请求的正常重启，保留同一沙箱的启动参数。使用 `-PdemoSandbox=/absolute/path/to/sandbox` 可重新打开已有沙箱，保留语言设置和样例编辑；默认创建新沙箱。

需要调整路径时，直接运行脚本：

```bash
python3 run_probe.py --ui --fresh \
  --full-kotlin --experimental-java-host --clr-sessions --backend \
  --solution fixtures/mixed/Probe.sln \
  --analysis-source fixtures/mixed/App/Program.kt \
  --license-config "$HOME/Library/Application Support/JetBrains/Rider2026.2"
```

普通 GUI 窗口的 solution 名为 `DotKt-Demo`。增加 `--ui-checks` 会创建名为 `DotKt-UI-Checks` 的验证窗口，自动修改并恢复新副本，观察真实 daemon 错误标记、输入点号、选择补全、参数与悬停控件，并保存 `ui-*.png`；该选项必须与 `--fresh` 一起使用。验证期间应让该窗口保持编辑焦点。普通启动不执行自动编辑。

交互式状态记录在新沙箱的 `report.json`。`startupSuccess` 表示窗口与导入成功，`completionSuccess` 表示实际初始补全候选可见；启用检查时还必须查看 `editorChecks.success`，整体 `success` 同时要求它通过。失败和取消不能视为通过。

在混合或 ASP.NET Core GUI 启动命令后加 `--navigation-checks`，会通过真实 IDE 事件队列发送 Cmd＋左键，检查实际打开的 C# 声明与光标，并写入 `dotnetNavigation`、`dotnet-navigation.jsonl` 和 `navigation-*.png`。Web 检查还确认本地 Kotlin 函数不被桥接接管。该检查移动编辑焦点，不修改源码；普通启动不会自动执行导航检查。

沙箱适配了 Kotlin 的 `RootKindMatcher`，并补入 Java PSI 复制所需的两个原始 IDEA 平台 marker interface。复制的 Java 插件中移除了与 Rider 冲突的 `CompileFile` action 注册，保留 Rider 自身的动作。修改仅发生在沙箱副本，摘要与修改清单记录在 `sdk-adaptations.json`；这还不是可直接发布的 SDK 依赖组合。

结果与剩余工作见仓库 `docs/technical-validation-results.md` 和 `docs/technical-validation-todo.md`。

## Kotlin ASP.NET Core 示例

从模板根目录执行 `./gradlew -p spikes/native-psi runAspNetIde`，会准备并打开 `fixtures/aspnet` 中的真实 Kotlin Minimal API 项目。该示例通过 DotKt 公开的 MSBuild target 获取框架 KLIB，支持 `Program.kt` / `Routes.kt` 跨文件解析，并已构建和运行两个 HTTP 端点。详细结果、证据与运行方式见 `docs/aspnet-core-validation.md`。

当前样例还包含 `HelloController.kt`。可 Cmd＋左键点击 `ControllerBase`、`Ok`、`MapControllers`、`CreateBuilder` 或 `StatusCode`，由 ReSharper 打开相应 .NET library source；这五项已实际定位到原始 ASP.NET Core C# 源码。源码不可用时沿用 Rider 外部导航策略；离线反编译降级尚未专项验收。构造函数目前定位到所属类型，复杂签名可能明确降级到类型或报告无法匹配。

`Program.kt` 的 `main` 现在提供 DotKt 运行图标。点击 Run 会选择原生 `.NET Project` 配置，先构建当前沙箱中的 `.ktproj`，再在 Rider Run 窗口启动 .NET 程序；可使用原生 Stop。已有的 `KotlinWeb` 配置会被复用。当前支持单 TFM 的顶层普通 `main`；Kotlin 断点和调试映射尚未验收。

在 ASP.NET Core GUI 命令中增加 `--run-checks` 可验证 main 运行，必须同时使用 `--ui --fresh`。检查从 main 上下文取得原生配置，通过平台运行 API 执行 Build、HTTP、Stop、编译失败阻止启动和修改源码后重跑；普通 `RunClass` 动作另有已保存的成功证据。检查临时修改全新副本、使用临时监听端口，并在结束后停止测试进程及恢复源码；报告写入 `mainRun`。普通 GUI 启动不会自动运行应用。

自定义 GUI 项目可用 `--fixture-root` 指定要复制的项目树，用 `--input-snapshot` 提供其明确的源码与 KLIB 集合。输入快照中的可选 `demo` 字段设置窗口名称、展示补全的位置和预期成员；不生成补全候选。`--ui-checks` 仍专门测试原混合样例，不接受自定义项目 profile。

Rider 对 `.ktproj` 不提供 IDEA 的 Java 源根分类，因此实验启动器开启 Kotlin 的 `kotlin.index.non.source.roots` 选项，使 `.kt` 能进入真实 stub 索引；实际分析范围继续由项目快照控制。
