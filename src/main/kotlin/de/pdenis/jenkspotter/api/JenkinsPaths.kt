package de.pdenis.jenkspotter.api

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * URL helpers. Jenkins returns absolute URLs based on its configured root URL, which may differ
 * from the URL the user configured (internal hostname, http vs https, reverse proxy). All URLs we
 * receive are therefore rebased onto the configured base before use.
 */
object JenkinsPaths {
    private val ANCHORS = listOf("/job/", "/queue/", "/view/", "/computer/", "/user/")
    private val BUILD_URL = Regex("^(.*/job/[^/]+/)(\\d+)/?$")

    fun normalizeBase(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun rebase(base: String, url: String): String {
        val normalizedBase = normalizeBase(base)
        if (url.startsWith(normalizedBase)) return withTrailingSlash(url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return withTrailingSlash(normalizedBase + url.trimStart('/'))
        }
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return withTrailingSlash(url)
        val idx = ANCHORS.map { path.indexOf(it) }.filter { it >= 0 }.minOrNull()
            ?: return withTrailingSlash(url)
        return withTrailingSlash(normalizedBase + path.substring(idx + 1))
    }

    /** "team/app/feature%2Fx" -> "job/team/job/app/job/feature%252Fx/" */
    fun jobPath(fullName: String): String =
        fullName.split('/').filter { it.isNotEmpty() }.joinToString("") { "job/${encodeSegment(it)}/" }

    fun jobUrl(base: String, fullName: String): String = normalizeBase(base) + jobPath(fullName)

    fun viewUrl(base: String, nameOrUrl: String): String {
        val value = nameOrUrl.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) return rebase(base, value)
        val segments = value.split('/').filter { it.isNotEmpty() }
        return normalizeBase(base) + segments.joinToString("") { "view/${encodeSegment(it)}/" }
    }

    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    fun isBuildUrl(url: String): Boolean = BUILD_URL.matches(url)

    fun jobUrlFromBuildUrl(buildUrl: String): String? = BUILD_URL.find(buildUrl)?.groupValues?.get(1)

    fun buildNumberFromUrl(buildUrl: String): Int? = BUILD_URL.find(buildUrl)?.groupValues?.get(2)?.toIntOrNull()

    /** Derives the Jenkins full name ("a/b") from a job or build URL. */
    fun fullNameFromUrl(url: String): String {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: url
        return Regex("/job/([^/]+)").findAll(path)
            .map { URLDecoder.decode(it.groupValues[1], StandardCharsets.UTF_8) }
            .joinToString("/")
    }

    /** Human-readable name: multibranch branch names are stored URL-encoded by Jenkins. */
    fun displayName(fullName: String): String =
        fullName.split('/').joinToString(" / ") { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrDefault(it) }

    private fun withTrailingSlash(url: String) = if (url.endsWith("/")) url else "$url/"
}
