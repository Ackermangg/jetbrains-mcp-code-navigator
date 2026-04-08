package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class GetMethodBodyArgs(
    val className: String,
    val methodName: String,
    val includeBody: Boolean? = true,
    val includeJavadoc: Boolean? = true,
    val parameterTypes: List<String>? = null
)

class GetMethodBodyTool : AbstractMcpTool<GetMethodBodyArgs>(GetMethodBodyArgs.serializer()) {
    override val name: String = "get_method_body"

    override val description: String = """
        Get the body of a specific Java method by class name and method name.
        Use this instead of reading entire large files - only returns the target method code.
        Set includeBody=false to get only the method signature (saves tokens).
        parameterTypes is optional: if omitted and multiple overloads exist, all overloads are returned.
        Use parameterTypes to disambiguate overloaded methods when you know the signature.
        className accepts both short name (e.g. "MyService") and fully-qualified name (e.g. "com.example.MyService").
    """.trimIndent()

    override fun handle(project: Project, args: GetMethodBodyArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = service.getMethodBody(
                args.className,
                args.methodName,
                args.includeBody ?: true,
                args.includeJavadoc ?: true,
                args.parameterTypes
            )
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
