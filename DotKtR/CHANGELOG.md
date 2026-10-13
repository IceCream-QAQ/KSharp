# Changelog

## 0.1.1-dev

- 将编辑器依赖移入 Rider system 目录中的不可变 KLIB 缓存，项目 Clean 不再删除 IDE 使用的库。
- 根据 DLL 内容、引用集合、TFM/RID、标准库与投影工具变化生成新快照；无变化时复用，失败时保留旧快照，重新打开项目可恢复最近的完整快照。
- 在 `.ktproj` 编辑器顶部右侧增加 Refresh KLIB 按钮及刷新状态。
- 保存程序集身份供 ReSharper 导航使用，并按文件系统规则匹配本地/归档 VFS 路径。
- 增加缓存单元测试，以及 Clean、实际补全/诊断、按钮点击和失败恢复的安装级回归。

## 0.1.0-dev

- 将原生 Kotlin 验证代码迁为独立 Rider 插件和 ReSharper 后端。
- 增加受信任项目中的 MSBuild 导入、项目设置、手动刷新与文件变更刷新。
- 将 CLR FIR 规则限定到 DotKt source module 和其 KLIB。
- 集成默认 .NET 声明导航及 main 的原生 .NET Project Run。
- 增加正式 Gradle 打包、严格结构检查、元数据测试和独立安装验证驱动。
- 兼容宿主固定为 Rider 262.10968.170 / IDEA 262.10968.63，尚未发布 Marketplace。
