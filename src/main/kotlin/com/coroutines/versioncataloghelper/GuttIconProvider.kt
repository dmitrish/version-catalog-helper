package com.coroutines.versioncataloghelper

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class VersionCatalogLineMarkerProvider : LineMarkerProvider {

    private val icon = IconLoader.getIcon("/icons/catalog-search.svg", javaClass)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val file = element.containingFile ?: return null

        // Only show in TOML files
        if (!file.name.endsWith(".toml")) return null

        // Only show in [versions] section
        val offset = element.textRange.startOffset
        if (!TomlParser.isInVersionsSection(file, offset)) return null

        // Only show on lines that define versions (key = "value" pattern)
        val line = getLine(file, offset)
        if (!line.matches("""^\s*[\w\-\.]+\s*=\s*["'].*["']\s*$""".toRegex())) {
            return null
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { "Search Maven repositories" },
            { event, elt ->
                // Open search dialog
                val dialog = LibrarySearchDialog(elt.project!!)
                dialog.show()
            },
            GutterIconRenderer.Alignment.RIGHT
        ) { "Search Maven" }
    }

    private fun getLine(file: PsiFile, offset: Int): String {
        val text = file.text
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).takeIf { it != -1 } ?: text.length
        return text.substring(lineStart, lineEnd)
    }
}