package com.coroutines.versioncataloghelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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

    fun searchByKeywordFlow(keyword: String): Flow<MavenCentralApi.LibraryInfo> = flow {
        if (keyword.length < 3) {
            return@flow
        }

        println("=== SEARCHING for keyword: $keyword ===")

        val emittedIds = mutableSetOf<String>()

        // 1. Search Google Maven and emit results immediately as we find them
        val patterns = buildGoogleMavenPatterns(keyword)

        for ((groupId, artifactId) in patterns) {
            val lib = tryFetchGoogleMaven(groupId, artifactId)
            if (lib != null) {
                val id = "${lib.groupId}:${lib.artifactId}"
                if (!emittedIds.contains(id)) {
                    emittedIds.add(id)
                    emit(lib)  // Emit immediately!
                    println("=== Emitted: $id ===")
                }
            }
        }

        // 2. Search Maven Central and emit as we find them
        val mavenResults = withContext(Dispatchers.IO) {
            searchMavenCentral(keyword)
        }

        val scored = mavenResults.map { lib ->
            lib to calculateRelevanceScore(lib, keyword)
        }.sortedByDescending { it.second }

        scored.forEach { (lib, score) ->
            val id = "${lib.groupId}:${lib.artifactId}"
            if (!emittedIds.contains(id)) {
                emittedIds.add(id)
                emit(lib)  // Emit immediately!
                println("=== Emitted: $id (score: $score) ===")
            }
        }
    }

    private fun buildGoogleMavenPatterns(keyword: String): List<Pair<String, String>> {
        val patterns = mutableListOf<Pair<String, String>>()

        patterns.add("androidx.$keyword" to keyword)
        patterns.add("androidx.$keyword" to "$keyword-runtime")
        patterns.add("androidx.$keyword" to "$keyword-ktx")
        patterns.add("androidx.$keyword" to "$keyword-runtime-ktx")
        patterns.add("androidx.$keyword" to "$keyword-viewmodel")
        patterns.add("androidx.$keyword" to "$keyword-livedata")
        patterns.add("androidx.$keyword" to "$keyword-common")
        patterns.add("androidx.compose.$keyword" to keyword)
        patterns.add("androidx.compose.$keyword" to "$keyword-android")
        patterns.add("com.google.android.$keyword" to keyword)
        patterns.add("com.google.$keyword" to keyword)

        when (keyword.lowercase()) {
            "lifecycle" -> {
                patterns.add(0, "androidx.lifecycle" to "lifecycle-runtime-ktx")
                patterns.add(1, "androidx.lifecycle" to "lifecycle-viewmodel-ktx")
                patterns.add(2, "androidx.lifecycle" to "lifecycle-livedata-ktx")
            }
            "room" -> {
                patterns.add(0, "androidx.room" to "room-runtime")
                patterns.add(1, "androidx.room" to "room-ktx")
            }
            "navigation" -> {
                patterns.add(0, "androidx.navigation" to "navigation-fragment-ktx")
                patterns.add(1, "androidx.navigation" to "navigation-ui-ktx")
            }
            "compose" -> {
                patterns.add(0, "androidx.compose.ui" to "ui")
                patterns.add(1, "androidx.compose.material3" to "material3")
                patterns.add(2, "androidx.compose.runtime" to "runtime")
            }
            "glance" -> {
                patterns.add(0, "androidx.glance" to "glance")
                patterns.add(1, "androidx.glance" to "glance-appwidget")
            }
        }

        return patterns
    }

    private fun tryFetchGoogleMaven(groupId: String, artifactId: String): MavenCentralApi.LibraryInfo? {
        try {
            val groupPath = groupId.replace('.', '/')
            val url = "https://dl.google.com/android/maven2/$groupPath/$artifactId/maven-metadata.xml"

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return null
                    val latestVersion = extractLatestVersion(body)

                    if (latestVersion != null) {
                        println("=== FOUND on Google Maven: $groupId:$artifactId - $latestVersion ===")
                        return MavenCentralApi.LibraryInfo(
                            groupId = groupId,
                            artifactId = artifactId,
                            latestVersion = latestVersion
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
        return null
    }

    private fun extractLatestVersion(xml: String): String? {
        var pattern = """<latest>([^<]+)</latest>""".toRegex()
        var match = pattern.find(xml)
        if (match != null) {
            return match.groupValues[1]
        }

        pattern = """<release>([^<]+)</release>""".toRegex()
        match = pattern.find(xml)
        return match?.groupValues?.get(1)
    }

    private fun searchMavenCentral(keyword: String): List<MavenCentralApi.LibraryInfo> {
        val results = mutableListOf<MavenCentralApi.LibraryInfo>()

        results.addAll(searchMavenCentralWithQuery(keyword))

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

    private fun calculateRelevanceScore(lib: MavenCentralApi.LibraryInfo, keyword: String): Int {
        var score = 0
        val keywordLower = keyword.lowercase()
        val groupLower = lib.groupId.lowercase()
        val artifactLower = lib.artifactId.lowercase()

        if (artifactLower == keywordLower) score += 100
        if (artifactLower.startsWith(keywordLower)) score += 50
        if (artifactLower.contains(keywordLower)) score += 25
        if (groupLower.contains(keywordLower)) score += 20

        if (groupLower == "androidx.$keywordLower" ||
            groupLower == "com.google.$keywordLower" ||
            groupLower == "com.google.android.$keywordLower") {
            score += 150
        }

        if (groupLower.startsWith("androidx.")) score += 30
        if (groupLower.startsWith("com.google.")) score += 20

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