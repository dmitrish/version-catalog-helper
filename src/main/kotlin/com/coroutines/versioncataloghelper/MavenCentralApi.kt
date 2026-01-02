package com.coroutines.versioncataloghelper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

object MavenCentralApi {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val cache = ConcurrentHashMap<String, CachedVersions>()

    data class CachedVersions(
        val versions: List<String>,
        val timestamp: Long
    )

    data class LibraryInfo(
        val groupId: String,
        val artifactId: String,
        val latestVersion: String
    )

    fun fetchVersions(groupId: String, artifactId: String): List<String> {
        val key = "$groupId:$artifactId"
        val cached = cache[key]

        if (cached != null && System.currentTimeMillis() - cached.timestamp < 300_000) {
            println("=== USING CACHED VERSIONS for $key ===")
            return cached.versions
        }

        println("=== FETCHING VERSIONS for: $key ===")

        // Try Google Maven first for androidx/google libraries
        val versions = if (groupId.startsWith("androidx.") ||
            groupId.startsWith("com.google.") ||
            groupId.startsWith("com.android.")) {
            println("=== Trying Google Maven (androidx/google library) ===")
            fetchFromGoogleMaven(groupId, artifactId).also {
                if (it.isEmpty()) {
                    println("=== Google Maven returned nothing, trying Maven Central ===")
                }
            }.ifEmpty {
                fetchFromMavenCentral(groupId, artifactId)
            }
        } else {
            println("=== Trying Maven Central ===")
            fetchFromMavenCentral(groupId, artifactId).also {
                if (it.isEmpty()) {
                    println("=== Maven Central returned nothing, trying Google Maven ===")
                }
            }.ifEmpty {
                fetchFromGoogleMaven(groupId, artifactId)
            }
        }

        println("=== FOUND ${versions.size} VERSIONS ===")
        versions.take(5).forEach { println("  - $it") }

        if (versions.isNotEmpty()) {
            cache[key] = CachedVersions(versions, System.currentTimeMillis())
        }

        return versions
    }

    private fun fetchFromMavenCentral(groupId: String, artifactId: String): List<String> {
        try {
            val url = "https://search.maven.org/solrsearch/select?" +
                    "q=g:\"$groupId\"+AND+a:\"$artifactId\"&" +
                    "rows=50&wt=json&core=gav"

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()

            val result = json.decodeFromString<MavenSearchResponse>(body)
            return result.response.docs
                .map { it.v }
                .distinct()
                .sortedWith(VersionComparator)
                .reversed()
        } catch (e: Exception) {
            println("=== Maven Central ERROR: ${e.message} ===")
            return emptyList()
        }
    }

    private fun fetchFromGoogleMaven(groupId: String, artifactId: String): List<String> {
        try {
            val groupPath = groupId.replace('.', '/')
            val url = "https://dl.google.com/android/maven2/$groupPath/$artifactId/maven-metadata.xml"

            println("=== Google Maven URL: $url ===")

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            println("=== Google Maven Response Code: ${response.code} ===")

            if (!response.isSuccessful) {
                println("=== Google Maven FAILED: ${response.message} ===")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()

            println("=== Google Maven XML (first 200 chars): ${body.take(200)} ===")

            // Parse XML to extract versions
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(body.byteInputStream())

            val versionNodes = doc.getElementsByTagName("version")
            println("=== Found ${versionNodes.length} version nodes ===")

            val versions = mutableListOf<String>()

            for (i in 0 until versionNodes.length) {
                val version = versionNodes.item(i).textContent
                versions.add(version)
                if (i < 3) println("=== Version $i: $version ===")
            }

            return versions
                .distinct()
                .sortedWith(VersionComparator)
                .reversed()

        } catch (e: Exception) {
            println("=== Google Maven ERROR: ${e.message} ===")
            e.printStackTrace()
            return emptyList()
        }
    }

    // Smart version comparator (handles 1.10.0 > 1.9.0)
    private object VersionComparator : Comparator<String> {
        override fun compare(v1: String, v2: String): Int {
            val parts1 = v1.split(".", "-", "_")
            val parts2 = v2.split(".", "-", "_")

            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val p1 = parts1.getOrNull(i) ?: "0"
                val p2 = parts2.getOrNull(i) ?: "0"

                // Try numeric comparison first
                val n1 = p1.toIntOrNull()
                val n2 = p2.toIntOrNull()

                if (n1 != null && n2 != null) {
                    if (n1 != n2) return n1.compareTo(n2)
                } else {
                    // Alphabetic comparison for alpha/beta/rc
                    if (p1 != p2) return p1.compareTo(p2)
                }
            }
            return 0
        }
    }

    @Serializable
    data class MavenSearchResponse(
        val response: ResponseData
    )

    @Serializable
    data class ResponseData(
        val docs: List<Doc>
    )

    @Serializable
    data class Doc(
        val v: String
    )
}