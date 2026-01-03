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

    data class SearchResult(
        val library: MavenCentralApi.LibraryInfo,
        val vendor: String
    )

    fun searchByKeywordFlow(keyword: String): Flow<SearchResult> = flow {
        if (keyword.length < 3) {
            return@flow
        }

        println("=== SEARCHING for keyword: $keyword ===")

        val emittedIds = mutableSetOf<String>()

        // PHASE 1: Google Maven - Try specific patterns first
        println("=== PHASE 1: Google Maven (specific patterns) ===")
        val patterns = buildGoogleMavenPatterns(keyword)
        for ((groupId, artifactId) in patterns) {
            val lib = tryFetchGoogleMaven(groupId, artifactId)
            if (lib != null) {
                val id = "${lib.groupId}:${lib.artifactId}"
                if (!emittedIds.contains(id)) {
                    emittedIds.add(id)
                    val vendor = if (lib.groupId.startsWith("androidx.")) "AndroidX" else "Google"
                    emit(SearchResult(lib, vendor))
                }
            }
        }

        // PHASE 2: Maven Central - Search for ALL androidx/google artifacts containing keyword
        println("=== PHASE 2: Maven Central (androidx/google with keyword) ===")
        val androidXResults = withContext(Dispatchers.IO) {
            searchMavenCentralForAndroidX(keyword)
        }

        androidXResults.forEach { lib ->
            val id = "${lib.groupId}:${lib.artifactId}"
            if (!emittedIds.contains(id)) {
                emittedIds.add(id)
                val vendor = if (lib.groupId.startsWith("androidx.")) "AndroidX" else "Google"
                emit(SearchResult(lib, vendor))
            }
        }

        // PHASE 3: JetBrains libraries
        println("=== PHASE 3: JetBrains ===")
        val jetbrainsResults = withContext(Dispatchers.IO) {
            searchMavenCentralForJetBrains(keyword)
        }

        jetbrainsResults.forEach { lib ->
            val id = "${lib.groupId}:${lib.artifactId}"
            if (!emittedIds.contains(id)) {
                emittedIds.add(id)
                emit(SearchResult(lib, "JetBrains"))
            }
        }

        // PHASE 4: Other libraries
        println("=== PHASE 4: Others ===")
        val otherResults = withContext(Dispatchers.IO) {
            searchMavenCentralGeneral(keyword)
        }

        val filteredOthers = otherResults
            .filter { lib ->
                val id = "${lib.groupId}:${lib.artifactId}"
                !emittedIds.contains(id) &&
                        !lib.groupId.startsWith("org.jetbrains.") &&
                        !lib.groupId.startsWith("com.jetbrains.") &&
                        !lib.groupId.startsWith("androidx.") &&
                        !lib.groupId.startsWith("com.google.")
            }
            .sortedByDescending { calculateRelevanceScore(it, keyword) }

        filteredOthers.forEach { lib ->
            emit(SearchResult(lib, "Other"))
        }
    }

    private fun buildGoogleMavenPatterns(keyword: String): List<Pair<String, String>> {
        val patterns = mutableListOf<Pair<String, String>>()

        // Specific shortcuts for common libraries
        when (keyword.lowercase()) {
            "lifecycle" -> {
                patterns.add("androidx.lifecycle" to "lifecycle-runtime-ktx")
                patterns.add("androidx.lifecycle" to "lifecycle-viewmodel-ktx")
                patterns.add("androidx.lifecycle" to "lifecycle-livedata-ktx")
            }
            "viewmodel" -> {
                patterns.add("androidx.lifecycle" to "lifecycle-viewmodel-ktx")
                patterns.add("androidx.lifecycle" to "lifecycle-viewmodel")
                patterns.add("androidx.lifecycle" to "lifecycle-viewmodel-compose")
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

        // Generic patterns
        patterns.add("androidx.$keyword" to keyword)
        patterns.add("androidx.$keyword" to "$keyword-ktx")
        patterns.add("androidx.$keyword" to "$keyword-runtime-ktx")
        patterns.add("androidx.compose.$keyword" to keyword)
        patterns.add("com.google.android.$keyword" to keyword)
        patterns.add("com.google.$keyword" to keyword)

        return patterns
    }

    private fun searchMavenCentralForAndroidX(keyword: String): List<MavenCentralApi.LibraryInfo> {
        val queries = listOf(
            // Search for androidx artifacts containing keyword anywhere
            "g:androidx.* AND a:*$keyword*",
            // Search for google artifacts containing keyword
            "g:com.google.android.* AND a:*$keyword*",
            "g:com.google.* AND a:*$keyword*"
        )

        val results = mutableListOf<MavenCentralApi.LibraryInfo>()
        queries.forEach { query ->
            results.addAll(searchMavenCentralWithQuery(query))
        }

        return results
            .distinctBy { "${it.groupId}:${it.artifactId}" }
            .sortedByDescending { calculateRelevanceScore(it, keyword) }
    }

    private fun searchMavenCentralForJetBrains(keyword: String): List<MavenCentralApi.LibraryInfo> {
        val queries = listOf(
            "g:org.jetbrains.* AND a:*$keyword*",
            "g:com.jetbrains.* AND a:*$keyword*"
        )

        val results = mutableListOf<MavenCentralApi.LibraryInfo>()
        queries.forEach { query ->
            results.addAll(searchMavenCentralWithQuery(query))
        }

        return results
            .distinctBy { "${it.groupId}:${it.artifactId}" }
            .filter { lib ->
                lib.groupId.startsWith("org.jetbrains.") ||
                        lib.groupId.startsWith("com.jetbrains.")
            }
            .sortedByDescending { calculateRelevanceScore(it, keyword) }
    }

    private fun searchMavenCentralGeneral(keyword: String): List<MavenCentralApi.LibraryInfo> {
        // General search - anything containing the keyword
        return searchMavenCentralWithQuery(keyword)
            .distinctBy { "${it.groupId}:${it.artifactId}" }
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
                        println("=== FOUND Google Maven: $groupId:$artifactId - $latestVersion ===")
                        return MavenCentralApi.LibraryInfo(groupId, artifactId, latestVersion)
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
        if (match != null) return match.groupValues[1]

        pattern = """<release>([^<]+)</release>""".toRegex()
        match = pattern.find(xml)
        return match?.groupValues?.get(1)
    }

    private fun searchMavenCentralWithQuery(query: String): List<MavenCentralApi.LibraryInfo> {
        try {
            val httpUrl = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("search.maven.org")
                .addPathSegments("solrsearch/select")
                .addQueryParameter("q", query)   // encoded safely
                .addQueryParameter("core", "gav")
                .addQueryParameter("rows", "100")
                .addQueryParameter("wt", "json")
                .build()

            println("=== Maven Central query: $query ===")

            val request = Request.Builder().url(httpUrl).build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    println("=== Query failed: ${it.code} ===")
                    return emptyList()
                }
                val body = it.body?.string() ?: return emptyList()
                val result = json.decodeFromString<SearchResponse>(body)
                println("=== Found ${result.response.docs.size} results for query: $query ===")
                return result.response.docs.map { doc ->
                    MavenCentralApi.LibraryInfo(doc.g, doc.a, doc.latestVersion)
                }
            }
        } catch (e: Exception) {
            println("=== Maven Central ERROR: ${e.message} ===")
            e.printStackTrace()
            return emptyList()
        }
    }
    /*private fun searchMavenCentralWithQuery(query: String): List<MavenCentralApi.LibraryInfo> {
        try {
            val url = "https://search.maven.org/solrsearch/select?" +
                    "q=$query&" +
                    "rows=100&wt=json"

            println("=== Maven Central query: $query ===")

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            response.use {
                if (!it.isSuccessful) {
                    println("=== Query failed: ${it.code} ===")
                    return emptyList()
                }

                val body = it.body?.string() ?: return emptyList()
                val result = json.decodeFromString<SearchResponse>(body)

                println("=== Found ${result.response.docs.size} results for query: $query ===")

                return result.response.docs.map { doc ->
                    MavenCentralApi.LibraryInfo(doc.g, doc.a, doc.latestVersion)
                }
            }
        } catch (e: Exception) {
            println("=== Maven Central ERROR: ${e.message} ===")
            e.printStackTrace()
            return emptyList()
        }
    } */

    private fun calculateRelevanceScore(lib: MavenCentralApi.LibraryInfo, keyword: String): Int {
        var score = 0
        val keywordLower = keyword.lowercase()
        val groupLower = lib.groupId.lowercase()
        val artifactLower = lib.artifactId.lowercase()

        // Exact matches get highest score
        if (artifactLower == keywordLower) score += 200
        if (artifactLower == "$keywordLower-ktx") score += 180
        if (artifactLower == "lifecycle-$keywordLower-ktx") score += 170
        if (artifactLower == "lifecycle-$keywordLower") score += 160

        // Starts with keyword
        if (artifactLower.startsWith(keywordLower)) score += 100
        if (artifactLower.startsWith("$keywordLower-")) score += 90

        // Contains keyword
        if (artifactLower.contains("-$keywordLower-")) score += 70
        if (artifactLower.contains("-$keywordLower")) score += 60
        if (artifactLower.contains("$keywordLower-")) score += 50
        if (artifactLower.contains(keywordLower)) score += 40

        // Group contains keyword
        if (groupLower.contains(keywordLower)) score += 30

        // Group matches
        if (groupLower == "androidx.$keywordLower") score += 150
        if (groupLower.startsWith("androidx.")) score += 40
        if (groupLower.startsWith("com.google.")) score += 30
        if (groupLower.startsWith("org.jetbrains.")) score += 20
        if (groupLower.startsWith("com.jetbrains.")) score += 20

        // Penalties
        if (artifactLower.length > keywordLower.length + 30) score -= 10

        // Bonus for common suffixes
        if (artifactLower.endsWith("-ktx")) score += 10
        if (artifactLower.endsWith("-compose")) score += 10

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