package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class JavaInspectArgs(
    val operation: String,
    val className: String,
    val methodName: String? = null,
    val parameterTypes: List<String>? = null,
    val includeBody: Boolean? = false,
    val includeJavadoc: Boolean? = true,
    val includeFields: Boolean? = true,
    val includeMethods: Boolean? = true,
    val includeInherited: Boolean? = false
)

class JavaInspectTool : AbstractMcpTool<JavaInspectArgs>(JavaInspectArgs.serializer()) {
    override val name: String = "java_inspect"

    override val description: String = """
        Read focused Java code information without loading whole files.
        operation="class_structure": return class fields, method signatures, annotations, inheritance, and counts without method bodies.
        operation="method_body": return a specific method's signature; set includeBody=true to include the body/Javadoc.
        className accepts short or fully-qualified names and can resolve project classes plus Maven/Gradle dependency classes.
        For overloaded methods, pass parameterTypes; omit it to return all overloads.
    """.trimIndent()

    override fun handle(project: Project, args: JavaInspectArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = when (args.operation.lowercase()) {
                "class_structure" -> service.getClassStructure(
                    args.className,
                    args.includeFields ?: true,
                    args.includeMethods ?: true,
                    args.includeInherited ?: false
                )
                "method_body" -> {
                    val methodName = args.methodName
                        ?: return Response(error = "operation=method_body requires methodName")
                    service.getMethodBody(
                        args.className,
                        methodName,
                        args.includeBody ?: false,
                        args.includeJavadoc ?: true,
                        args.parameterTypes
                    )
                }
                else -> return Response(error = "Invalid operation: ${args.operation}. Use 'class_structure' or 'method_body'")
            }
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
