package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class JavaResolveArgs(
    val operation: String,
    val symbolName: String? = null,
    val symbolKinds: List<String>? = null,
    val contextClass: String? = null,
    val includeDependencies: Boolean? = true,
    val maxResults: Int? = 20,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null
)

class JavaResolveTool : AbstractMcpTool<JavaResolveArgs>(JavaResolveArgs.serializer()) {
    override val name: String = "java_resolve"

    override val description: String = """
        Locate Java symbols or resolve a concrete definition.
        operation="find_symbol": search classes, methods, or fields by name and return candidate definitions.
        operation="go_to_definition": resolve the symbol at filePath+line+column (1-based), or resolve symbolName as a class/member definition.
        Use find_symbol first when a short name may be ambiguous; use fully-qualified class names for dependency classes.
        symbolKinds can be "class", "method", or "field". contextClass narrows member lookup.
    """.trimIndent()

    override fun handle(project: Project, args: JavaResolveArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = when (args.operation.lowercase()) {
                "find_symbol" -> {
                    val symbolName = args.symbolName
                        ?: return Response(error = "operation=find_symbol requires symbolName")
                    service.findSymbol(
                        symbolName,
                        args.symbolKinds,
                        args.contextClass,
                        args.includeDependencies ?: true,
                        args.maxResults ?: 20
                    )
                }
                "go_to_definition" -> {
                    val filePath = args.filePath
                    val line = args.line
                    val column = args.column
                    val symbolName = args.symbolName
                    when {
                        filePath != null && line != null && column != null && symbolName == null ->
                            service.goToDefinitionByPosition(filePath, line, column)
                        symbolName != null && filePath == null && line == null && column == null ->
                            service.goToDefinitionByName(symbolName, args.contextClass)
                        else -> return Response(error = "operation=go_to_definition requires either filePath+line+column or symbolName, but not both")
                    }
                }
                else -> return Response(error = "Invalid operation: ${args.operation}. Use 'find_symbol' or 'go_to_definition'")
            }
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
