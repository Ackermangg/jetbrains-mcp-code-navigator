package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class FindSymbolArgs(
    val symbolName: String,
    val symbolKinds: List<String>? = null,
    val contextClass: String? = null,
    val includeDependencies: Boolean? = true,
    val maxResults: Int? = 20
)

class FindSymbolTool : AbstractMcpTool<FindSymbolArgs>(FindSymbolArgs.serializer()) {
    override val name: String = "find_symbol"

    override val description: String = """
        Search Java classes, methods, or fields by symbol name and return candidate definitions.
        Use this when you know the symbol name but do not know its exact file or fully-qualified class.
        symbolKinds can limit the search to "class", "method", or "field".
        contextClass narrows method/field candidates to a known containing class.
        includeDependencies controls whether Maven/Gradle dependency classes are included.
    """.trimIndent()

    override fun handle(project: Project, args: FindSymbolArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = service.findSymbol(
                args.symbolName,
                args.symbolKinds,
                args.contextClass,
                args.includeDependencies ?: true,
                args.maxResults ?: 20
            )
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
