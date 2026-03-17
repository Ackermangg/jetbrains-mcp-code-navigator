package com.codenavigatormcp.tools

import com.codenavigatormcp.service.JavaPsiNavigationService
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

@Serializable
data class GetClassStructureArgs(
    val className: String,
    val includeFields: Boolean? = true,
    val includeMethods: Boolean? = true,
    val includeInherited: Boolean? = false
)

class GetClassStructureTool : AbstractMcpTool<GetClassStructureArgs>(GetClassStructureArgs.serializer()) {
    override val name: String = "get_class_structure"

    override val description: String = """
        Get the complete structure of a Java class without method bodies (saves tokens).
        Returns fields (name, type, annotations), method signatures (name, return type, parameters,
        modifiers, line range, javadoc summary), inheritance info (superclass, interfaces),
        and class-level annotations.
        Set includeInherited=true to include inherited members.
    """.trimIndent()

    override fun handle(project: Project, args: GetClassStructureArgs): Response {
        return try {
            val service = JavaPsiNavigationService(project)
            val json = service.getClassStructure(
                args.className,
                args.includeFields ?: true,
                args.includeMethods ?: true,
                args.includeInherited ?: false
            )
            Response(json)
        } catch (e: Exception) {
            Response(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
