# DotKt + ASP.NET Core Rider 验证

2026-10-13：已在插件沙箱中载入真正的 Kotlin ASP.NET Core 项目，并将 Kotlin 编译为 .NET 程序，启动 Kestrel、验证 HTTP 响应。

## 项目

- `spikes/native-psi/fixtures/aspnet/DotKt-AspNetCore.sln`
- `KotlinWeb/KotlinWeb.ktproj` 使用 `Microsoft.NET.Sdk.Web`，导入固定 DotKt 源码的 `eng/KotlinClr.targets`。这是 DotKt 自身提供的本地开发入口，与发布 SDK 共用编译 targets。
- `Program.kt` 创建 `WebApplicationBuilder`、构建应用并调用另一文件的 Kotlin 扩展函数。
- `Routes.kt` 用 `MapGet`、`RequestDelegate` 和 `HttpResponse.WriteAsync` 实现 `/`、`/health`。业务入口和端点均为 Kotlin。
- `HelloController.kt` 新增继承 `ControllerBase` 的 Kotlin API Controller，通过 `ApiController`、`Route`、`HttpGet`、`FromRoute` 属性实现属性路由；`GreetingResponse` 是 Kotlin data class。`Program.kt` 已调用 `AddControllers()` 和 `MapControllers()`。

环境沿用此前验证：Rider 2026.2.3.1、IDEA 2026.2.3、DotKt `400ab50472c0839a9b7de6a94a18db4bc9ad4bb5`、.NET SDK 10.0.401 / ASP.NET Core 10.0.12。

## 实测结果

| 项目 | 结果 |
| --- | --- |
| DotKt 编译 | `kotc → BIR → bir2cir → CIR → ilemit → KotlinWeb.dll` 构建成功 |
| 引用输入 | 通过公开 `DotKtResolveKlibReferences` target 获取 307 个 KLIB，另加 CLR stdlib，共 308 个依赖 |
| 项目快照 | 来自实际求值的 `KotlinCompile`，Controller 加入后包含三个源码文件；读取 `DotKtStdlib`、`DotKtKotlinVersion`、TFM/RID |
| 跨文件解析 | `Program.kt` 的 `mapDotKtEndpoints()` 解析到 `Routes.kt` 中的源码声明，origin 为 `SOURCE` |
| 编辑器 | 三个文件的原生 daemon 均完成分析，错误标记列表为空；语义染色和参数名 inlay 可见 |
| 原生成员补全 | `app.` 弹窗包含 `Run`、`RunAsync`、`StartAsync`、`Urls`、`Services` 和本地 `mapDotKtEndpoints` 等候选 |
| HTTP `/` | 200，`Hello from Kotlin on ASP.NET Core!` |
| HTTP `/health` | 200，`{"status":"ok","language":"Kotlin","platform":"ASP.NET Core"}` |
| Controller `/api/hello` | 200，`{"message":"Hello from a Kotlin Controller!","language":"Kotlin"}` |
| Controller `/api/hello/Ada` | 200，`{"message":"Hello, Ada!","language":"Kotlin"}`，路由参数绑定成功 |
| Controller 中文路由参数 | `/api/hello/世界` 返回 200，JSON 中保留 `Hello, 世界!` |

证据：[Rider 报告](evidence/aspnet-rider.json)、[MSBuild 输入及 KLIB 摘要](evidence/aspnet-inputs.json)、[完整构建日志](evidence/aspnet-build.log)、[HTTP 响应](evidence/aspnet-http.json)。实际控件图像：[Program.kt](evidence/aspnet-program.png)、[Routes.kt](evidence/aspnet-routes.png)、[补全弹窗](evidence/aspnet-completion.png)。

Controller 追加验证：[HTTP 响应](evidence/aspnet-controller-http.json)、[三个文件的 Rider 分析与最终焦点](evidence/aspnet-controller-rider.json)、[Controller 编辑器](evidence/aspnet-controller.png)、[构建日志](evidence/aspnet-controller-build.log)。新版启动器会在完成检查后打开 `HelloController.kt`。

当前 DotKt 投影仅提供 `HttpGetAttribute` 的无参注解构造形式；直接写 `@HttpGetAttribute("{name}")` 会被编译器拒绝。样例采用等效的 MVC 写法 `@HttpGetAttribute` 加 `@RouteAttribute("{name}")`，其路由行为已由真实 HTTP 请求验证。

## 本次发现和修正

单文件样例没有暴露跨文件索引问题。初次载入时，ASP.NET Core 的 KLIB 符号已能解析，但 `Program.kt` 看不到 `Routes.kt` 的函数。最终确认 Kotlin 的 `KotlinNonSourceRootIndexFilter` 直接按 IDEA 源码根模型过滤，而 Rider 的 `.ktproj` 没有对应的 Java 源根归属；`.kt` 最初只有 Trigram 索引，没有进入 stub 索引。

沙箱启用 Kotlin 自带的 `kotlin.index.non.source.roots=true` 后，普通 Kotlin stub 索引正常建立。K2 的模块归属和内容范围仍使用明确的项目源码快照，不把所有已索引文件合并为同一个模块。最初关于缺少 stub builder 的假设已被排除，没有保留额外的 stub builder 注册或伪造声明。

启动器增加了自定义项目树、输入快照和补全展示位置，分析报告覆盖同一项目的其他 Kotlin 文件。切换文件后等待编辑焦点稳定，再显示补全。原有混合样例的 K2、补全、两个 C# 重载导航、未保存编辑回归仍通过，见 [回归报告](evidence/aspnet-mixed-regression.json)。

## 复现

工具链输入已按原生验证 README 准备后，从模板根目录运行：

```bash
./gradlew -p spikes/native-psi runAspNetIde
```

该任务先执行 `prepare_aspnet.py` 构建并导出公开的 MSBuild 分析快照，再打开独立 Rider，窗口中的 solution 名为 `DotKt-AspNetCore`。依赖和配置留在沙箱；源码为样例副本。

单独准备或换一个同版本源码 checkout：

```bash
python3 spikes/native-psi/prepare_aspnet.py --dotkt-source /absolute/path/to/dotkt
```

准备脚本生成忽略版本控制的 `DotKt.Local.props`，记录本机工具位置。使用自定义 checkout 启动 Rider 时，也需向 `run_probe.py` 传相同的 `--dotkt-source`。

运行已构建的 Web 程序：

```bash
dotnet spikes/native-psi/fixtures/aspnet/KotlinWeb/bin/Debug/net10.0/KotlinWeb.dll
```

监听 `http://127.0.0.1:5088`。本次运行的是源样例的构建产物；编辑 Rider 沙箱中的副本不会自动重建或热更新这个服务。

## 边界

这是固定工具链的真实小型 Web 项目验证。通用 `.ktproj` 自动发现/刷新、更多 ASP.NET Core 场景、Rider 一键运行/调试与 Kotlin 断点、ASP.NET Core 外部符号的 C# 导航仍未验收。IDE 中的绿色 Kotlin main gutter 不代表这些运行/调试集成已完成。
