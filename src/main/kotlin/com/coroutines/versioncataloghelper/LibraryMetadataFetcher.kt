package com.coroutines.versioncataloghelper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
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

        val pomUrl = getPomUrl(groupId, artifactId, version)
        val (url, description) = parsePom(pomUrl)

        val htmlContent = url?.let { fetchAndFormatHtml(it, groupId, artifactId, version) }

        return LibraryMetadata(url, description, htmlContent)
    }

    private fun getPomUrl(groupId: String, artifactId: String, version: String): String {
        val groupPath = groupId.replace('.', '/')

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
                if (!it.isSuccessful) return null to null

                val pomXml = it.body?.string() ?: return null to null

                val urlPattern = """<url>([^<]+)</url>""".toRegex()
                val url = urlPattern.find(pomXml)?.groupValues?.get(1)

                val descPattern = """<description>([^<]+)</description>""".toRegex()
                val description = descPattern.find(pomXml)?.groupValues?.get(1)

                println("=== Found URL: $url ===")

                return url to description
            }
        } catch (e: Exception) {
            println("=== Error parsing POM: ${e.message} ===")
            return null to null
        }
    }

    private fun fetchAndFormatHtml(url: String, groupId: String, artifactId: String, version: String): String {
        try {
            val finalUrl = if (groupId.startsWith("androidx.")) {
                val library = groupId.removePrefix("androidx.")
                "https://developer.android.com/jetpack/androidx/releases/$library#$version"
            } else {
                url
            }

            println("=== Fetching from: $finalUrl ===")

            val request = Request.Builder().url(finalUrl).build()
            val response = client.newCall(request).execute()

            response.use {
                if (!it.isSuccessful) return buildErrorHtml(finalUrl)

                val html = it.body?.string() ?: return buildErrorHtml(finalUrl)

                return if (finalUrl.contains("developer.android.com")) {
                    extractAndFormatAndroidX(html, finalUrl, version)
                } else {
                    // For other URLs, return a styled iframe
                    buildIframeHtml(finalUrl)
                }
            }
        } catch (e: Exception) {
            println("=== Error: ${e.message} ===")
            return buildErrorHtml(url)
        }
    }

    private fun extractAndFormatAndroidX(html: String, url: String, version: String): String {
        try {
            val doc = Jsoup.parse(html, url)

            // Find version section
            val versionHeader = doc.select("h2, h3, h4").find {
                it.text().contains(version, ignoreCase = true) ||
                        it.attr("id").contains(version, ignoreCase = true)
            }

            val content = StringBuilder()

            if (versionHeader != null) {
                content.append("<h2>${versionHeader.text()}</h2>")

                var next = versionHeader.nextElementSibling()
                while (next != null && next.tagName() !in listOf("h1", "h2")) {
                    when (next.tagName()) {
                        "p" -> content.append("<p>${next.html()}</p>")
                        "ul", "ol" -> {
                            content.append("<ul>")
                            next.select("li").forEach {
                                content.append("<li>${it.html()}</li>")
                            }
                            content.append("</ul>")
                        }
                        "h3" -> content.append("<h3>${next.text()}</h3>")
                        "h4" -> content.append("<h4>${next.text()}</h4>")
                        "pre" -> content.append("<pre><code>${next.text()}</code></pre>")
                        "table" -> content.append(next.outerHtml())
                        else -> {
                            if (next.text().isNotBlank()) {
                                content.append("<p>${next.html()}</p>")
                            }
                        }
                    }
                    next = next.nextElementSibling()
                    if (content.length > 20000) break
                }
            } else {
                // Fallback: get first relevant content
                content.append("<p><em>Version-specific notes not found. Showing general information:</em></p>")
                doc.select("article p, main p, .devsite-article-body p").take(10).forEach {
                    content.append("<p>${it.html()}</p>")
                }
            }

            return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        * {
                            margin: 0;
                            padding: 0;
                            box-sizing: border-box;
                        }
                        
                        body {
                            font-family: 'Roboto', 'Segoe UI', Arial, sans-serif;
                            background: #ffffff;
                            color: #202124;
                            padding: 24px;
                            line-height: 1.6;
                        }
                        
                        .source-link {
                            background: #e8f0fe;
                            padding: 12px 16px;
                            border-radius: 8px;
                            margin-bottom: 24px;
                            border-left: 4px solid #1a73e8;
                        }
                        
                        .source-link a {
                            color: #1a73e8;
                            text-decoration: none;
                            font-weight: 500;
                        }
                        
                        .source-link a:hover {
                            text-decoration: underline;
                        }
                        
                        .content {
                            background: #ffffff;
                            max-width: 800px;
                        }
                        
                        h2 {
                            color: #1a73e8;
                            font-size: 28px;
                            margin: 24px 0 16px 0;
                            font-weight: 500;
                        }
                        
                        h3 {
                            color: #1a73e8;
                            font-size: 20px;
                            margin: 20px 0 12px 0;
                            font-weight: 500;
                        }
                        
                        h4 {
                            color: #5f6368;
                            font-size: 16px;
                            margin: 16px 0 8px 0;
                            font-weight: 600;
                        }
                        
                        p {
                            margin: 12px 0;
                            color: #202124;
                            font-size: 14px;
                        }
                        
                        ul, ol {
                            margin: 12px 0;
                            padding-left: 32px;
                        }
                        
                        li {
                            margin: 8px 0;
                            color: #202124;
                            font-size: 14px;
                        }
                        
                        code {
                            background: #f1f3f4;
                            padding: 2px 6px;
                            border-radius: 3px;
                            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
                            font-size: 13px;
                            color: #c7254e;
                        }
                        
                        pre {
                            background: #f8f9fa;
                            padding: 16px;
                            border-radius: 4px;
                            overflow-x: auto;
                            margin: 16px 0;
                            border: 1px solid #e8eaed;
                        }
                        
                        pre code {
                            background: transparent;
                            padding: 0;
                            color: #202124;
                            font-size: 13px;
                        }
                        
                        a {
                            color: #1a73e8;
                            text-decoration: none;
                        }
                        
                        a:hover {
                            text-decoration: underline;
                        }
                        
                        table {
                            width: 100%;
                            border-collapse: collapse;
                            margin: 16px 0;
                            background: #ffffff;
                        }
                        
                        th, td {
                            padding: 12px;
                            text-align: left;
                            border: 1px solid #e8eaed;
                        }
                        
                        th {
                            background: #f8f9fa;
                            font-weight: 600;
                            color: #5f6368;
                        }
                        
                        tr:nth-child(even) {
                            background: #fafafa;
                        }
                        
                        em {
                            color: #5f6368;
                            font-style: italic;
                        }
                        
                        strong, b {
                            font-weight: 600;
                            color: #202124;
                        }
                    </style>
                </head>
                <body>
                    <div class="source-link">
                        📄 <strong>Documentation:</strong> <a href="$url" target="_blank">$url</a>
                    </div>
                    <div class="content">
                        $content
                    </div>
                </body>
                </html>
            """.trimIndent()
        } catch (e: Exception) {
            println("=== Error extracting AndroidX: ${e.message} ===")
            e.printStackTrace()
            return buildErrorHtml(url)
        }
    }

    private fun buildIframeHtml(url: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { 
                        margin: 0; 
                        padding: 0; 
                        background: #ffffff;
                    }
                    .header {
                        background: #e8f0fe;
                        padding: 12px 16px;
                        border-bottom: 1px solid #dadce0;
                    }
                    .header a {
                        color: #1a73e8;
                        text-decoration: none;
                        font-family: Arial, sans-serif;
                        font-size: 14px;
                    }
                    iframe { 
                        width: 100%; 
                        height: calc(100vh - 50px); 
                        border: none;
                        background: #ffffff;
                    }
                </style>
            </head>
            <body>
                <div class="header">
                    📄 <strong>External documentation:</strong> <a href="$url" target="_blank">$url</a>
                </div>
                <iframe src="$url"></iframe>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildErrorHtml(url: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        padding: 24px;
                        background: #ffffff;
                    }
                    .error {
                        background: #ffffff;
                        padding: 24px;
                        border-radius: 8px;
                        border-left: 4px solid #ea4335;
                        border: 1px solid #f8d7da;
                    }
                    h3 {
                        color: #ea4335;
                        margin-top: 0;
                    }
                    a {
                        color: #1a73e8;
                        text-decoration: none;
                    }
                    a:hover {
                        text-decoration: underline;
                    }
                </style>
            </head>
            <body>
                <div class="error">
                    <h3>⚠️ Could not load documentation</h3>
                    <p>Try opening the URL directly: <a href="$url" target="_blank">$url</a></p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}