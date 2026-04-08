# Code Navigator MCP Plugin

IntelliJ IDEA 插件，基于 IDEA 的 PSI（Program Structure Interface）代码分析引擎，通过 MCP 协议向 AI 编码助手暴露 6 个精准的 Java 代码导航工具。

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
get_method_body("cn.hutool.core.util.StrUtil", "isEmptyIfStr")

# 查看 Spring KafkaTemplate 的方法列表
get_class_structure("org.springframework.kafka.core.KafkaTemplate")
```

这意味着当项目代码调用第三方库时，AI 助手可以直接读取库方法的实现，无需手动反编译或切换工具。`filePath` 字段会直接返回 Maven 本地仓库的 `.jar` 路径，方便溯源。

> **注意**：继承的方法定义在父类中，可通过 `get_class_structure` 返回的 `superClass` 字段找到父类再查询。

## 工具列表

### `find_symbol`
按符号名搜索 Java 类、方法或字段，返回候选定义列表，适合快速定位尚未确定文件位置的目标。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `symbolName` | string | ✅ | 符号名 |
| `symbolKinds` | string[] | — | 限定搜索类型：`class` / `method` / `field`，默认全部 |
| `contextClass` | string | — | 已知宿主类时用于收窄方法/字段候选 |
| `includeDependencies` | boolean | — | 是否包含 Maven/Gradle 依赖，默认 `true` |
| `maxResults` | int | — | 返回上限，默认 20，最大 100 |

### `get_method_body`
获取指定 Java 方法的代码体。只返回目标方法，而非整个文件。**支持项目源码和 Maven 依赖 JAR 中的类。**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `className` | string | ✅ | 类名（短名或全限定名） |
| `methodName` | string | ✅ | 方法名 |
| `includeBody` | boolean | — | 是否包含方法体，默认 `true`；设为 `false` 仅返回签名 |
| `includeJavadoc` | boolean | — | 是否包含 Javadoc，默认 `true` |
| `parameterTypes` | string[] | — | 参数类型列表，用于区分重载方法；不传则返回所有同名重载 |

### `go_to_definition`
跳转到符号定义，返回文件路径、行号、符号类型和签名。支持两种模式：

| 模式 | 参数 | 说明 |
|------|------|------|
| 按位置 | `filePath` + `line` + `column` | 解析指定坐标处的符号 |
| 按名称 | `symbolName` [+ `contextClass`] | 查找类定义，或在已知类中查找成员定义 |

### `find_references`
查找 Java 类、方法或字段的所有引用，每条结果包含文件路径、行号、所在类/方法和代码片段。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `className` | string | ✅ | 类名 |
| `methodName` | string | — | 方法名（不填则查类的引用） |
| `fieldName` | string | — | 字段名 |
| `scope` | string | — | 搜索范围：`project`（默认）、`module`、`file` |
| `maxResults` | int | — | 结果上限，默认 50 |

### `get_class_structure`
获取类的完整结构概览（不含方法体，节省 token）：字段、方法签名、继承关系、类级注解。**支持项目源码和 Maven 依赖 JAR 中的类。**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `className` | string | ✅ | 类名 |
| `includeFields` | boolean | — | 是否包含字段，默认 `true` |
| `includeMethods` | boolean | — | 是否包含方法签名，默认 `true` |
| `includeInherited` | boolean | — | 是否包含继承成员，默认 `false` |

### `call_hierarchy`
获取方法的调用链，返回带文件路径和行号的树状结构。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `className` | string | ✅ | 类名 |
| `methodName` | string | ✅ | 方法名 |
| `direction` | string | — | `callers`（谁调用了它）/ `callees`（它调用了谁）/ `both`，默认 `callers` |
| `depth` | int | — | 递归深度，默认 1，最大 5 |
| `maxResults` | int | — | 每层结果上限，默认 30 |

## 要求

- **IntelliJ IDEA** 2024.3 及以上（Ultimate 或 Community）
- **MCP Server 插件**（Plugin ID: 26071）已安装并启用
- **Node.js**（用于运行 `@jetbrains/mcp-proxy`）

## 安装

### 方式一：从 Release 安装（推荐）

1. 从 [Releases](../../releases) 下载最新 `code-navigator-mcp-plugin.zip`
2. IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk
3. 选择 zip 文件 → 重启 IDEA

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

- 已知类或方法：优先用 `get_class_structure` 或 `get_method_body`
- 只知道符号名、不确定定义位置：先用 `find_symbol`
- 已知调用点坐标：再用 `go_to_definition`
- 需要追踪谁在用它：用 `find_references` 或 `call_hierarchy`

`search_in_files_content` 更适合纯文本场景，不推荐作为 Java 语义定位的首选工具。

## 使用示例

```
# 先按名称找候选定义，再决定读哪个类/方法
find_symbol("GenericQueryUtil", symbolKinds=["class"])

# 只读取目标方法，不加载整个文件
get_method_body("StowageProcessService", "clearStowageByWaybillReport")

# 不传 parameterTypes 时，返回所有同名重载
get_method_body("OrderService", "createOrder", includeBody=false)

# 读取 Maven 依赖 JAR 中的方法实现
get_method_body("cn.hutool.core.util.StrUtil", "isEmptyIfStr")

# 获取类结构概览，快速了解有哪些方法（支持 JAR 中的类）
get_class_structure("org.springframework.kafka.core.KafkaTemplate")

# 精确查找某方法的所有调用点
find_references("OrderService", methodName="createOrder")

# 分析调用链，了解谁在调用这个方法
call_hierarchy("PaymentService", "processPayment", direction="callers", depth=2)
```

## 开发

```bash
./gradlew runIde        # 启动沙箱 IDE 进行测试
./gradlew buildPlugin   # 构建插件 zip
```

## 兼容性

| IDE 版本 | 支持 |
|---------|------|
| IDEA 2024.3.x (build 243) | ✅ |
| IDEA 2025.x (build 25x) | ✅ |

插件仅支持 **Java** 语言（不支持 Kotlin/Scala 等）。
