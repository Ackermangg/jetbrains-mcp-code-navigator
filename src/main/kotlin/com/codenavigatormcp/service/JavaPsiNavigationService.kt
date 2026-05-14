package com.codenavigatormcp.service

import com.codenavigatormcp.util.PsiUtils
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.changes.ChangeListManager
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

        throw IllegalArgumentException(PsiUtils.buildAmbiguousClassMessage(project, symbolName, allCandidates))
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

    // ===== SymbolContext =====

    fun getSymbolContext(
        className: String?,
        methodName: String?,
        parameterTypes: List<String>?,
        filePath: String?,
        line: Int?,
        column: Int?,
        includeImports: Boolean,
        includeFields: Boolean,
        includeBody: Boolean,
        nearbyLines: Int,
        maxFields: Int?,
        excludeLombokGenerated: Boolean
    ): String = runReadAction {
        val targetClass = when {
            className != null -> PsiUtils.findClass(project, className)
            filePath != null && line != null -> findClassAtPosition(filePath, line, column ?: 1)
            else -> throw IllegalArgumentException("operation=symbol_context requires either className or filePath+line")
        }

        val targetMethod = when {
            methodName != null -> {
                var methods = targetClass.findMethodsByName(methodName, false).toList()
                if (methods.isEmpty() && methodName == targetClass.name) {
                    methods = targetClass.constructors.toList()
                }
                if (methods.isEmpty()) {
                    throw IllegalArgumentException("Method not found: $methodName in ${targetClass.qualifiedName ?: targetClass.name}")
                }
                if (parameterTypes != null) {
                    resolveMethodOverloads(methodName, methods, parameterTypes).firstOrNull()
                } else {
                    if (methods.size > 1) {
                        throw IllegalArgumentException(buildSymbolContextOverloadError(methodName, methods))
                    }
                    methods.firstOrNull()
                }
            }
            filePath != null && line != null -> findMethodAtPosition(filePath, line, column ?: 1)
            else -> null
        }

        val containingFile = targetClass.containingFile
        val virtualFile = containingFile.virtualFile
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
        val effectiveNearbyLines = nearbyLines.coerceIn(0, 200)
        val sourceLines = if (document != null) {
            buildNearbyLinesJson(document, targetMethod ?: targetClass, effectiveNearbyLines)
        } else {
            "[]"
        }

        val importsJson = if (includeImports) {
            (containingFile as? PsiJavaFile)?.importList?.allImportStatements
                ?.joinToString(",") { "\"${PsiUtils.jsonEscape(it.text)}\"" } ?: ""
        } else {
            null
        }

        val allFields = targetClass.fields.toList()
        val returnedFields = maxFields?.coerceIn(0, 200)?.let { allFields.take(it) } ?: allFields
        val fieldsJson = if (includeFields) {
            returnedFields.joinToString(",") { field ->
                val modifiers = PsiUtils.getModifiers(field).joinToString(",") { "\"$it\"" }
                "{\"name\":\"${PsiUtils.jsonEscape(field.name)}\",\"type\":\"${PsiUtils.jsonEscape(field.type.presentableText)}\",\"modifiers\":[$modifiers]}"
            }
        } else {
            null
        }

        val methodJson = targetMethod?.let { method ->
            if (excludeLombokGenerated && isLombokGeneratedMethod(targetClass, method)) {
                buildString {
                    append("{")
                    append("\"name\":\"${PsiUtils.jsonEscape(method.name)}\",")
                    append("\"excluded\":true,")
                    append("\"reason\":\"lombokGeneratedLike\"")
                    append("}")
                }
            } else {
                buildMethodJson(method, targetClass, includeBody, includeJavadoc = true)
            }
        }

        buildString {
            append("{")
            append("\"operation\":\"symbol_context\",")
            append("\"className\":\"${PsiUtils.jsonEscape(targetClass.qualifiedName ?: targetClass.name ?: "")}\",")
            append("\"filePath\":\"${PsiUtils.jsonEscape(virtualFile?.let { PsiUtils.toProjectRelativePath(project, it) } ?: "")}\",")
            append("\"moduleName\":\"${PsiUtils.jsonEscape(PsiUtils.getModuleName(targetClass))}\",")
            append("\"nearbyLines\":$sourceLines")
            if (importsJson != null) {
                append(",\"imports\":[$importsJson]")
            }
            if (fieldsJson != null) {
                append(",\"fields\":[$fieldsJson],")
                append("\"returnedFields\":${returnedFields.size},")
                append("\"totalFields\":${allFields.size},")
                append("\"hasMoreFields\":${returnedFields.size < allFields.size}")
            }
            if (methodJson != null) {
                append(",\"method\":$methodJson")
            }
            append("}")
        }
    }

    private fun buildSymbolContextOverloadError(methodName: String, methods: List<PsiMethod>): String {
        val candidates = methods.joinToString("; ") { method ->
            val (startLine, endLine) = PsiUtils.getLineRange(method)
            "${buildSignatureString(method)} [lineRange=$startLine-$endLine]"
        }
        return "Multiple overloads of '$methodName' matched. Pass parameterTypes to operation=symbol_context. Candidates: $candidates"
    }

    private fun findClassAtPosition(filePath: String, line: Int, column: Int): PsiClass {
        val element = findElementAtPosition(filePath, line, column)
        return PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
            ?: throw IllegalArgumentException("No Java class at $filePath:$line:$column")
    }

    private fun findMethodAtPosition(filePath: String, line: Int, column: Int): PsiMethod? {
        val element = findElementAtPosition(filePath, line, column)
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
    }

    private fun findElementAtPosition(filePath: String, line: Int, column: Int): PsiElement {
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
        val offset = (lineStartOffset + (column - 1)).coerceIn(lineStartOffset, lineEndOffset)
        return psiFile.findElementAt(offset)
            ?: throw IllegalArgumentException("No element at $filePath:$line:$column")
    }

    private fun buildNearbyLinesJson(document: com.intellij.openapi.editor.Document, element: PsiElement, nearbyLines: Int): String {
        val (startLine, endLine) = PsiUtils.getLineRange(element)
        val centerStart = if (startLine > 0) startLine else 1
        val centerEnd = if (endLine > 0) endLine else centerStart
        val fromLine = (centerStart - nearbyLines).coerceAtLeast(1)
        val toLine = (centerEnd + nearbyLines).coerceAtMost(document.lineCount)
        return (fromLine..toLine).joinToString(",", prefix = "[", postfix = "]") { lineNumber ->
            val startOffset = document.getLineStartOffset(lineNumber - 1)
            val endOffset = document.getLineEndOffset(lineNumber - 1)
            val text = document.text.substring(startOffset, endOffset)
            "{\"line\":$lineNumber,\"text\":\"${PsiUtils.jsonEscape(text)}\"}"
        }
    }

    // ===== Diagnostics =====

    fun getDiagnostics(
        filePath: String?,
        filePaths: List<String>?,
        moduleName: String?,
        changedOnly: Boolean,
        maxResults: Int
    ): String = runReadAction {
        val effectiveMax = maxResults.coerceIn(1, 500)
        val selection = collectDiagnosticFiles(filePath, filePaths, moduleName, changedOnly, effectiveMax)
        val virtualFiles = selection.files
        val problems = mutableListOf<String>()
        var hasMore = false

        for (virtualFile in virtualFiles) {
            if (problems.size >= effectiveMax) {
                hasMore = true
                break
            }
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: continue
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            try {
                psiFile.accept(object : JavaRecursiveElementWalkingVisitor() {
                    override fun visitErrorElement(element: PsiErrorElement) {
                        if (problems.size >= effectiveMax) {
                            hasMore = true
                            throw DiagnosticLimitReachedException()
                        }
                        val offset = element.textOffset
                        val line = if (document != null && offset >= 0 && offset <= document.textLength) {
                            document.getLineNumber(offset) + 1
                        } else {
                            -1
                        }
                        problems.add(buildString {
                            append("{")
                            append("\"severity\":\"ERROR\",")
                            append("\"filePath\":\"${PsiUtils.jsonEscape(PsiUtils.toProjectRelativePath(project, virtualFile))}\",")
                            append("\"line\":$line,")
                            append("\"description\":\"${PsiUtils.jsonEscape(element.errorDescription)}\"")
                            append("}")
                        })
                        if (problems.size >= effectiveMax) {
                            hasMore = true
                            throw DiagnosticLimitReachedException()
                        }
                    }
                })
            } catch (_: DiagnosticLimitReachedException) {
                break
            }
        }

        val missingFilesJson = selection.missingPaths.joinToString(",") { "\"${PsiUtils.jsonEscape(it)}\"" }
        val skippedFilesJson = selection.skippedPaths.joinToString(",") { "\"${PsiUtils.jsonEscape(it)}\"" }
        val inputProblemCount = selection.missingPaths.size + selection.skippedPaths.size

        buildString {
            append("{")
            append("\"operation\":\"diagnostics\",")
            append("\"diagnosticSource\":\"java_psi_parse_errors\",")
            append("\"scope\":\"${PsiUtils.jsonEscape(describeDiagnosticScope(filePath, filePaths, moduleName, changedOnly))}\",")
            append("\"checkedFiles\":${virtualFiles.size},")
            append("\"returnedCount\":${problems.size},")
            append("\"maxResults\":$effectiveMax,")
            append("\"hasMore\":$hasMore,")
            append("\"inputValid\":${inputProblemCount == 0},")
            append("\"inputProblemCount\":$inputProblemCount,")
            append("\"missingFiles\":[$missingFilesJson],")
            append("\"skippedFiles\":[$skippedFilesJson],")
            append("\"problems\":[${problems.joinToString(",")}]")
            append("}")
        }
    }

    private data class DiagnosticFileSelection(
        val files: List<VirtualFile>,
        val missingPaths: List<String> = emptyList(),
        val skippedPaths: List<String> = emptyList()
    )

    private fun collectDiagnosticFiles(
        filePath: String?,
        filePaths: List<String>?,
        moduleName: String?,
        changedOnly: Boolean,
        fileLimit: Int
    ): DiagnosticFileSelection {
        val explicitPaths = buildList {
            if (filePath != null) add(filePath)
            if (filePaths != null) addAll(filePaths)
        }
        if (explicitPaths.isNotEmpty()) {
            val files = mutableListOf<VirtualFile>()
            val missingPaths = mutableListOf<String>()
            val skippedPaths = mutableListOf<String>()
            explicitPaths.forEach { path ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolveFilePath(path))
                when {
                    virtualFile == null -> missingPaths.add(path)
                    virtualFile.extension != "java" -> skippedPaths.add(path)
                    else -> files.add(virtualFile)
                }
            }
            return DiagnosticFileSelection(
                files = files.distinctBy { it.path },
                missingPaths = missingPaths,
                skippedPaths = skippedPaths
            )
        }

        if (changedOnly) {
            val changeListManager = ChangeListManager.getInstance(project)
            val fileStatusManager = FileStatusManager.getInstance(project)
            return DiagnosticFileSelection(changeListManager.affectedFiles
                .asSequence()
                .filter { it.extension == "java" }
                .filter { fileStatusManager.getStatus(it) != FileStatus.NOT_CHANGED }
                .distinctBy { it.path }
                .take(fileLimit)
                .toList())
        }

        if (moduleName != null) {
            val module = ModuleManager.getInstance(project).modules.firstOrNull { it.name == moduleName }
                ?: throw IllegalArgumentException("Module not found: $moduleName")
            val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots.toList()
            return DiagnosticFileSelection(collectJavaFiles(sourceRoots, fileLimit).distinctBy { it.path })
        }

        throw IllegalArgumentException("operation=diagnostics requires filePath, filePaths, moduleName, or changedOnly=true")
    }

    private fun collectJavaFiles(roots: List<VirtualFile>, fileLimit: Int): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        fun visit(file: VirtualFile) {
            if (files.size >= fileLimit) return
            if (file.isDirectory) {
                file.children.forEach(::visit)
            } else if (file.extension == "java") {
                files.add(file)
            }
        }
        roots.forEach(::visit)
        return files
    }

    private fun describeDiagnosticScope(
        filePath: String?,
        filePaths: List<String>?,
        moduleName: String?,
        changedOnly: Boolean
    ): String {
        return when {
            filePath != null || !filePaths.isNullOrEmpty() -> "files"
            changedOnly -> "changed_files"
            moduleName != null -> "module:$moduleName"
            else -> "unspecified"
        }
    }

    // ===== MapStructMappings =====

    fun getMapperMappings(className: String): String = runReadAction {
        val psiClass = PsiUtils.findClass(project, className)
        val isMapper = hasExactAnnotation(psiClass, "org.mapstruct.Mapper")
        val methods = psiClass.methods.toList()
        val methodJson = methods.joinToString(",") { method -> buildMapperMethodJson(method) }

        buildString {
            append("{")
            append("\"operation\":\"mapper_mappings\",")
            append("\"className\":\"${PsiUtils.jsonEscape(psiClass.qualifiedName ?: psiClass.name ?: className)}\",")
            append("\"moduleName\":\"${PsiUtils.jsonEscape(PsiUtils.getModuleName(psiClass))}\",")
            append("\"mapStructMapper\":$isMapper,")
            append("\"generatedImplementationChecked\":false,")
            append("\"note\":\"Static annotation summary only; run Maven or IDE build to validate generated MapStruct implementation and Lombok-generated accessors.\",")
            append("\"methods\":[$methodJson]")
            append("}")
        }
    }

    private fun buildMapperMethodJson(method: PsiMethod): String {
        val mappingAnnotations = method.annotations.filter { isMapStructMappingAnnotation(it) }
        val flattenedMappings = mappingAnnotations.flatMap { flattenMapStructAnnotation(it) }
        val mappingsJson = flattenedMappings.joinToString(",")
        return buildString {
            append("{")
            append("\"name\":\"${PsiUtils.jsonEscape(method.name)}\",")
            append("\"signature\":\"${PsiUtils.jsonEscape(buildSignatureString(method))}\",")
            append("\"returnType\":\"${PsiUtils.jsonEscape(method.returnType?.presentableText ?: "void")}\",")
            append("\"parameters\":[")
            append(method.parameterList.parameters.joinToString(",") {
                "{\"name\":\"${PsiUtils.jsonEscape(it.name)}\",\"type\":\"${PsiUtils.jsonEscape(it.type.presentableText)}\"}"
            })
            append("],")
            append("\"mappingAnnotationCount\":${flattenedMappings.size},")
            append("\"mappings\":[$mappingsJson]")
            append("}")
        }
    }

    private fun isMapStructMappingAnnotation(annotation: PsiAnnotation): Boolean {
        return annotation.qualifiedName in setOf(
            "org.mapstruct.Mapping",
            "org.mapstruct.Mappings",
            "org.mapstruct.BeanMapping"
        )
    }

    private fun flattenMapStructAnnotation(annotation: PsiAnnotation): List<String> {
        return when (annotation.qualifiedName) {
            "org.mapstruct.Mappings" -> {
                val value = annotation.findAttributeValue("value")
                when (value) {
                    is PsiArrayInitializerMemberValue -> value.initializers
                        .filterIsInstance<PsiAnnotation>()
                        .filter { it.qualifiedName == "org.mapstruct.Mapping" }
                        .map { buildMapStructAnnotationJson(it) }
                    is PsiAnnotation -> if (value.qualifiedName == "org.mapstruct.Mapping") {
                        listOf(buildMapStructAnnotationJson(value))
                    } else {
                        emptyList()
                    }
                    else -> listOf(buildMapStructAnnotationJson(annotation))
                }
            }
            else -> listOf(buildMapStructAnnotationJson(annotation))
        }
    }

    private fun buildMapStructAnnotationJson(annotation: PsiAnnotation): String {
        val attributes = annotation.parameterList.attributes.joinToString(",") { attribute ->
            val name = attribute.name ?: "value"
            "\"${PsiUtils.jsonEscape(name)}\":\"${PsiUtils.jsonEscape(unquoteAnnotationValue(attribute.value?.text ?: ""))}\""
        }
        val annotationName = annotation.qualifiedName?.substringAfterLast('.')
            ?: annotation.nameReferenceElement?.referenceName
            ?: ""
        return buildString {
            append("{")
            append("\"annotation\":\"${PsiUtils.jsonEscape(annotationName)}\",")
            append("\"attributes\":{$attributes}")
            append("}")
        }
    }

    private fun unquoteAnnotationValue(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun hasExactAnnotation(owner: PsiModifierListOwner, qualifiedName: String): Boolean {
        return owner.annotations.any { it.qualifiedName == qualifiedName }
    }

    private class DiagnosticLimitReachedException : RuntimeException()

    // ===== GetClassStructure =====

    fun getClassStructure(
        className: String,
        includeFields: Boolean,
        includeMethods: Boolean,
        includeInherited: Boolean,
        maxFields: Int?,
        maxMethods: Int?,
        methodNamePattern: String?,
        methodVisibility: String?,
        excludeSynthetic: Boolean,
        excludeLombokGenerated: Boolean
    ): String = runReadAction {
        val psiClass = PsiUtils.findClass(project, className)
        val filePath = psiClass.containingFile?.virtualFile?.let {
            PsiUtils.toProjectRelativePath(project, it)
        } ?: ""

        val parts = mutableListOf<String>()
        parts.add("\"className\":\"${PsiUtils.jsonEscape(psiClass.qualifiedName ?: psiClass.name ?: "")}\"")
        parts.add("\"filePath\":\"${PsiUtils.jsonEscape(filePath)}\"")
        parts.add("\"moduleName\":\"${PsiUtils.jsonEscape(PsiUtils.getModuleName(psiClass))}\"")
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
            val allFields = if (includeInherited) {
                psiClass.allFields.filter { it.containingClass?.qualifiedName != "java.lang.Object" }
            } else {
                psiClass.fields.toList()
            }
            val effectiveMaxFields = maxFields?.coerceIn(0, 500)
            val returnedFields = effectiveMaxFields?.let { allFields.take(it) } ?: allFields
            val fieldsJson = returnedFields.joinToString(",") { field ->
                val fieldAnnotations = field.annotations.joinToString(",") {
                    "\"${PsiUtils.jsonEscape(it.qualifiedName?.substringAfterLast('.') ?: "")}\""
                }
                val fieldModifiers = PsiUtils.getModifiers(field).joinToString(",") { "\"$it\"" }
                "{\"name\":\"${PsiUtils.jsonEscape(field.name)}\",\"type\":\"${PsiUtils.jsonEscape(field.type.presentableText)}\",\"modifiers\":[$fieldModifiers],\"annotations\":[$fieldAnnotations]}"
            }
            parts.add("\"fields\":[$fieldsJson]")
            parts.add("\"returnedFields\":${returnedFields.size}")
            parts.add("\"hasMoreFields\":${effectiveMaxFields != null && allFields.size > effectiveMaxFields}")
        }

        // Methods
        if (includeMethods) {
            val allMethods = if (includeInherited) {
                psiClass.allMethods.filter { it.containingClass?.qualifiedName != "java.lang.Object" }
            } else {
                psiClass.methods.toList()
            }
            val nameRegex = methodNamePattern?.let { Regex(it) }
            val visibility = methodVisibility?.lowercase()
            val filteredMethods = allMethods
                .filter { method -> nameRegex?.containsMatchIn(method.name) ?: true }
                .filter { method -> visibility == null || getMethodVisibility(method) == visibility }
                .filter { method -> !excludeSynthetic || !isSyntheticLikeMethod(method) }
                .filter { method -> !excludeLombokGenerated || !isLombokGeneratedMethod(psiClass, method) }
            val effectiveMaxMethods = maxMethods?.coerceIn(0, 500)
            val returnedMethods = effectiveMaxMethods?.let { filteredMethods.take(it) } ?: filteredMethods
            val methodsJson = returnedMethods.joinToString(",") { method -> buildClassStructureMethodJson(psiClass, method) }
            parts.add("\"methods\":[$methodsJson]")
            parts.add("\"returnedMethods\":${returnedMethods.size}")
            parts.add("\"filteredMethods\":${filteredMethods.size}")
            parts.add("\"hasMoreMethods\":${effectiveMaxMethods != null && filteredMethods.size > effectiveMaxMethods}")
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

    private fun buildClassStructureMethodJson(psiClass: PsiClass, method: PsiMethod): String {
        val (mStart, mEnd) = PsiUtils.getLineRange(method)
        val javadoc = method.docComment?.let { PsiUtils.extractJavadocSummary(it) }
        val params = method.parameterList.parameters.joinToString(",") { "\"${PsiUtils.jsonEscape(it.type.presentableText)}\"" }
        val modifiers = PsiUtils.getModifiers(method).joinToString(",") { "\"$it\"" }
        return buildString {
            append("{\"name\":\"${PsiUtils.jsonEscape(method.name)}\",")
            append("\"returnType\":\"${PsiUtils.jsonEscape(method.returnType?.presentableText ?: "void")}\",")
            append("\"parameters\":[$params],")
            append("\"modifiers\":[$modifiers],")
            append("\"visibility\":\"${getMethodVisibility(method)}\",")
            append("\"syntheticLike\":${isSyntheticLikeMethod(method)},")
            append("\"lombokGeneratedLike\":${isLombokGeneratedMethod(psiClass, method)},")
            append("\"lineRange\":{\"start\":$mStart,\"end\":$mEnd}")
            if (javadoc != null) {
                append(",\"javadoc\":\"${PsiUtils.jsonEscape(javadoc)}\"")
            }
            append("}")
        }
    }

    private fun getMethodVisibility(method: PsiMethod): String {
        return when {
            method.hasModifierProperty(PsiModifier.PUBLIC) -> "public"
            method.hasModifierProperty(PsiModifier.PROTECTED) -> "protected"
            method.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
            else -> "package"
        }
    }

    private fun isSyntheticLikeMethod(method: PsiMethod): Boolean {
        val (startLine, endLine) = PsiUtils.getLineRange(method)
        return startLine < 1 || endLine < 1 || method.containingFile?.virtualFile == null
    }

    private fun isLombokGeneratedMethod(psiClass: PsiClass, method: PsiMethod): Boolean {
        if (method.docComment != null) return false

        val (startLine, endLine) = PsiUtils.getLineRange(method)
        val hasUnreliableSourceLocation = startLine <= 1 && endLine <= 1
        if (method.isConstructor) {
            return hasUnreliableSourceLocation && hasLombokConstructorAnnotation(psiClass)
        }

        if (method.parameterList.parametersCount == 0 && method.name.startsWith("get") && method.name.length > 3) {
            return hasUnreliableSourceLocation && hasLombokGetterAnnotation(psiClass, getterFieldName(method.name))
        }
        if (method.parameterList.parametersCount == 0 && method.name.startsWith("is") && method.name.length > 2) {
            return hasUnreliableSourceLocation && hasLombokGetterAnnotation(psiClass, booleanGetterFieldName(method.name))
        }
        if (method.parameterList.parametersCount == 1 && method.name.startsWith("set") && method.name.length > 3) {
            return hasUnreliableSourceLocation && hasLombokSetterAnnotation(psiClass, setterFieldName(method.name))
        }
        return hasUnreliableSourceLocation &&
            method.name in setOf("toString", "equals", "hashCode", "canEqual") &&
            hasLombokValueLikeAnnotation(psiClass)
    }

    private fun hasLombokConstructorAnnotation(psiClass: PsiClass): Boolean {
        return PsiUtils.hasAnnotation(
            psiClass,
            setOf(
                "lombok.Data", "Data",
                "lombok.Value", "Value",
                "lombok.Builder", "Builder",
                "lombok.AllArgsConstructor", "AllArgsConstructor",
                "lombok.NoArgsConstructor", "NoArgsConstructor",
                "lombok.RequiredArgsConstructor", "RequiredArgsConstructor"
            )
        )
    }

    private fun hasLombokValueLikeAnnotation(psiClass: PsiClass): Boolean {
        return PsiUtils.hasAnnotation(
            psiClass,
            setOf(
                "lombok.Data", "Data",
                "lombok.Value", "Value"
            )
        )
    }

    private fun hasLombokGetterAnnotation(psiClass: PsiClass, fieldName: String): Boolean {
        return PsiUtils.hasAnnotation(
            psiClass,
            setOf(
                "lombok.Data", "Data",
                "lombok.Getter", "Getter",
                "lombok.Value", "Value"
            )
        ) || hasFieldAnnotation(psiClass, fieldName, setOf("lombok.Getter", "Getter"))
    }

    private fun hasLombokSetterAnnotation(psiClass: PsiClass, fieldName: String): Boolean {
        return PsiUtils.hasAnnotation(
            psiClass,
            setOf(
                "lombok.Data", "Data",
                "lombok.Setter", "Setter"
            )
        ) || hasFieldAnnotation(psiClass, fieldName, setOf("lombok.Setter", "Setter"))
    }

    private fun hasFieldAnnotation(psiClass: PsiClass, fieldName: String, annotations: Set<String>): Boolean {
        val field = psiClass.findFieldByName(fieldName, false) ?: return false
        return PsiUtils.hasAnnotation(field, annotations)
    }

    private fun getterFieldName(methodName: String): String {
        return decapitalizePropertyName(methodName.removePrefix("get"))
    }

    private fun booleanGetterFieldName(methodName: String): String {
        return decapitalizePropertyName(methodName.removePrefix("is"))
    }

    private fun setterFieldName(methodName: String): String {
        return decapitalizePropertyName(methodName.removePrefix("set"))
    }

    private fun decapitalizePropertyName(propertyName: String): String {
        if (propertyName.isEmpty()) return propertyName
        if (propertyName.length > 1 && propertyName[0].isUpperCase() && propertyName[1].isUpperCase()) {
            return propertyName
        }
        return propertyName.replaceFirstChar { it.lowercase() }
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
