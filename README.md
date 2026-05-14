# Code Navigator MCP Plugin

IntelliJ IDEA 插件，基于 IDEA 的 PSI（Program Structure Interface）代码分析引擎，通过 MCP 协议向 AI 编码助手暴露 3 个聚合式 Java 代码导航工具。

## 绝对前提

当前工具是面向ai agent工具的，一切改动都要考虑是否对ai agent和大模型友好，如果用户没考虑到也要主动提出

## 解决的问题

在大型 Java 项目中使用 AI 编码助手时，常见痛点：

- **大文件上下文浪费**：3000+ 行的 Service 文件，仅导航就消耗 15,000–20,000 token
- **缺少方法级读取**：无法只获取目标方法体，必须读取整个文件
- **调用链分析困难**：文本搜索无法精确追踪方法调用关系
- **语义理解不足**：`grep getUserById` 返回 50 个调用点 + 12 个注释，无法区分定义与引用

本插件通过 PSI 语义分析解决上述问题，让 AI 助手可以像 IDE 一样理解代码结构。

## 核心优势：完整覆盖 Maven 依赖

与其他代码导航工具只能分析项目源码不同，本插件直接复用 IntelliJ 的索引引擎，**项目源码和 Maven 仓库中的依赖 JAR 统一可查**：

```
# 读取 hutool JAR 中的方法实现
java_inspect(operation="method_body", className="cn.hutool.core.util.StrUtil", methodName="isEmptyIfStr", includeBody=true)

# 查看 Spring KafkaTemplate 的方法列表
java_inspect(operation="class_structure", className="org.springframework.kafka.core.KafkaTemplate")
```

这意味着当项目代码调用第三方库时，AI 助手可以直接读取库方法的实现，无需手动反编译或切换工具。`filePath` 字段会直接返回 Maven 本地仓库的 `.jar` 路径，方便溯源。

> **注意**：继承的方法定义在父类中，可通过 `java_inspect(operation="class_structure")` 返回的 `superClass` 字段找到父类再查询。

## 工具列表

### `java_resolve`
定位 Java 符号或解析定义，合并原 `find_symbol` 和 `go_to_definition` 能力。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `operation` | string | ✅ | `find_symbol` 或 `go_to_definition` |
| `symbolName` | string | 视模式 | 符号名；`find_symbol` 必填，按名称跳定义时必填 |
| `symbolKinds` | string[] | — | `find_symbol` 时限定搜索类型：`class` / `method` / `field`，默认全部 |
| `contextClass` | string | — | 已知宿主类时用于收窄方法/字段候选 |
| `includeDependencies` | boolean | — | 是否包含 Maven/Gradle 依赖，默认 `true` |
| `maxResults` | int | — | 返回上限，默认 20，最大 100 |
| `filePath` / `line` / `column` | string / int / int | 视模式 | `go_to_definition` 按位置解析时使用，行列号从 1 开始 |

返回值包含 `returnedCount`、`maxResults`、`hasMore` 和 `candidates`。`hasMore=true` 表示结果被截断，需要提高 `maxResults` 或收窄查询条件。

