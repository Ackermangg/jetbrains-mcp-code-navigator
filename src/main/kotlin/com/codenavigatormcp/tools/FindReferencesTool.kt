package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class FindReferencesArgs(
    val className: String,
    val methodName: String? = null,
    val fieldName: String? = null,
    val scope: String? = "project",
    val maxResults: Int? = 50
)

class FindReferencesTool : AbstractMcpTool<FindReferencesArgs>(FindReferencesArgs.serializer()) {
    override val name: String = "find_references"

    override val description: String = """
        Find all references to a Java class, method, or field in the project.
        Provide className (required) and optionally methodName or fieldName to narrow the search.
        scope controls search range: "project" (default), "module", or "file".
        maxResults limits the number of results (default 50).
        Each reference includes file path, line number, containing method/class, and code snippet.
    """.trimIndent()

    override fun handle(project: Project, args: FindReferencesArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = service.findReferences(
                args.className,
                args.methodName,
                args.fieldName,
                args.scope ?: "project",
                args.maxResults ?: 50
            )
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
