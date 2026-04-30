package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class JavaUsageArgs(
    val operation: String,
    val className: String,
    val methodName: String? = null,
    val fieldName: String? = null,
    val scope: String? = "project",
    val direction: String? = "callers",
    val depth: Int? = 1,
    val maxResults: Int? = null
)

class JavaUsageTool : AbstractMcpTool<JavaUsageArgs>(JavaUsageArgs.serializer()) {
    override val name: String = "java_usage"

    override val description: String = """
        Analyze where Java symbols are used and how Java methods call each other.
        operation="find_references": find project references to a class, method, or field; pass className and optionally methodName or fieldName.
        operation="call_hierarchy": trace callers, callees, or both for a method; requires className and methodName.
        scope for references is "project", "module", or "file". direction for call_hierarchy is "callers", "callees", or "both".
        For call_hierarchy, maxResults is applied per tree level; returnedCount counts all returned tree nodes.
        Results include file paths, line numbers, containing class/method, snippets, or call tree nodes.
    """.trimIndent()

    override fun handle(project: Project, args: JavaUsageArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = when (args.operation.lowercase()) {
                "find_references" -> {
                    if (args.methodName != null && args.fieldName != null) {
                        return Response(error = "operation=find_references accepts methodName or fieldName, not both")
                    }
                    val scope = args.scope ?: "project"
                    if (scope !in setOf("project", "module", "file")) {
                        return Response(error = "Invalid scope: $scope. Use 'project', 'module', or 'file'")
                    }
                    service.findReferences(
                        args.className,
                        args.methodName,
                        args.fieldName,
                        scope,
                        args.maxResults ?: 50
                    )
                }
                "call_hierarchy" -> {
                    val methodName = args.methodName
                        ?: return Response(error = "operation=call_hierarchy requires methodName")
                    service.getCallHierarchy(
                        args.className,
                        methodName,
                        args.direction ?: "callers",
                        args.depth ?: 1,
                        args.maxResults ?: 30
                    )
                }
                else -> return Response(error = "Invalid operation: ${args.operation}. Use 'find_references' or 'call_hierarchy'")
            }
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