### `java_inspect`
读取类结构、方法代码、窄上下文、限定范围诊断或 MapStruct 映射摘要，合并原 `get_class_structure` 和 `get_method_body` 能力。**支持项目源码和 Maven 依赖 JAR 中的类。**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `operation` | string | ✅ | `class_structure` / `method_body` / `symbol_context` / `diagnostics` / `mapper_mappings` |
| `className` | string | 视模式 | 类名（短名或全限定名）；类/方法/MapStruct 查询时使用 |
| `methodName` | string | 视模式 | `method_body` 必填；`symbol_context` 可选 |
| `parameterTypes` | string[] | — | `method_body` 时用于区分重载方法；`symbol_context` 遇到重载时必须传入 |
| `includeBody` | boolean | — | `method_body` / `symbol_context` 时是否包含方法体，默认 `false` |
| `includeJavadoc` | boolean | — | `method_body` 时是否包含 Javadoc，默认 `true` |
| `includeFields` | boolean | — | `class_structure` / `symbol_context` 时是否包含字段，默认 `true` |
| `includeMethods` | boolean | — | `class_structure` 时是否包含方法签名，默认 `true` |
| `includeInherited` | boolean | — | `class_structure` 时是否包含继承成员，默认 `false` |
| `maxFields` / `maxMethods` | int | — | `class_structure` / `symbol_context` 的字段或方法返回上限 |
| `methodNamePattern` | string | — | `class_structure` 方法名正则过滤 |
| `methodVisibility` | string | — | `class_structure` 方法可见性过滤：`public` / `protected` / `private` / `package` |
| `excludeSynthetic` | boolean | — | `class_structure` 是否过滤无可靠源码位置的 synthetic/light 方法 |
| `excludeLombokGenerated` | boolean | — | `class_structure` / `symbol_context` 是否过滤 Lombok 生成风格方法 |
| `filePath` / `line` / `column` | string / int / int | 视模式 | `symbol_context` 按位置读取或 `diagnostics` 查单文件 |
| `filePaths` | string[] | — | `diagnostics` 查多个文件 |
| `changedOnly` | boolean | — | `diagnostics` 只查 VCS 改动 Java 文件 |
| `moduleName` | string | — | `diagnostics` 查指定模块源码根 |
| `nearbyLines` | int | — | `symbol_context` 附近源码行数，默认 20 |
| `maxResults` | int | — | `diagnostics` 问题返回上限 |

`method_body` 返回值固定为对象，方法结果始终放在 `methods` 数组中；单个重载和多个重载的结构一致。

`symbol_context` 如果 `methodName` 命中多个重载且未传 `parameterTypes`，会返回错误并列出候选签名，避免静默读取错误重载。

`diagnostics` 返回的是 IntelliJ PSI parse error 摘要，适合快速确认指定文件/模块/本次改动文件是否有语法或解析错误；它不等价于 Maven 编译，也不验证 MapStruct/Lombok 生成代码。显式传入的缺失文件和非 Java 文件会通过 `missingFiles` / `skippedFiles` 返回，并将 `inputValid` 标为 `false`。

`mapper_mappings` 只汇总可解析到 `org.mapstruct.*` 的 MapStruct 注解和方法签名，并在返回中标记 `generatedImplementationChecked=false`；生成实现和 Lombok accessor 是否真实可编译仍需 Maven 或 IDE build 验证。

### `java_usage`
分析引用和调用链，合并原 `find_references` 和 `call_hierarchy` 能力。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `operation` | string | ✅ | `find_references` 或 `call_hierarchy` |
| `className` | string | ✅ | 类名 |
| `methodName` | string | 视模式 | 查方法引用或调用链时使用；`call_hierarchy` 必填 |
| `fieldName` | string | — | `find_references` 查字段引用时使用，和 `methodName` 互斥 |
| `scope` | string | — | `find_references` 搜索范围：`project`（默认）、`module`、`file` |
| `direction` | string | — | `call_hierarchy` 方向：`callers` / `callees` / `both`，默认 `callers` |
| `depth` | int | — | `call_hierarchy` 递归深度，默认 1，最大 5 |
| `maxResults` | int | — | 结果上限；引用默认 50，调用链默认每层 30 |

引用结果包含 `returnedCount`、`maxResults`、`hasMore`。调用链结果包含 `returnedCount`、`topLevelReturnedCount`、`maxResultsPerLevel`、`limitScope`、`hasMore`。`hasMore=true` 表示结果被截断，需要提高 `maxResults` 或缩小范围。

## 要求

- **IntelliJ IDEA** 2024.3 及以上（Ultimate 或 Community）
- **MCP Server 插件**（Plugin ID: 26071）已安装并启用
- **Node.js**（用于运行 `@jetbrains/mcp-proxy`）

## 安装

### 方式一：从 Release 安装（推荐）

1. 从 [Releases](../../releases) 下载最新 `code-navigator-mcp-plugin.zip`
2. IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk
3. 选择 zip 文件 → 重启 IDEA

