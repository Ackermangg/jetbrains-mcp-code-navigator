package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class JavaInspectArgs(
    val operation: String,
    val className: String? = null,
    val methodName: String? = null,
    val parameterTypes: List<String>? = null,
    val includeBody: Boolean? = false,
    val includeJavadoc: Boolean? = true,
    val includeFields: Boolean? = true,
    val includeMethods: Boolean? = true,
    val includeInherited: Boolean? = false,
    val maxFields: Int? = null,
    val maxMethods: Int? = null,
    val methodNamePattern: String? = null,
    val methodVisibility: String? = null,
    val excludeSynthetic: Boolean? = false,
    val excludeLombokGenerated: Boolean? = false,
    val filePath: String? = null,
    val filePaths: List<String>? = null,
    val line: Int? = null,
    val column: Int? = null,
    val nearbyLines: Int? = 20,
    val includeImports: Boolean? = true,
    val changedOnly: Boolean? = false,
    val moduleName: String? = null,
    val maxResults: Int? = null
)

class JavaInspectTool : AbstractMcpTool<JavaInspectArgs>(JavaInspectArgs.serializer()) {
    override val name: String = "java_inspect"

    override val description: String = """
        Read focused Java code information without loading whole files.
        operation="class_structure": return class fields, method signatures, annotations, inheritance, and counts without method bodies.
        operation="method_body": return a specific method's signature; set includeBody=true to include the body/Javadoc.
        operation="symbol_context": return imports, fields, one method, and nearby source lines for a class/method or file position.
        operation="diagnostics": return scoped Java PSI syntax errors for filePaths, changed files, or a module.
        operation="mapper_mappings": summarize MapStruct mapping annotations without compiling generated code.
        className accepts short or fully-qualified names and can resolve project classes plus Maven/Gradle dependency classes.
        For overloaded methods, pass parameterTypes; omit it to return all overloads.
    """.trimIndent()

    override fun handle(project: Project, args: JavaInspectArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = when (args.operation.lowercase()) {
                "class_structure" -> {
                    val className = args.className
                        ?: return Response(error = "operation=class_structure requires className")
                    service.getClassStructure(
                        className,
                        args.includeFields ?: true,
                        args.includeMethods ?: true,
                        args.includeInherited ?: false,
                        args.maxFields,
                        args.maxMethods,
                        args.methodNamePattern,
                        args.methodVisibility,
                        args.excludeSynthetic ?: false,
                        args.excludeLombokGenerated ?: false
                    )
                }
                "method_body" -> {
                    val className = args.className
                        ?: return Response(error = "operation=method_body requires className")
                    val methodName = args.methodName
                        ?: return Response(error = "operation=method_body requires methodName")
                    service.getMethodBody(
                        className,
                        methodName,
                        args.includeBody ?: false,
                        args.includeJavadoc ?: true,
                        args.parameterTypes
                    )
                }
                "symbol_context" -> service.getSymbolContext(
                    args.className,
                    args.methodName,
                    args.parameterTypes,
                    args.filePath,
                    args.line,
                    args.column,
                    args.includeImports ?: true,
                    args.includeFields ?: true,
                    args.includeBody ?: false,
                    args.nearbyLines ?: 20,
                    args.maxFields,
                    args.excludeLombokGenerated ?: false
                )
                "diagnostics" -> service.getDiagnostics(
                    args.filePath,
                    args.filePaths,
                    args.moduleName,
                    args.changedOnly ?: false,
                    args.maxResults ?: 100
                )
                "mapper_mappings" -> {
                    val className = args.className
                        ?: return Response(error = "operation=mapper_mappings requires className")
                    service.getMapperMappings(className)
                }
                else -> return Response(error = "Invalid operation: ${args.operation}. Use 'class_structure', 'method_body', 'symbol_context', 'diagnostics', or 'mapper_mappings'")
            }
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
