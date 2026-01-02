package com.coroutines.versioncataloghelper

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

object TomlParser {

    data class LibraryInfo(
        val group: String,
        val artifact: String
    )

    fun findLibraryInfo(file: PsiFile, versionKey: String?): LibraryInfo? {
        if (versionKey == null) return null

        println("=== SEARCHING for library with version.ref = $versionKey ===")

        val text = file.text

        // Try Pattern 1: Standard format with group and name
        var pattern = """['"]*[\w\-\.]+['"]*\s*=\s*\{\s*group\s*=\s*["']([^"']+)["']\s*,\s*name\s*=\s*["']([^"']+)["'][^}]*version\.ref\s*=\s*["']$versionKey["']""".toRegex()
        var match = pattern.find(text)

        if (match != null) {
            val group = match.groupValues[1]
            val artifact = match.groupValues[2]
            println("=== FOUND (format 1): $group:$artifact ===")
            return LibraryInfo(group, artifact)
        }

        // Try Pattern 2: Module format
        pattern = """['"]*[\w\-\.]+['"]*\s*=\s*\{\s*module\s*=\s*["']([^:]+):([^"']+)["'][^}]*version\.ref\s*=\s*["']$versionKey["']""".toRegex()
        match = pattern.find(text)

        if (match != null) {
            val group = match.groupValues[1]
            val artifact = match.groupValues[2]
            println("=== FOUND (format 2): $group:$artifact ===")
            return LibraryInfo(group, artifact)
        }

        // Try Pattern 3: Reverse order
        pattern = """['"]*[\w\-\.]+['"]*\s*=\s*\{\s*version\.ref\s*=\s*["']$versionKey["'][^}]*group\s*=\s*["']([^"']+)["']\s*,\s*name\s*=\s*["']([^"']+)["']""".toRegex()
        match = pattern.find(text)

        if (match != null) {
            val group = match.groupValues[1]
            val artifact = match.groupValues[2]
            println("=== FOUND (format 3): $group:$artifact ===")
            return LibraryInfo(group, artifact)
        }

        // NEW: Try to find library by name similarity (missing version.ref)
        println("=== Trying smart name matching for: $versionKey ===")
        val smartMatch = findByNameSimilarity(text, versionKey)
        if (smartMatch != null) {
            println("=== FOUND by name similarity: ${smartMatch.group}:${smartMatch.artifact} ===")
            println("=== WARNING: Library is missing version.ref! ===")
            return smartMatch
        }

        println("=== NOT FOUND - will try fallback ===")
        return null
    }

    private fun findByNameSimilarity(text: String, versionKey: String): LibraryInfo? {
        // Look for libraries that contain the version key name
        // e.g., "material3" version might match "androidx-material3" library

        // Pattern: libraryName = { group = "...", name = "..." }  (no version.ref!)
        val pattern = """['"]*[\w\-\.]*${versionKey}[\w\-\.]*['"]*\s*=\s*\{\s*group\s*=\s*["']([^"']+)["']\s*,\s*name\s*=\s*["']([^"']+)["']""".toRegex()
        val match = pattern.find(text)

        if (match != null) {
            val group = match.groupValues[1]
            val artifact = match.groupValues[2]
            return LibraryInfo(group, artifact)
        }

        return null
    }

    fun getVersionKey(element: PsiElement): String? {
        val text = element.containingFile.text
        val offset = element.textRange.startOffset

        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).takeIf { it != -1 } ?: text.length
        val line = text.substring(lineStart, lineEnd)

        println("=== CURRENT LINE: $line ===")

        val patterns = listOf(
            """([\w\-\.]+)\s*=\s*["'][^"']*""".toRegex(),
            """["']([\w\-\.]+)["']\s*=\s*["'][^"']*""".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(line)
            if (match != null) {
                val key = match.groupValues[1]
                println("=== VERSION KEY: $key ===")
                return key
            }
        }

        return null
    }

    fun isInVersionsSection(file: PsiFile, offset: Int): Boolean {
        val text = file.text
        val beforeText = text.substring(0, offset)
        val lastSection = """\[(\w+)\]""".toRegex()
            .findAll(beforeText)
            .lastOrNull()
            ?.groupValues?.get(1)

        return lastSection == "versions"
    }
}