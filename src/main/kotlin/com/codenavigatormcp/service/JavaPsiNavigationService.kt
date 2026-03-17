package com.codenavigatormcp.service

import com.codenavigatormcp.util.PsiUtils
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

class JavaPsiNavigationService(private val project: Project) {

    // ===== GetMethodBody =====

    fun getMethodBody(
        className: String,
        methodName: String,
        includeBody: Boolean,
        includeJavadoc: Boolean,
        parameterTypes: List<String>?
    ): String = runReadAction {
        val psiClass = PsiUtils.findClass(project, className)

        // Support constructors: if methodName matches class short name, also look at constructors
        var methods = psiClass.findMethodsByName(methodName, false).toList()
        if (methods.isEmpty() && methodName == psiClass.name) {
            methods = psiClass.constructors.toList()
        }
        if (methods.isEmpty()) {
            throw IllegalArgumentException("Method not found: $methodName in ${psiClass.qualifiedName ?: className}")
        }

        val targetMethods = if (parameterTypes != null) {
            val filtered = methods.filter { method ->
                method.parameterList.parameters.map { it.type.presentableText } == parameterTypes
            }
            if (filtered.isEmpty()) {
                throw IllegalArgumentException(
                    "No overload of '$methodName' matches parameter types $parameterTypes. " +
                    "Available overloads: ${methods.map { m -> m.parameterList.parameters.map { it.type.presentableText } }}"
                )
            }
            filtered
        } else {
            methods
        }

        if (targetMethods.size == 1) {
            buildMethodJson(targetMethods[0], psiClass, includeBody, includeJavadoc)
        } else {
            "[${targetMethods.joinToString(",") { buildMethodJson(it, psiClass, includeBody, includeJavadoc) }}]"
        }
    }

    private fun buildMethodJson(
        method: PsiMethod,
        psiClass: PsiClass,
        includeBody: Boolean,
        includeJavadoc: Boolean
    ): String {
        val filePath = method.containingFile.virtualFile?.let {
            PsiUtils.toProjectRelativePath(project, it)
        } ?: ""
        val (startLine, endLine) = PsiUtils.getLineRange(method)
        val signature = buildSignatureString(method)

        return buildString {
            append("{")
            append("\"filePath\":\"${PsiUtils.jsonEscape(filePath)}\",")
            append("\"className\":\"${PsiUtils.jsonEscape(psiClass.qualifiedName ?: psiClass.name ?: "")}\",")
            append("\"methodName\":\"${PsiUtils.jsonEscape(method.name)}\",")
            append("\"lineRange\":{\"start\":$startLine,\"end\":$endLine},")
            append("\"signature\":\"${PsiUtils.jsonEscape(signature)}\"")

            if (includeBody) {
                val bodyText = if (includeJavadoc) {
                    method.text
                } else {
                    val docComment = method.docComment
                    if (docComment != null) {
                        method.text.removePrefix(docComment.text).trimStart()
                    } else {
                        method.text
                    }
                }
                append(",\"body\":\"${PsiUtils.jsonEscape(bodyText)}\"")
            } else {
                append(",\"returnType\":\"${PsiUtils.jsonEscape(method.returnType?.presentableText ?: "void")}\"")
                append(",\"parameters\":[")
                append(method.parameterList.parameters.joinToString(",") { p ->
                    "{\"name\":\"${PsiUtils.jsonEscape(p.name ?: "")}\",\"type\":\"${PsiUtils.jsonEscape(p.type.presentableText)}\"}"
                })
                append("]")
            }

            append("}")
        }
    }

    private fun buildSignatureString(method: PsiMethod): String {
        val modifiers = PsiUtils.getModifiers(method).joinToString(" ")
        val returnType = method.returnType?.presentableText ?: ""
        val params = method.parameterList.parameters.joinToString(", ") {
            "${it.type.presentableText} ${it.name ?: ""}"
        }
        val parts = mutableListOf<String>()
        if (modifiers.isNotEmpty()) parts.add(modifiers)
        if (returnType.isNotEmpty()) parts.add(returnType)
        parts.add("${method.name}($params)")
        return parts.joinToString(" ")
    }

    // ===== GoToDefinition =====

