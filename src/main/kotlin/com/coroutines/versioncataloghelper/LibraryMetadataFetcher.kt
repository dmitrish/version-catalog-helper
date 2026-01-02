package com.coroutines.versioncataloghelper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.util.concurrent.TimeUnit

object LibraryMetadataFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class LibraryMetadata(
        val url: String?,
        val description: String?,
        val htmlContent: String?
    )

    fun fetchMetadata(groupId: String, artifactId: String, version: String): LibraryMetadata {
        println("=== Fetching metadata for $groupId:$artifactId:$version ===")

        // Try to get POM file first
        val pomUrl = getPomUrl(groupId, artifactId, version)
        val (url, description) = parsePom(pomUrl)

        // If URL exists, fetch its content
        val htmlContent = url?.let { fetchHtmlContent(it, groupId, artifactId, version) }

        return LibraryMetadata(url, description, htmlContent)
    }

    private fun getPomUrl(groupId: String, artifactId: String, version: String): String {
        val groupPath = groupId.replace('.', '/')

        // Try Google Maven first for androidx/google libraries
        return if (groupId.startsWith("androidx.") || groupId.startsWith("com.google.")) {
            "https://dl.google.com/android/maven2/$groupPath/$artifactId/$version/$artifactId-$version.pom"
        } else {
            "https://repo1.maven.org/maven2/$groupPath/$artifactId/$version/$artifactId-$version.pom"
        }
    }

    private fun parsePom(pomUrl: String): Pair<String?, String?> {
        try {
            println("=== Fetching POM: $pomUrl ===")

            val request = Request.Builder().url(pomUrl).build()
            val response = client.newCall(request).execute()

            response.use {
                if (!it.isSuccessful) {
                    println("=== POM fetch failed: ${it.code} ===")
                    return null to null
                }

                val pomXml = it.body?.string() ?: return null to null

                // Extract URL
                val urlPattern = """<url>([^<]+)</url>""".toRegex()
                val url = urlPattern.find(pomXml)?.groupValues?.get(1)

                // Extract description
                val descPattern = """<description>([^<]+)</description>""".toRegex()
                val description = descPattern.find(pomXml)?.groupValues?.get(1)

                println("=== Found URL: $url ===")
                println("=== Found description: $description ===")

                return url to description
            }
        } catch (e: Exception) {
            println("=== Error parsing POM: ${e.message} ===")
            return null to null
        }
    }

    private fun fetchHtmlContent(url: String, groupId: String, artifactId: String, version: String): String? {
        try {
            // Special handling for AndroidX libraries
            val finalUrl = if (groupId.startsWith("androidx.")) {
                constructAndroidXUrl(groupId, artifactId, version, url)
            } else {
                url
            }

            println("=== Fetching HTML from: $finalUrl ===")

            val request = Request.Builder().url(finalUrl).build()
            val response = client.newCall(request).execute()

            response.use {
                if (!it.isSuccessful) {
                    println("=== HTML fetch failed: ${it.code} ===")
                    return null
                }

                val html = it.body?.string() ?: return null

                // Parse and extract relevant content
                return extractRelevantContent(html, finalUrl)
            }
        } catch (e: Exception) {
            println("=== Error fetching HTML: ${e.message} ===")
            return null
        }
    }

    private fun constructAndroidXUrl(groupId: String, artifactId: String, version: String, baseUrl: String): String {
        // For AndroidX, construct release notes URL
        // Example: androidx.lifecycle → https://developer.android.com/jetpack/androidx/releases/lifecycle

        val library = groupId.removePrefix("androidx.")
        val versionMajorMinor = version.split(".").take(2).joinToString(".")

        return "https://developer.android.com/jetpack/androidx/releases/$library#$version"
    }

    private fun extractRelevantContent(html: String, url: String): String {
        try {
            val doc = Jsoup.parse(html, url)

            // For AndroidX documentation, extract the main content
            if (url.contains("developer.android.com")) {
                return extractAndroidXContent(doc, url)
            }

            // For other sites, try to find main content
            val mainContent = doc.select("article, main, .content, #content").firstOrNull()
                ?: doc.body()

            // Clean up and format
            val cleaned = Jsoup.clean(
                mainContent.html(),
                url,
                Safelist.relaxed()
                    .addTags("h1", "h2", "h3", "h4", "h5", "h6")
                    .addAttributes("a", "href")
            )

            return """
                <html>
                <head>
                    <style>
                        body { font-family: sans-serif; padding: 10px; }
                        h1, h2, h3 { color: #1a73e8; }
                        a { color: #1a73e8; }
                        code { background: #f5f5f5; padding: 2px 4px; }
                        pre { background: #f5f5f5; padding: 10px; }
                    </style>
                </head>
                <body>
                    <p><b>Source:</b> <a href="$url">$url</a></p>
                    <hr>
                    $cleaned
                </body>
                </html>
            """.trimIndent()
        } catch (e: Exception) {
            println("=== Error extracting content: ${e.message} ===")
            return "<html><body><p>Error loading content: ${e.message}</p></body></html>"
        }
    }

    private fun extractAndroidXContent(doc: org.jsoup.nodes.Document, url: String): String {
        try {
            // Try to find the specific version section
            val versionHash = url.substringAfter("#", "")

            // Look for the version header
            val versionHeader = if (versionHash.isNotEmpty()) {
                doc.select("h2, h3").find { it.text().contains(versionHash) }
            } else {
                null
            }

            // Extract content between this header and the next one
            val content = if (versionHeader != null) {
                val siblings = mutableListOf<String>()
                siblings.add("<h2>${versionHeader.text()}</h2>")

                var next = versionHeader.nextElementSibling()
                while (next != null && next.tagName() !in listOf("h1", "h2")) {
                    siblings.add(next.outerHtml())
                    next = next.nextElementSibling()
                }

                siblings.joinToString("\n")
            } else {
                // Fall back to main content area
                doc.select("article, main").firstOrNull()?.html()
                    ?: doc.select(".devsite-article-body").firstOrNull()?.html()
                    ?: "Content not found"
            }

            return """
                <html>
                <head>
                    <style>
                        body { font-family: Roboto, sans-serif; padding: 15px; font-size: 14px; }
                        h1, h2, h3 { color: #1a73e8; margin-top: 20px; }
                        h2 { font-size: 18px; }
                        h3 { font-size: 16px; }
                        a { color: #1a73e8; text-decoration: none; }
                        a:hover { text-decoration: underline; }
                        code { background: #f5f5f5; padding: 2px 6px; border-radius: 3px; font-family: monospace; }
                        pre { background: #f5f5f5; padding: 12px; border-radius: 4px; overflow-x: auto; }
                        ul, ol { padding-left: 20px; }
                        li { margin: 8px 0; }
                    </style>
                </head>
                <body>
                    <div style="background: #e3f2fd; padding: 10px; border-radius: 4px; margin-bottom: 15px;">
                        <b>📄 Documentation:</b> <a href="$url" target="_blank">$url</a>
                    </div>
                    $content
                </body>
                </html>
            """.trimIndent()
        } catch (e: Exception) {
            println("=== Error extracting AndroidX content: ${e.message} ===")
            return "<html><body><p>Error loading AndroidX documentation: ${e.message}</p></body></html>"
        }
    }
}