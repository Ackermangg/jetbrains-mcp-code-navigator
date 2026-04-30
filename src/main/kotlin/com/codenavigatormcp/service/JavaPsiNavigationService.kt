package com.codenavigatormcp.service

import com.codenavigatormcp.util.PsiUtils
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
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
            resolveMethodOverloads(methodName, methods, parameterTypes)
        } else {
            methods
        }

        buildString {
            append("{")
            append("\"operation\":\"method_body\",")
            append("\"className\":\"${PsiUtils.jsonEscape(psiClass.qualifiedName ?: psiClass.name ?: className)}\",")
            append("\"methodName\":\"${PsiUtils.jsonEscape(methodName)}\",")
            append("\"returnedCount\":${targetMethods.size},")
            append("\"methods\":[${targetMethods.joinToString(",") { buildMethodJson(it, psiClass, includeBody, includeJavadoc) }}]")
            append("}")
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
                    "{\"name\":\"${PsiUtils.jsonEscape(p.name)}\",\"type\":\"${PsiUtils.jsonEscape(p.type.presentableText)}\"}"
                })
                append("]")
            }

            append("}")
        }
    }

    private fun buildSignatureString(method: PsiMethod): String {
        return PsiUtils.getElementSignature(method)
    }

    private fun resolveMethodOverloads(
        methodName: String,
        methods: List<PsiMethod>,
        parameterTypes: List<String>
    ): List<PsiMethod> {
        val exactMatches = methods.filter { method ->
            method.parameterList.parameters.map { it.type.presentableText } == parameterTypes
        }
        if (exactMatches.isNotEmpty()) return exactMatches

        val normalizedInput = parameterTypes.map(::normalizeTypeForComparison)
        val normalizedMatches = methods.filter { method ->
            method.parameterList.parameters.map { normalizeTypeForComparison(it.type.presentableText) } == normalizedInput
        }
        if (normalizedMatches.size == 1) return normalizedMatches

        val erasedInput = parameterTypes.map(::eraseGenericTypeForComparison)
        val erasedMatches = methods.filter { method ->
            method.parameterList.parameters.map { eraseGenericTypeForComparison(it.type.presentableText) } == erasedInput
        }
        if (erasedMatches.size == 1) return erasedMatches

        throw IllegalArgumentException(buildMethodMatchError(methodName, parameterTypes, methods, normalizedMatches, erasedMatches))
    }

    private fun buildMethodMatchError(
        methodName: String,
        parameterTypes: List<String>,
        methods: List<PsiMethod>,
        normalizedMatches: List<PsiMethod>,
        erasedMatches: List<PsiMethod>
    ): String {
        val availableOverloads = methods.joinToString("; ") { buildSignatureString(it) }
        val reason = when {
            normalizedMatches.size > 1 -> {
                "Multiple overloads of '$methodName' match normalized parameter types $parameterTypes"
            }
            erasedMatches.size > 1 -> {
                "Multiple overloads of '$methodName' match erased parameter types $parameterTypes"
            }
            else -> {
                "No overload of '$methodName' matches parameter types $parameterTypes"
            }
        }
        return "$reason. Available overloads: $availableOverloads. " +
            "Tip: omit parameterTypes to return all overloads, or inspect candidates with java_inspect(class_structure) / java_resolve(find_symbol) first."
    }

    private fun normalizeTypeForComparison(typeText: String): String {
        val noWhitespace = typeText.replace(Regex("\\s+"), "")
        val normalizedVarargs = noWhitespace.replace("...", "[]")
        return normalizedVarargs.replace(Regex("(?<![\\w$])(?:[A-Za-z_$][\\w$]*\\.)+([A-Za-z_$][\\w$]*)")) {
            it.groupValues[1]
        }
    }

    private fun eraseGenericTypeForComparison(typeText: String): String {
        val normalized = normalizeTypeForComparison(typeText)
        val sb = StringBuilder()
        var depth = 0
        for (ch in normalized) {
            when (ch) {
                '<' -> depth++
                '>' -> depth = (depth - 1).coerceAtLeast(0)
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString()
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
            val psiClass = resolveClassForDefinition(symbolName)
            buildDefinitionJson(psiClass)
        }
    }

    private fun resolveClassForDefinition(symbolName: String): PsiClass {
        if (symbolName.contains('.')) {
            return PsiUtils.findClass(project, symbolName)
        }

        val cache = PsiShortNamesCache.getInstance(project)
        val allCandidates = cache.getClassesByName(symbolName, GlobalSearchScope.allScope(project))
            .distinctBy { it.qualifiedName ?: it.name ?: "" }

        if (allCandidates.isEmpty()) {
            throw IllegalArgumentException("Class not found: $symbolName")
        }
        if (allCandidates.size == 1) {
            return allCandidates[0]
        }

        val formattedCandidates = allCandidates.joinToString("; ") {
            val filePath = it.containingFile?.virtualFile?.let { file -> PsiUtils.toProjectRelativePath(project, file) } ?: ""
            "${it.qualifiedName ?: it.name ?: symbolName} [$filePath]"
        }
        throw IllegalArgumentException(
            "Class name '$symbolName' is ambiguous. Candidates: $formattedCandidates. " +
                "Use a fully-qualified class name or call java_resolve with operation=find_symbol first."
        )
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
                    element.name,
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

    // ===== FindSymbol =====

    fun findSymbol(
        symbolName: String,
        symbolKinds: List<String>?,
        contextClass: String?,
        includeDependencies: Boolean,
        maxResults: Int
    ): String = runReadAction {
        val effectiveKinds = parseSymbolKinds(symbolKinds)
        val effectiveMax = maxResults.coerceIn(1, 100)
        val contextPsiClass = contextClass?.let { PsiUtils.findClass(project, it, includeDependencies) }
        val candidates = LinkedHashMap<String, PsiNamedElement>()

        if ("class" in effectiveKinds) {
            collectClassCandidates(symbolName, includeDependencies, candidates)
        }
        if ("method" in effectiveKinds) {
            collectMethodCandidates(symbolName, contextPsiClass, includeDependencies, candidates)
        }
        if ("field" in effectiveKinds) {
            collectFieldCandidates(symbolName, contextPsiClass, includeDependencies, candidates)
        }

        val sortedCandidates = candidates.values
            .sortedWith(compareByDescending<PsiNamedElement> { isExactContextMatch(it, contextPsiClass) }
                .thenByDescending { isProjectElement(it) }
                .thenBy { PsiUtils.getSymbolKind(it as PsiElement) }
                .thenBy { it.name ?: "" }
                .thenBy { PsiUtils.getQualifiedName(it) ?: "" })
        val returnedCandidates = sortedCandidates.take(effectiveMax)
        val hasMore = sortedCandidates.size > effectiveMax

        buildString {
            append("{")
            append("\"symbolName\":\"${PsiUtils.jsonEscape(symbolName)}\",")
            append("\"returnedCount\":${returnedCandidates.size},")
            append("\"maxResults\":$effectiveMax,")
            append("\"hasMore\":$hasMore,")
            append("\"candidates\":[${returnedCandidates.joinToString(",") { PsiUtils.buildSymbolCandidateJson(project, it) }}]")
            append("}")
        }
    }

    private fun parseSymbolKinds(symbolKinds: List<String>?): Set<String> {
        if (symbolKinds.isNullOrEmpty()) return setOf("class", "method", "field")
        val effectiveKinds = symbolKinds.map { it.lowercase() }.toSet()
        val invalidKinds = effectiveKinds - setOf("class", "method", "field")
        if (invalidKinds.isNotEmpty()) {
            throw IllegalArgumentException("Invalid symbolKinds: ${invalidKinds.joinToString(", ")}")
        }
        return effectiveKinds
    }

    private fun collectClassCandidates(
        symbolName: String,
        includeDependencies: Boolean,
        candidates: MutableMap<String, PsiNamedElement>
    ) {
        val classScope = if (includeDependencies) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        if (symbolName.contains('.')) {
            JavaPsiFacade.getInstance(project).findClass(symbolName, classScope)?.let { candidate ->
                candidates.putIfAbsent(PsiUtils.getQualifiedName(candidate) ?: candidate.name ?: symbolName, candidate)
            }
            return
        }

        PsiShortNamesCache.getInstance(project)
            .getClassesByName(symbolName, classScope)
            .forEach { candidate ->
                candidates.putIfAbsent(PsiUtils.getQualifiedName(candidate) ?: candidate.name ?: symbolName, candidate)
            }
    }

    private fun collectMethodCandidates(
        symbolName: String,
        contextPsiClass: PsiClass?,
        includeDependencies: Boolean,
        candidates: MutableMap<String, PsiNamedElement>
    ) {
        if (contextPsiClass != null) {
            contextPsiClass.findMethodsByName(symbolName, false).forEach { method ->
                candidates.putIfAbsent(PsiUtils.getQualifiedName(method) ?: method.name, method)
            }
            return
        }

        val scope = if (includeDependencies) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        PsiShortNamesCache.getInstance(project)
            .getMethodsByName(symbolName, scope)
            .forEach { method ->
                candidates.putIfAbsent(PsiUtils.getQualifiedName(method) ?: method.name, method)
            }
    }

    private fun collectFieldCandidates(
        symbolName: String,
        contextPsiClass: PsiClass?,
        includeDependencies: Boolean,
        candidates: MutableMap<String, PsiNamedElement>
    ) {
        if (contextPsiClass != null) {
            contextPsiClass.fields
                .filter { it.name == symbolName }
                .forEach { field ->
                    candidates.putIfAbsent(PsiUtils.getQualifiedName(field) ?: field.name, field)
                }
            return
        }

        val scope = if (includeDependencies) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        PsiShortNamesCache.getInstance(project)
            .getFieldsByName(symbolName, scope)
            .forEach { field ->
                candidates.putIfAbsent(PsiUtils.getQualifiedName(field) ?: field.name, field)
            }
    }

    private fun isExactContextMatch(element: PsiNamedElement, contextPsiClass: PsiClass?): Boolean {
        if (contextPsiClass == null) return false
        return PsiUtils.getContainingClassName(element as PsiElement) == (contextPsiClass.qualifiedName ?: contextPsiClass.name)
    }

    private fun isProjectElement(element: PsiNamedElement): Boolean {
        val psiElement = element as PsiElement
        return PsiUtils.isProjectFile(project, psiElement.containingFile?.virtualFile)
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
        var hasMore = false

        // CRITICAL FIX: Use Processor returning false to actually stop iteration
        ReferencesSearch.search(targetElement as PsiElement, searchScope).forEach(Processor { ref ->
            if (references.size >= effectiveMax) {
                hasMore = true
                return@Processor false
            }

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
            append("\"maxResults\":$effectiveMax,")
            append("\"hasMore\":$hasMore,")
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
            append("\"direction\":\"${PsiUtils.jsonEscape(direction)}\",")
            append("\"maxResultsPerLevel\":$effectiveMax,")
            append("\"limitScope\":\"per_level\"")

            when (direction) {
                "callers" -> {
                    val visited = mutableSetOf(getMethodKey(method))
                    val callers = findCallers(method, effectiveDepth, effectiveMax, visited)
                    append(",\"returnedCount\":${callers.totalReturnedCount}")
                    append(",\"topLevelReturnedCount\":${callers.topLevelReturnedCount}")
                    append(",\"hasMore\":${callers.hasMore}")
                    append(",\"hierarchy\":[$callers]")
                }
                "callees" -> {
                    val visited = mutableSetOf(getMethodKey(method))
                    val callees = findCallees(method, effectiveDepth, effectiveMax, visited)
                    append(",\"returnedCount\":${callees.totalReturnedCount}")
                    append(",\"topLevelReturnedCount\":${callees.topLevelReturnedCount}")
                    append(",\"hasMore\":${callees.hasMore}")
                    append(",\"hierarchy\":[$callees]")
                }
                "both" -> {
                    val visitedCallers = mutableSetOf(getMethodKey(method))
                    val callers = findCallers(method, effectiveDepth, effectiveMax, visitedCallers)
                    val visitedCallees = mutableSetOf(getMethodKey(method))
                    val callees = findCallees(method, effectiveDepth, effectiveMax, visitedCallees)
                    append(",\"returnedCount\":${callers.totalReturnedCount + callees.totalReturnedCount}")
                    append(",\"topLevelReturnedCount\":${callers.topLevelReturnedCount + callees.topLevelReturnedCount}")
                    append(",\"hasMore\":${callers.hasMore || callees.hasMore}")
                    append(",\"callers\":[$callers]")
                    append(",\"callees\":[$callees]")
                }
                else -> throw IllegalArgumentException("Invalid direction: $direction. Use 'callers', 'callees', or 'both'")
            }

            append("}")
        }
    }

    private data class JsonListResult(
        val json: String,
        val topLevelReturnedCount: Int,
        val totalReturnedCount: Int,
        val hasMore: Boolean
    ) {
        override fun toString(): String = json
    }

    private fun findCallers(
        method: PsiMethod,
        depth: Int,
        maxResults: Int,
        visited: MutableSet<String>
    ): JsonListResult {
        if (depth <= 0) return JsonListResult("", 0, 0, false)

        val scope = GlobalSearchScope.projectScope(project)
        val callerNodes = mutableListOf<String>()
        var totalReturnedCount = 0
        var hasMore = false

        // CRITICAL FIX: Use Processor returning false to actually stop iteration
        ReferencesSearch.search(method, scope).forEach(Processor { ref ->
            if (callerNodes.size >= maxResults) {
                hasMore = true
                return@Processor false
            }

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
                    } else JsonListResult("", 0, 0, false)
                    totalReturnedCount += 1 + childCallers.totalReturnedCount
                    hasMore = hasMore || childCallers.hasMore

                    callerNodes.add(buildString {
                        append("{")
                        append("\"method\":\"${PsiUtils.jsonEscape(callerSig)}\",")
                        append("\"filePath\":\"${PsiUtils.jsonEscape(callerFile)}\",")
                        append("\"line\":$callerLine")
                        if (childCallers.json.isNotEmpty()) {
                            append(",\"callers\":[$childCallers]")
                        }
                        append("}")
                    })
                }
            }
            true
        })

        return JsonListResult(callerNodes.joinToString(","), callerNodes.size, totalReturnedCount, hasMore)
    }

    // Sentinel exception to break out of visitor traversal
    private class MaxResultsReachedException : RuntimeException()

    private fun findCallees(
        method: PsiMethod,
        depth: Int,
        maxResults: Int,
        visited: MutableSet<String>
    ): JsonListResult {
        if (depth <= 0) return JsonListResult("", 0, 0, false)

        val calleeNodes = mutableListOf<String>()
        val projectScope = GlobalSearchScope.projectScope(project)
        var totalReturnedCount = 0
        var hasMore = false

        try {
            method.body?.accept(object : JavaRecursiveElementVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    if (calleeNodes.size >= maxResults) {
                        hasMore = true
                        throw MaxResultsReachedException()
                    }

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
                                } else JsonListResult("", 0, 0, false)
                                totalReturnedCount += 1 + childCallees.totalReturnedCount
                                hasMore = hasMore || childCallees.hasMore

                                calleeNodes.add(buildString {
                                    append("{")
                                    append("\"method\":\"${PsiUtils.jsonEscape(calleeSig)}\",")
                                    append("\"filePath\":\"${PsiUtils.jsonEscape(calleeFile)}\",")
                                    append("\"line\":$calleeLine")
                                    if (childCallees.json.isNotEmpty()) {
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

        return JsonListResult(calleeNodes.joinToString(","), calleeNodes.size, totalReturnedCount, hasMore)
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