    fun goToDefinitionByPosition(filePath: String, line: Int, column: Int): String = runReadAction {
        val resolvedPath = resolveFilePath(filePath)
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            ?: throw IllegalArgumentException("File not found: $filePath")
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: throw IllegalArgumentException("Cannot parse file: $filePath")
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: throw IllegalArgumentException("Cannot get document for: $filePath")

        if (line < 1 || line > document.lineCount) {
            throw IllegalArgumentException("Line $line out of range (1-${document.lineCount})")
        }

        val lineStartOffset = document.getLineStartOffset(line - 1)
        val lineEndOffset = document.getLineEndOffset(line - 1)
        val offset = lineStartOffset + (column - 1)

        if (offset < lineStartOffset || offset > lineEndOffset) {
            throw IllegalArgumentException("Column $column out of range for line $line")
        }

        val element = psiFile.findElementAt(offset)
            ?: throw IllegalArgumentException("No element at $filePath:$line:$column")

        val resolved = resolveElement(element)
            ?: throw IllegalArgumentException("Cannot resolve definition at $filePath:$line:$column")

        buildDefinitionJson(resolved)
    }

    fun goToDefinitionByName(symbolName: String, contextClass: String?): String = runReadAction {
        if (contextClass != null) {
            val psiClass = PsiUtils.findClass(project, contextClass)

            val methods = psiClass.findMethodsByName(symbolName, true)
            if (methods.isNotEmpty()) return@runReadAction buildDefinitionJson(methods[0])

            val field = psiClass.findFieldByName(symbolName, true)
            if (field != null) return@runReadAction buildDefinitionJson(field)

            val innerClass = psiClass.findInnerClassByName(symbolName, true)
            if (innerClass != null) return@runReadAction buildDefinitionJson(innerClass)

            throw IllegalArgumentException("Symbol '$symbolName' not found in class '$contextClass'")
        } else {
            val psiClass = PsiUtils.findClass(project, symbolName)
            buildDefinitionJson(psiClass)
        }
    }

    private fun resolveElement(element: PsiElement): PsiElement? {
        element.parent?.reference?.resolve()?.let { return it }
        element.reference?.resolve()?.let { return it }
        for (ref in element.parent?.references.orEmpty()) {
            ref.resolve()?.let { return it }
        }
        return null
    }

    private fun buildDefinitionJson(element: PsiElement): String {
        val file = element.containingFile?.virtualFile
        val filePath = file?.let { PsiUtils.toProjectRelativePath(project, it) } ?: ""
        val line = PsiUtils.getLineNumber(element)

        val (symbolName, symbolKind, containingClass, signature) = when (element) {
            is PsiMethod -> {
                val cls = element.containingClass
                arrayOf(
                    element.name,
                    "method",
                    cls?.qualifiedName ?: cls?.name ?: "",
                    buildSignatureString(element)
                )
            }
            is PsiField -> {
                val cls = element.containingClass
                arrayOf(
                    element.name,
                    "field",
                    cls?.qualifiedName ?: cls?.name ?: "",
                    "${element.type.presentableText} ${element.name}"
                )
            }
            is PsiClass -> {
                arrayOf(
                    element.name ?: "",
                    "class",
                    element.qualifiedName ?: element.name ?: "",
                    element.qualifiedName ?: element.name ?: ""
                )
            }
            is PsiParameter -> {
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                arrayOf(
                    element.name ?: "",
                    "parameter",
                    method?.containingClass?.qualifiedName ?: "",
                    "${element.type.presentableText} ${element.name}"
                )
            }
            else -> {
                arrayOf(element.text.take(50), "unknown", "", "")
            }
        }

        return buildString {
            append("{")
            append("\"definitionFile\":\"${PsiUtils.jsonEscape(filePath)}\",")
            append("\"definitionLine\":$line,")
            append("\"symbolName\":\"${PsiUtils.jsonEscape(symbolName)}\",")
            append("\"symbolKind\":\"$symbolKind\",")
            append("\"containingClass\":\"${PsiUtils.jsonEscape(containingClass)}\",")
            append("\"signature\":\"${PsiUtils.jsonEscape(signature)}\"")
            append("}")
        }
    }

    // ===== FindReferences =====

