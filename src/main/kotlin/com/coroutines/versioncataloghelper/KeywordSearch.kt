package com.coroutines.versioncataloghelper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object KeywordSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun searchByKeyword(keyword: String): List<MavenCentralApi.LibraryInfo> {
        if (keyword.length < 2) {
            println("=== Keyword too short: $keyword ===")
            return emptyList()
        }

        println("=== SEARCHING for keyword: $keyword ===")

        val allLibraries = mutableListOf<MavenCentralApi.LibraryInfo>()

        // 1. Search Google Maven with common patterns
        val googleResults = searchGoogleMaven(keyword)
        println("=== Found ${googleResults.size} results from Google Maven ===")
        allLibraries.addAll(googleResults)

        // 2. Search Maven Central
        val mavenResults = searchMavenCentral(keyword)
        println("=== Found ${mavenResults.size} results from Maven Central ===")
        allLibraries.addAll(mavenResults)

        // 3. Prioritize, deduplicate, and group
        return prioritizeAndGroup(allLibraries, keyword)
    }

    private fun searchGoogleMaven(keyword: String): List<MavenCentralApi.LibraryInfo> {
        val results = mutableListOf<MavenCentralApi.LibraryInfo>()

        // Smart patterns - try multiple common AndroidX/Google library patterns
        val patterns = mutableListOf<Pair<String, String>>()

        // Pattern 1: androidx.{keyword} (most common!)
        patterns.add("androidx.$keyword" to keyword)

        // Pattern 2: androidx.{keyword}.{keyword} (e.g., androidx.lifecycle.lifecycle-runtime)
        patterns.add("androidx.$keyword" to "$keyword-runtime")
        patterns.add("androidx.$keyword" to "$keyword-viewmodel")
        patterns.add("androidx.$keyword" to "$keyword-livedata")
        patterns.add("androidx.$keyword" to "$keyword-common")

        // Pattern 3: androidx.compose.{keyword}
        patterns.add("androidx.compose.$keyword" to keyword)
        patterns.add("androidx.compose.$keyword" to "$keyword-android")

        // Pattern 4: com.google.android.{keyword}
        patterns.add("com.google.android.$keyword" to keyword)

        // Pattern 5: com.google.{keyword}
        patterns.add("com.google.$keyword" to keyword)

        // Pattern 6: Common library name variations
        when (keyword.lowercase()) {
            "lifecycle" -> {
                patterns.add("androidx.lifecycle" to "lifecycle-runtime-ktx")
                patterns.add("androidx.lifecycle" to "lifecycle-viewmodel-ktx")
                patterns.add("androidx.lifecycle" to "lifecycle-livedata-ktx")
            }
            "room" -> {
                patterns.add("androidx.room" to "room-runtime")
                patterns.add("androidx.room" to "room-ktx")
            }
            "navigation" -> {
                patterns.add("androidx.navigation" to "navigation-fragment-ktx")
                patterns.add("androidx.navigation" to "navigation-ui-ktx")
            }
            "compose" -> {
                patterns.add("androidx.compose.ui" to "ui")
                patterns.add("androidx.compose.material3" to "material3")
                patterns.add("androidx.compose.runtime" to "runtime")
            }
            "glance" -> {
                patterns.add("androidx.glance" to "glance")
                patterns.add("androidx.glance" to "glance-appwidget")
            }
        }

        // Try each pattern
        for ((groupId, artifactId) in patterns) {
            try {
                val groupPath = groupId.replace('.', '/')
                val url = "https://dl.google.com/android/maven2/$groupPath/$artifactId/maven-metadata.xml"

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                response.use {
                    if (it.isSuccessful) {
                        val body = it.body?.string() ?: return@use
                        val latestVersion = extractLatestVersion(body)

                        if (latestVersion != null) {
                            println("=== FOUND on Google Maven: $groupId:$artifactId - $latestVersion ===")

                            results.add(
                                MavenCentralApi.LibraryInfo(
                                    groupId = groupId,
                                    artifactId = artifactId,
                                    latestVersion = latestVersion
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Silent fail - try next pattern
            }
        }

        return results
    }

    private fun extractLatestVersion(xml: String): String? {
        // Try <latest> first
        var pattern = """<latest>([^<]+)</latest>""".toRegex()
        var match = pattern.find(xml)
        if (match != null) {
            return match.groupValues[1]
        }

        // Fallback: try <release>
        pattern = """<release>([^<]+)</release>""".toRegex()
        match = pattern.find(xml)
        return match?.groupValues?.get(1)
    }

    private fun searchMavenCentral(keyword: String): List<MavenCentralApi.LibraryInfo> {
        val results = mutableListOf<MavenCentralApi.LibraryInfo>()

        // Search 1: Exact keyword
        results.addAll(searchMavenCentralWithQuery(keyword))

        // Search 2: With "androidx." prefix (in case user forgot to type it)
        if (!keyword.startsWith("androidx.") && !keyword.startsWith("com.google.")) {
            results.addAll(searchMavenCentralWithQuery("androidx.$keyword"))
            results.addAll(searchMavenCentralWithQuery("com.google.android.$keyword"))
        }

        return results
    }

    private fun searchMavenCentralWithQuery(query: String): List<MavenCentralApi.LibraryInfo> {
        try {
            val url = "https://search.maven.org/solrsearch/select?" +
                    "q=$query&" +
                    "rows=20&wt=json"

            println("=== Maven Central query: $query ===")

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            response.use {
                if (!it.isSuccessful) {
                    return emptyList()
                }

                val body = it.body?.string() ?: return emptyList()
                val result = json.decodeFromString<SearchResponse>(body)

                return result.response.docs.map { doc ->
                    MavenCentralApi.LibraryInfo(doc.g, doc.a, doc.latestVersion)
                }
            }
        } catch (e: Exception) {
            println("=== Maven Central ERROR: ${e.message} ===")
            return emptyList()
        }
    }

    private fun prioritizeAndGroup(
        libraries: List<MavenCentralApi.LibraryInfo>,
        keyword: String
    ): List<MavenCentralApi.LibraryInfo> {
        // Remove duplicates (same group:artifact)
        val unique = libraries.distinctBy { "${it.groupId}:${it.artifactId}" }

        println("=== After deduplication: ${unique.size} unique libraries ===")

        // Score each library for relevance
        val scored = unique.map { lib ->
            val score = calculateRelevanceScore(lib, keyword)
            lib to score
        }.sortedByDescending { it.second }

        // Group by vendor
        val grouped = scored.groupBy { (lib, _) ->
            when {
                lib.groupId.startsWith("androidx.") -> 0 // AndroidX first
                lib.groupId.startsWith("com.google.android.") -> 1 // Google second
                lib.groupId.startsWith("com.google.") -> 2 // Other Google third
                lib.groupId.startsWith("org.jetbrains.") ||
                        lib.groupId.startsWith("com.jetbrains.") -> 3 // JetBrains fourth
                else -> 4 // Others last
            }
        }

        // Flatten in priority order
        val result = mutableListOf<MavenCentralApi.LibraryInfo>()
        for (priority in 0..4) {
            grouped[priority]?.let { libs ->
                result.addAll(libs.map { it.first })
            }
        }

        println("=== Final results: ${result.size} libraries ===")
        result.take(15).forEach {
            println("  -> ${it.groupId}:${it.artifactId} - ${it.latestVersion}")
        }

        // Limit to top 15
        return result.take(15)
    }

    private fun calculateRelevanceScore(lib: MavenCentralApi.LibraryInfo, keyword: String): Int {
        var score = 0
        val keywordLower = keyword.lowercase()
        val groupLower = lib.groupId.lowercase()
        val artifactLower = lib.artifactId.lowercase()

        // Exact artifact match
        if (artifactLower == keywordLower) score += 100

        // Artifact starts with keyword
        if (artifactLower.startsWith(keywordLower)) score += 50

        // Artifact contains keyword
        if (artifactLower.contains(keywordLower)) score += 25

        // Group contains keyword (e.g., androidx.lifecycle)
        if (groupLower.contains(keywordLower)) score += 20

        // Exact group.keyword match (e.g., searching "lifecycle" finds "androidx.lifecycle")
        if (groupLower == "androidx.$keywordLower" ||
            groupLower == "com.google.$keywordLower" ||
            groupLower == "com.google.android.$keywordLower") {
            score += 150
        }

        // Bonus for AndroidX/Google (already prioritized, but this helps ranking within category)
        if (groupLower.startsWith("androidx.")) score += 30
        if (groupLower.startsWith("com.google.")) score += 20

        // Penalty for very long artifact names (likely not what user wants)
        if (artifactLower.length > keywordLower.length + 20) score -= 10

        return score
    }

    @Serializable
    data class SearchResponse(
        val response: ResponseData
    )

    @Serializable
    data class ResponseData(
        val docs: List<Doc>
    )

    @Serializable
    data class Doc(
        val g: String,
        val a: String,
        val latestVersion: String
    )
}