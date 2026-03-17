package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class CallHierarchyArgs(
    val className: String,
    val methodName: String,
    val direction: String? = "callers",
    val depth: Int? = 1,
    val maxResults: Int? = 30
)

class CallHierarchyTool : AbstractMcpTool<CallHierarchyArgs>(CallHierarchyArgs.serializer()) {
    override val name: String = "call_hierarchy"

    override val description: String = """
        Get the call hierarchy of a Java method.
        direction: "callers" (who calls this method), "callees" (what this method calls), or "both".
        depth controls recursion depth (default 1, max 5).
        maxResults limits results per level (default 30).
        Returns a tree structure showing the call chain with file paths and line numbers.
    """.trimIndent()

    override fun handle(project: Project, args: CallHierarchyArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = service.getCallHierarchy(
                args.className,
                args.methodName,
                args.direction ?: "callers",
                args.depth ?: 1,
                args.maxResults ?: 30
            )
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