    fun findReferences(
        className: String,
        methodName: String?,
        fieldName: String?,
        scope: String,
        maxResults: Int
    ): String = runReadAction {
        val psiClass = PsiUtils.findClass(project, className)

        val targetElement: PsiNamedElement = when {
            methodName != null -> {
                psiClass.findMethodsByName(methodName, false).firstOrNull()
                    ?: throw IllegalArgumentException("Method '$methodName' not found in ${psiClass.qualifiedName ?: className}")
            }
            fieldName != null -> {
                psiClass.findFieldByName(fieldName, false)
                    ?: throw IllegalArgumentException("Field '$fieldName' not found in ${psiClass.qualifiedName ?: className}")
            }
            else -> psiClass
        }

        val searchScope = when (scope) {
            "module" -> {
                val module = com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(targetElement as PsiElement)
                if (module != null) module.moduleScope else GlobalSearchScope.projectScope(project)
            }
            "file" -> GlobalSearchScope.fileScope((targetElement as PsiElement).containingFile)
            else -> GlobalSearchScope.projectScope(project)
        }

        val references = mutableListOf<String>()
        val effectiveMax = maxResults.coerceIn(1, 200)

        // CRITICAL FIX: Use Processor returning false to actually stop iteration
        ReferencesSearch.search(targetElement as PsiElement, searchScope).forEach(Processor { ref ->
            if (references.size >= effectiveMax) return@Processor false

            val element = ref.element
            val refFile = element.containingFile?.virtualFile
            val refFilePath = refFile?.let { PsiUtils.toProjectRelativePath(project, it) } ?: ""
            val refLine = PsiUtils.getLineNumber(element)
            val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

            val snippet = getCodeSnippet(element)

            references.add(buildString {
                append("{")
                append("\"filePath\":\"${PsiUtils.jsonEscape(refFilePath)}\",")
                append("\"line\":$refLine,")
                append("\"containingMethod\":${containingMethod?.name?.let { "\"${PsiUtils.jsonEscape(it)}\"" } ?: "null"},")
                append("\"containingClass\":${containingClass?.let { "\"${PsiUtils.jsonEscape(it.qualifiedName ?: it.name ?: "")}\"" } ?: "null"},")
                append("\"codeSnippet\":\"${PsiUtils.jsonEscape(snippet)}\"")
                append("}")
            })
            true
        })

        val symbolKind = when (targetElement) {
            is PsiMethod -> "method"
            is PsiField -> "field"
            is PsiClass -> "class"
            else -> "unknown"
        }

        buildString {
            append("{")
            append("\"symbolName\":\"${PsiUtils.jsonEscape(targetElement.name ?: "")}\",")
            append("\"symbolKind\":\"$symbolKind\",")
            append("\"returnedCount\":${references.size},")
            append("\"references\":[${references.joinToString(",")}]")
            append("}")
        }
    }

    private fun getCodeSnippet(element: PsiElement): String {
        var current: PsiElement? = element
        while (current != null && current !is PsiStatement && current !is PsiField && current !is PsiMethod) {
            current = current.parent
        }
        val text = (current ?: element.parent ?: element).text
        return text.take(150).replace("\n", " ").trim()
    }

    // ===== GetClassStructure =====

    fun getClassStructure(
        className: String,
        includeFields: Boolean,
        includeMethods: Boolean,
        includeInherited: Boolean
    ): String = runReadAction {
        val psiClass = PsiUtils.findClass(project, className)
        val filePath = psiClass.containingFile?.virtualFile?.let {
            PsiUtils.toProjectRelativePath(project, it)
        } ?: ""

        val parts = mutableListOf<String>()
        parts.add("\"className\":\"${PsiUtils.jsonEscape(psiClass.qualifiedName ?: psiClass.name ?: "")}\"")
        parts.add("\"filePath\":\"${PsiUtils.jsonEscape(filePath)}\"")
        parts.add("\"superClass\":${psiClass.superClass?.qualifiedName?.let { "\"${PsiUtils.jsonEscape(it)}\"" } ?: "null"}")

        // Interfaces
        val interfaces = psiClass.interfaces.joinToString(",") { "\"${PsiUtils.jsonEscape(it.qualifiedName ?: it.name ?: "")}\"" }
        parts.add("\"interfaces\":[$interfaces]")

        // Class annotations
        val annotations = psiClass.annotations.joinToString(",") {
            "\"${PsiUtils.jsonEscape(it.qualifiedName?.substringAfterLast('.') ?: "")}\""
        }
        parts.add("\"annotations\":[$annotations]")

        // Fields
        if (includeFields) {
            val fields = if (includeInherited) {
                psiClass.allFields.filter { it.containingClass?.qualifiedName != "java.lang.Object" }
            } else {
                psiClass.fields.toList()
            }
            val fieldsJson = fields.joinToString(",") { field ->
                val fieldAnnotations = field.annotations.joinToString(",") {
                    "\"${PsiUtils.jsonEscape(it.qualifiedName?.substringAfterLast('.') ?: "")}\""
                }
                val fieldModifiers = PsiUtils.getModifiers(field).joinToString(",") { "\"$it\"" }
                "{\"name\":\"${PsiUtils.jsonEscape(field.name)}\",\"type\":\"${PsiUtils.jsonEscape(field.type.presentableText)}\",\"modifiers\":[$fieldModifiers],\"annotations\":[$fieldAnnotations]}"
            }
            parts.add("\"fields\":[$fieldsJson]")
        }

        // Methods
        if (includeMethods) {
            val methods = if (includeInherited) {
                psiClass.allMethods.filter { it.containingClass?.qualifiedName != "java.lang.Object" }
            } else {
                psiClass.methods.toList()
            }
            val methodsJson = methods.joinToString(",") { method ->
                val (mStart, mEnd) = PsiUtils.getLineRange(method)
                val javadoc = method.docComment?.let { PsiUtils.extractJavadocSummary(it) }
                val params = method.parameterList.parameters.joinToString(",") { "\"${PsiUtils.jsonEscape(it.type.presentableText)}\"" }
                val modifiers = PsiUtils.getModifiers(method).joinToString(",") { "\"$it\"" }
                buildString {
                    append("{\"name\":\"${PsiUtils.jsonEscape(method.name)}\",")
                    append("\"returnType\":\"${PsiUtils.jsonEscape(method.returnType?.presentableText ?: "void")}\",")
                    append("\"parameters\":[$params],")
                    append("\"modifiers\":[$modifiers],")
                    append("\"lineRange\":{\"start\":$mStart,\"end\":$mEnd}")
                    if (javadoc != null) {
                        append(",\"javadoc\":\"${PsiUtils.jsonEscape(javadoc)}\"")
                    }
                    append("}")
                }
            }
            parts.add("\"methods\":[$methodsJson]")
        }

        // Stats
        parts.add("\"totalMethods\":${psiClass.methods.size}")
        parts.add("\"totalFields\":${psiClass.fields.size}")

        val document = PsiDocumentManager.getInstance(project).getDocument(psiClass.containingFile)
        if (document != null) {
            parts.add("\"totalLines\":${document.lineCount}")
        }

        "{${parts.joinToString(",")}}"
    }

    // ===== CallHierarchy =====

    fun getCallHierarchy(
        className: String,
        methodName: String,
        direction: String,
        depth: Int,
        maxResults: Int
    ): String = runReadAction {
        val psiClass = PsiUtils.findClass(project, className)
        val method = psiClass.findMethodsByName(methodName, false).firstOrNull()
            ?: throw IllegalArgumentException("Method '$methodName' not found in ${psiClass.qualifiedName ?: className}")

        val effectiveDepth = depth.coerceIn(1, 5)
        val effectiveMax = maxResults.coerceIn(1, 100)
        val targetSignature = PsiUtils.formatMethodSignature(method)

        buildString {
            append("{")
            append("\"targetMethod\":\"${PsiUtils.jsonEscape(targetSignature)}\",")
            append("\"direction\":\"${PsiUtils.jsonEscape(direction)}\"")

            when (direction) {
                "callers" -> {
                    val visited = mutableSetOf(getMethodKey(method))
                    val callers = findCallers(method, effectiveDepth, effectiveMax, visited)
                    append(",\"hierarchy\":[$callers]")
                }
                "callees" -> {
                    val visited = mutableSetOf(getMethodKey(method))
                    val callees = findCallees(method, effectiveDepth, effectiveMax, visited)
                    append(",\"hierarchy\":[$callees]")
                }
                "both" -> {
                    val visitedCallers = mutableSetOf(getMethodKey(method))
                    val callers = findCallers(method, effectiveDepth, effectiveMax, visitedCallers)
                    val visitedCallees = mutableSetOf(getMethodKey(method))
                    val callees = findCallees(method, effectiveDepth, effectiveMax, visitedCallees)
                    append(",\"callers\":[$callers]")
                    append(",\"callees\":[$callees]")
                }
                else -> throw IllegalArgumentException("Invalid direction: $direction. Use 'callers', 'callees', or 'both'")
            }

            append("}")
        }
    }

    private fun findCallers(
        method: PsiMethod,
        depth: Int,
        maxResults: Int,
        visited: MutableSet<String>
    ): String {
        if (depth <= 0) return ""

        val scope = GlobalSearchScope.projectScope(project)
        val callerNodes = mutableListOf<String>()

        // CRITICAL FIX: Use Processor returning false to actually stop iteration
        ReferencesSearch.search(method, scope).forEach(Processor { ref ->
            if (callerNodes.size >= maxResults) return@Processor false

            val callerMethod = PsiTreeUtil.getParentOfType(ref.element, PsiMethod::class.java)
            if (callerMethod != null) {
                val key = getMethodKey(callerMethod)
                if (key !in visited) {
                    visited.add(key)
                    val callerSig = PsiUtils.formatMethodSignature(callerMethod)
                    val callerFile = callerMethod.containingFile?.virtualFile?.let {
                        PsiUtils.toProjectRelativePath(project, it)
                    } ?: ""
                    val callerLine = PsiUtils.getLineNumber(ref.element)

                    val childCallers = if (depth > 1) {
                        findCallers(callerMethod, depth - 1, maxResults, visited)
                    } else ""

                    callerNodes.add(buildString {
                        append("{")
                        append("\"method\":\"${PsiUtils.jsonEscape(callerSig)}\",")
                        append("\"filePath\":\"${PsiUtils.jsonEscape(callerFile)}\",")
                        append("\"line\":$callerLine")
                        if (childCallers.isNotEmpty()) {
                            append(",\"callers\":[$childCallers]")
                        }
                        append("}")
                    })
                }
            }
            true
        })

        return callerNodes.joinToString(",")
    }

    // Sentinel exception to break out of visitor traversal
    private class MaxResultsReachedException : RuntimeException()

    private fun findCallees(
        method: PsiMethod,
        depth: Int,
        maxResults: Int,
        visited: MutableSet<String>
    ): String {
        if (depth <= 0) return ""

        val calleeNodes = mutableListOf<String>()
        val projectScope = GlobalSearchScope.projectScope(project)

        try {
            method.body?.accept(object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    if (calleeNodes.size >= maxResults) throw MaxResultsReachedException()

                    val resolvedMethod = expression.resolveMethod()
                    if (resolvedMethod != null) {
                        val containingFile = resolvedMethod.containingFile?.virtualFile
                        if (containingFile != null && projectScope.contains(containingFile)) {
                            val key = getMethodKey(resolvedMethod)
                            if (key !in visited) {
                                visited.add(key)
                                val calleeSig = PsiUtils.formatMethodSignature(resolvedMethod)
                                val calleeFile = PsiUtils.toProjectRelativePath(project, containingFile)
                                val calleeLine = PsiUtils.getLineNumber(resolvedMethod)

                                val childCallees = if (depth > 1) {
                                    findCallees(resolvedMethod, depth - 1, maxResults, visited)
                                } else ""

                                calleeNodes.add(buildString {
                                    append("{")
                                    append("\"method\":\"${PsiUtils.jsonEscape(calleeSig)}\",")
                                    append("\"filePath\":\"${PsiUtils.jsonEscape(calleeFile)}\",")
                                    append("\"line\":$calleeLine")
                                    if (childCallees.isNotEmpty()) {
                                        append(",\"callees\":[$childCallees]")
                                    }
                                    append("}")
                                })
                            }
                        }
                    }
                    super.visitMethodCallExpression(expression)
                }
            })
        } catch (_: MaxResultsReachedException) {
            // Expected: visitor stopped after reaching maxResults
        }

        return calleeNodes.joinToString(",")
    }

    private fun getMethodKey(method: PsiMethod): String {
        val className = method.containingClass?.qualifiedName ?: method.containingClass?.name ?: ""
        val params = method.parameterList.parameters.joinToString(",") { it.type.presentableText }
        return "$className#${method.name}($params)"
    }

    // ===== Utility =====

    private fun resolveFilePath(filePath: String): String {
        if (filePath.startsWith("/") || filePath.contains(":\\") || filePath.contains(":/")) {
            return filePath.replace("\\", "/")
        }
        val projectDir = project.basePath ?: return filePath
        return "$projectDir/$filePath"
    }
}
