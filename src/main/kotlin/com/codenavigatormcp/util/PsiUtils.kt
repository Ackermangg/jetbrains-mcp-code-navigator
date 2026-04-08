package com.codenavigatormcp.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil

object PsiUtils {

    fun findClass(project: Project, className: String): PsiClass {
        return findClass(project, className, includeDependencies = true)
    }

    fun findClass(project: Project, className: String, includeDependencies: Boolean): PsiClass {
        // allScope includes Maven/Gradle dependency JARs in addition to project sources
        val scope = if (includeDependencies) {
            GlobalSearchScope.allScope(project)
        } else {
            GlobalSearchScope.projectScope(project)
        }

        // Try fully-qualified name first (works for both project classes and dependency classes)
        JavaPsiFacade.getInstance(project).findClass(className, scope)?.let { return it }

        // Fallback to short name: use project scope to avoid ambiguity across dependency JARs
        val classes = PsiShortNamesCache.getInstance(project)
            .getClassesByName(className, GlobalSearchScope.projectScope(project))
        if (classes.isNotEmpty()) return classes[0]

        throw IllegalArgumentException("Class not found: $className")
    }

    fun getLineNumber(element: PsiElement): Int {
        val document = PsiDocumentManager.getInstance(element.project)
            .getDocument(element.containingFile) ?: return -1
        val offset = element.textOffset
        // Light/synthetic elements (e.g. Lombok-generated) can return -1 for textOffset
        if (offset < 0 || offset > document.textLength) return -1
        return document.getLineNumber(offset) + 1
    }

    fun getLineRange(element: PsiElement): Pair<Int, Int> {
        val document = PsiDocumentManager.getInstance(element.project)
            .getDocument(element.containingFile) ?: return Pair(-1, -1)
        val startLine = document.getLineNumber(element.textRange.startOffset) + 1
        // Use endOffset - 1 to get the last character actually in the element (endOffset is exclusive)
        val endLine = document.getLineNumber((element.textRange.endOffset - 1).coerceAtLeast(0)) + 1
        return Pair(startLine, endLine)
    }

    fun toProjectRelativePath(project: Project, file: VirtualFile): String {
        val projectDir = project.basePath ?: return file.path
        val filePath = file.path
        return if (filePath.startsWith(projectDir)) {
            filePath.removePrefix(projectDir).removePrefix("/")
        } else {
            filePath
        }
    }

    fun isProjectFile(project: Project, file: VirtualFile?): Boolean {
        if (file == null) return false
        return GlobalSearchScope.projectScope(project).contains(file)
    }

    fun getSourceType(project: Project, file: VirtualFile?): String {
        return if (isProjectFile(project, file)) "project" else "dependency"
    }

    fun getModifiers(member: PsiModifierListOwner): List<String> {
        val modifiers = mutableListOf<String>()
        val modifierList = member.modifierList ?: return modifiers
        for (modifier in listOf(
            PsiModifier.PUBLIC, PsiModifier.PROTECTED, PsiModifier.PRIVATE,
            PsiModifier.STATIC, PsiModifier.FINAL, PsiModifier.ABSTRACT,
            PsiModifier.SYNCHRONIZED, PsiModifier.VOLATILE, PsiModifier.TRANSIENT,
            PsiModifier.NATIVE, PsiModifier.DEFAULT
        )) {
            if (modifierList.hasModifierProperty(modifier)) {
                modifiers.add(modifier)
            }
        }
        return modifiers
    }

    fun formatMethodSignature(method: PsiMethod): String {
        val className = method.containingClass?.name ?: "Unknown"
        val params = method.parameterList.parameters.joinToString(", ") { it.type.presentableText }
        return "$className.${method.name}($params)"
    }

    fun getSymbolKind(element: PsiElement): String = when (element) {
        is PsiClass -> "class"
        is PsiMethod -> "method"
        is PsiField -> "field"
        is PsiParameter -> "parameter"
        else -> "unknown"
    }

    fun getContainingClassName(element: PsiElement): String? = when (element) {
        is PsiClass -> element.qualifiedName ?: element.name
        is PsiMember -> element.containingClass?.qualifiedName ?: element.containingClass?.name
        is PsiParameter -> PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            ?.containingClass?.qualifiedName
        else -> null
    }

    fun getQualifiedName(element: PsiNamedElement): String? {
        return when (element) {
            is PsiClass -> element.qualifiedName ?: element.name
            is PsiMethod -> {
                val containingClass = getContainingClassName(element) ?: return null
                val params = element.parameterList.parameters.joinToString(",") { it.type.presentableText }
                "$containingClass#${element.name}($params)"
            }
            is PsiField -> {
                val containingClass = getContainingClassName(element) ?: return null
                "$containingClass#${element.name}"
            }
            else -> element.name
        }
    }

    fun getElementSignature(element: PsiNamedElement): String = when (element) {
        is PsiMethod -> {
            val modifiers = getModifiers(element).joinToString(" ")
            val returnType = element.returnType?.presentableText ?: ""
            val params = element.parameterList.parameters.joinToString(", ") {
                "${it.type.presentableText} ${it.name}"
            }
            listOfNotNull(
                modifiers.ifEmpty { null },
                returnType.ifEmpty { null },
                "${element.name}($params)"
            ).joinToString(" ")
        }
        is PsiField -> "${element.type.presentableText} ${element.name}"
        is PsiClass -> element.qualifiedName ?: element.name ?: ""
        is PsiParameter -> "${element.type.presentableText} ${element.name}".trim()
        else -> element.name ?: ""
    }

    fun buildSymbolCandidateJson(project: Project, element: PsiNamedElement): String {
        val psiElement = element as PsiElement
        val file = psiElement.containingFile?.virtualFile
        val filePath = file?.let { toProjectRelativePath(project, it) } ?: ""
        val line = getLineNumber(psiElement)
        val symbolKind = getSymbolKind(psiElement)
        val containingClass = getContainingClassName(psiElement) ?: ""
        val qualifiedName = getQualifiedName(element) ?: ""
        val signature = getElementSignature(element)
        val sourceType = getSourceType(project, file)

        return buildString {
            append("{")
            append("\"symbolKind\":\"${jsonEscape(symbolKind)}\",")
            append("\"name\":\"${jsonEscape(element.name ?: "")}\",")
            append("\"containingClass\":\"${jsonEscape(containingClass)}\",")
            append("\"qualifiedName\":\"${jsonEscape(qualifiedName)}\",")
            append("\"signature\":\"${jsonEscape(signature)}\",")
            append("\"filePath\":\"${jsonEscape(filePath)}\",")
            append("\"line\":$line,")
            append("\"sourceType\":\"$sourceType\"")
            append("}")
        }
    }

    fun extractJavadocSummary(docComment: PsiDocComment): String? {
        val descriptionElements = docComment.descriptionElements
        val text = descriptionElements.joinToString("") { it.text }.trim()
        if (text.isEmpty()) return null
        // Return first sentence
        val firstSentence = text.split(Regex("[.。]")).firstOrNull()?.trim()
        return firstSentence?.ifEmpty { null }
    }

    fun jsonEscape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace(Regex("[\\u0000-\\u001f]")) { String.format("\\u%04x", it.value[0].code) }
}
