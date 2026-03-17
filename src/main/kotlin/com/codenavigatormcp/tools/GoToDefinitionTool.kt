package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class GoToDefinitionArgs(
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val symbolName: String? = null,
    val contextClass: String? = null
)

class GoToDefinitionTool : AbstractMcpTool<GoToDefinitionArgs>(GoToDefinitionArgs.serializer()) {
    override val name: String = "go_to_definition"

    override val description: String = """
        Navigate to the definition of a symbol. Supports two modes:
        1. Position-based: provide filePath + line + column to resolve the symbol at that location.
        2. Name-based: provide symbolName (and optionally contextClass) to find the definition by name.
        Returns the definition's file path, line number, symbol kind, and signature.
    """.trimIndent()

    override fun handle(project: Project, args: GoToDefinitionArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = if (args.filePath != null && args.line != null && args.column != null) {
                service.goToDefinitionByPosition(args.filePath, args.line, args.column)
            } else if (args.symbolName != null) {
                service.goToDefinitionByName(args.symbolName, args.contextClass)
            } else {
                return Response(error = "Provide either (filePath + line + column) or (symbolName) parameters")
            }
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
