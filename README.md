# KSharp

为 DotKt 提供 Kotlin 编辑能力的 Rider 插件，原名 DotKtR。Gradle 工程位于 [DotKtR 目录](DotKtR/README.md)，ReSharper 后端开发 solution 为 `DotKtR.sln`。

当前版本为 `0.1.2-dev`，安装包为 `KSharp-0.1.2-dev.zip`。支持项目导入、Kotlin 编辑、.NET 导航和 main 运行，以及独立于构建产物的 KLIB 缓存和 `.ktproj` 编辑器刷新按钮；依赖文档列出的固定 Kotlin/Java 兼容宿主，尚未发布到 Marketplace。

```bash
cd DotKtR
./gradlew -PdotnetPath=/path/to/dotnet buildPlugin verifyPluginStructure test
```

安装要求、运行方式和验证记录见 [插件 README](DotKtR/README.md)。