发布新版本时，在本地打 `v*` 标签并推送，GitHub Actions 会自动构建插件 zip、创建 Release，并上传 `code-navigator-mcp-plugin.zip`：

```bash
git tag v1.0.1
git push origin v1.0.1
```

普通 push 和 PR 也会自动执行构建，可在 Actions 页面下载 `code-navigator-mcp-plugin` artifact 进行临时测试。

### 方式二：从源码构建

```bash
git clone https://github.com/your-org/jetbrains-mcp-code-navigator.git
cd jetbrains-mcp-code-navigator
./gradlew buildPlugin
# 产物：build/distributions/code-navigator-mcp-plugin.zip
```

然后按方式一安装 zip 文件。

## 配置 Claude Code

在项目的 `.mcp.json` 或全局 MCP 配置文件中添加：

```json
{
  "mcpServers": {
    "jetbrains": {
      "command": "npx",
      "args": ["-y", "@jetbrains/mcp-proxy"]
    }
  }
}
```

`@jetbrains/mcp-proxy` 会自动发现运行中的 IDEA 实例并桥接 MCP 协议。

## 推荐工作流

- 已知类或方法：优先用 `java_inspect`
- 只知道符号名、不确定定义位置：用 `java_resolve(operation="find_symbol")`
- 已知调用点坐标：用 `java_resolve(operation="go_to_definition")`
- 需要追踪谁在用它：用 `java_usage`

`search_in_files_content` 更适合纯文本场景，不推荐作为 Java 语义定位的首选工具。

## 使用示例

```
# 先按名称找候选定义，再决定读哪个类/方法
java_resolve(operation="find_symbol", symbolName="GenericQueryUtil", symbolKinds=["class"])

# 只读取目标方法，不加载整个文件
java_inspect(operation="method_body", className="StowageProcessService", methodName="clearStowageByWaybillReport")

# 不传 parameterTypes 时，返回所有同名重载
java_inspect(operation="method_body", className="OrderService", methodName="createOrder")

# 读取 Maven 依赖 JAR 中的方法实现
java_inspect(operation="method_body", className="cn.hutool.core.util.StrUtil", methodName="isEmptyIfStr", includeBody=true)

# 获取类结构概览，快速了解有哪些方法（支持 JAR 中的类）
java_inspect(operation="class_structure", className="org.springframework.kafka.core.KafkaTemplate")

# 大类只看字段和前 20 个非 Lombok/synthetic 方法
java_inspect(operation="class_structure", className="Stowage", maxMethods=20, excludeLombokGenerated=true, excludeSynthetic=true)

# 读取指定方法的窄上下文：imports、字段、方法签名和附近源码行
java_inspect(operation="symbol_context", className="OrderService", methodName="createOrder", nearbyLines=20)

# 只检查本次改动 Java 文件的 PSI 解析错误
java_inspect(operation="diagnostics", changedOnly=true)

# 汇总 MapStruct mapper 上的 @Mapping / @Mappings / @BeanMapping 注解
java_inspect(operation="mapper_mappings", className="OrderMapper")

# 精确查找某方法的所有调用点
java_usage(operation="find_references", className="OrderService", methodName="createOrder")

# 分析调用链，了解谁在调用这个方法
java_usage(operation="call_hierarchy", className="PaymentService", methodName="processPayment", direction="callers", depth=2)
```

## 开发

```bash
./gradlew runIde        # 启动沙箱 IDE 进行测试
./gradlew buildPlugin   # 构建插件 zip
```

CI 使用 GitHub Actions 自动执行 `./gradlew buildPlugin -Dorg.gradle.java.home="$JAVA_HOME"`，避免受本地 `gradle.properties` 中 Windows JDK 路径影响。构建产物固定为 `build/distributions/code-navigator-mcp-plugin.zip`。

## 兼容性

| IDE 版本 | 支持 |
|---------|------|
| IDEA 2024.3.x (build 243) | ✅ |
| IDEA 2025.x (build 25x) | ✅ |

插件仅支持 **Java** 语言（不支持 Kotlin/Scala 等）。
