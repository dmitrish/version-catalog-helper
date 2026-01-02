package com.coroutines.versioncataloghelper

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TomlVersionCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    if (!parameters.originalFile.name.endsWith(".toml")) {
                        return
                    }

                    val element = parameters.position
                    val file = parameters.originalFile
                    val offset = element.textRange.startOffset

                    // Only trigger in [versions] section
                    if (!TomlParser.isInVersionsSection(file, offset)) {
                        return
                    }

                    println("=== COMPLETION IN VERSIONS SECTION ===")

                    val versionKey = TomlParser.getVersionKey(element)
                    val libraryInfo = TomlParser.findLibraryInfo(file, versionKey)

                    if (libraryInfo != null) {
                        // Found library definition - fetch versions
                        fetchAndShowVersions(libraryInfo, result)
                    } else if (versionKey != null && versionKey.length >= 3) {
                        // No library definition - search by keyword
                        searchAndSuggestLibraries(versionKey, result)
                    }
                }
            }
        )
    }

    private fun fetchAndShowVersions(
        libraryInfo: TomlParser.LibraryInfo,
        result: CompletionResultSet
    ) {
        println("=== Fetching versions for ${libraryInfo.group}:${libraryInfo.artifact} ===")

        // Run on background thread and WAIT for results (with timeout)
        val future = CompletableFuture.supplyAsync {
            try {
                MavenCentralApi.fetchVersions(libraryInfo.group, libraryInfo.artifact)
            } catch (e: Exception) {
                println("=== ERROR fetching versions: ${e.message} ===")
                emptyList()
            }
        }

        try {
            // Wait up to 5 seconds for results
            val versions = future.get(5, TimeUnit.SECONDS)

            println("=== Got ${versions.size} versions ===")

            if (versions.isEmpty()) {
                result.addElement(
                    LookupElementBuilder.create("")
                        .withPresentableText("No versions found")
                        .withTailText(" - check library name", true)
                        .withTypeText("Error")
                        .withInsertHandler { context, _ ->
                            context.document.deleteString(context.startOffset, context.tailOffset)
                        }
                )
            } else {
                versions.take(15).forEach { version ->
                    val isPreview = version.contains("alpha", ignoreCase = true) ||
                            version.contains("beta", ignoreCase = true) ||
                            version.contains("rc", ignoreCase = true) ||
                            version.contains("-M") ||
                            version.contains("SNAPSHOT", ignoreCase = true)

                    result.addElement(
                        LookupElementBuilder.create(version)
                            .withTailText(if (isPreview) " (preview)" else "", true)
                            .withTypeText("${libraryInfo.group}:${libraryInfo.artifact}")
                            .bold()
                    )
                }
            }
        } catch (e: Exception) {
            println("=== Timeout or error: ${e.message} ===")
            result.addElement(
                LookupElementBuilder.create("")
                    .withPresentableText("Search timed out")
                    .withTailText(" - try again", true)
                    .withTypeText("Error")
                    .withInsertHandler { context, _ ->
                        context.document.deleteString(context.startOffset, context.tailOffset)
                    }
            )
        }
    }

    private fun searchAndSuggestLibraries(
        keyword: String,
        result: CompletionResultSet
    ) {
        println("=== Searching for keyword: $keyword ===")

        // Run search on background thread and WAIT for results (with timeout)
        val future = CompletableFuture.supplyAsync {
            try {
                KeywordSearch.searchByKeyword(keyword)
            } catch (e: Exception) {
                println("=== ERROR searching: ${e.message} ===")
                emptyList()
            }
        }

        try {
            // Wait up to 5 seconds for results
            val libraries = future.get(5, TimeUnit.SECONDS)

            println("=== Got ${libraries.size} library suggestions ===")

            if (libraries.isEmpty()) {
                result.addElement(
                    LookupElementBuilder.create("")
                        .withPresentableText("No libraries found for '$keyword'")
                        .withTailText(" - try different term", true)
                        .withTypeText("Hint")
                        .withInsertHandler { context, _ ->
                            context.document.deleteString(context.startOffset, context.tailOffset)
                        }
                )
                return
            }

            // Group by vendor
            val grouped = libraries.groupBy { lib ->
                when {
                    lib.groupId.startsWith("androidx.") -> "AndroidX"
                    lib.groupId.startsWith("com.google.") -> "Google"
                    lib.groupId.startsWith("org.jetbrains.") ||
                            lib.groupId.startsWith("com.jetbrains.") -> "JetBrains"
                    else -> "Other"
                }
            }

            val order = listOf("AndroidX", "Google", "JetBrains", "Other")

            for (vendor in order) {
                val libs = grouped[vendor] ?: continue
                if (libs.isEmpty()) continue

                // Add vendor header
                result.addElement(
                    LookupElementBuilder.create("_header_$vendor")
                        .withPresentableText("─── $vendor ───")
                        .withTypeText("")
                        .withInsertHandler { context, _ ->
                            context.document.deleteString(context.startOffset, context.tailOffset)
                        }
                )

                // Add libraries
                libs.forEach { lib ->
                    result.addElement(
                        LookupElementBuilder.create(lib.latestVersion)
                            .withPresentableText("${lib.artifactId} ${lib.latestVersion}")
                            .withTailText(" (${lib.groupId})", true)
                            .withTypeText("Add to [libraries]")
                            .bold()
                    )
                }
            }

            println("=== Added ${libraries.size} suggestions to completion ===")

        } catch (e: Exception) {
            println("=== Timeout or error: ${e.message} ===")
            result.addElement(
                LookupElementBuilder.create("")
                    .withPresentableText("Search timed out")
                    .withTailText(" - try again", true)
                    .withTypeText("Error")
                    .withInsertHandler { context, _ ->
                        context.document.deleteString(context.startOffset, context.tailOffset)
                    }
            )
        }
    }

    override fun invokeAutoPopup(position: com.intellij.psi.PsiElement, typeChar: Char): Boolean {
        val file = position.containingFile
        if (file?.name?.endsWith(".toml") == true) {
            return typeChar in "0123456789.\""
        }
        return false
    }
}