# KSharp

基于 [DotKt](https://github.com/tmyt/dotkt) 的 Rider 插件，提供 Kotlin 编辑能力和 .NET 导航。  
DotKt 是一个将 Kotlin 编译为 .NET 可执行文件的工具链，支持 Kotlin/Native 的 KLIB 格式和 CLR 投影。  
Gradle 工程位于 [KSharp 目录](KSharp/README.md)，ReSharper 后端开发 solution 为 `KSharp.sln`。  

当前项目完全基于 Astra 许愿而来，随时可能因任何理由变动

```bash
cd KSharp
./gradlew -PdotnetPath=/path/to/dotnet buildPlugin verifyPluginStructure test
```

安装要求、运行方式和验证记录见 [插件 README](KSharp/README.md)。
