package de.pdenis.jenkspotter.ui

import de.pdenis.jenkspotter.api.BuildInfo
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.settings.BrowserUi
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** URLs of the different Jenkins web UIs, and parsing of URLs pasted from the browser. */
object JenkinsUi {
    fun buildUrl(build: BuildInfo, ui: BrowserUi): String = when {
        ui == BrowserUi.PIPELINE_GRAPH && build.isPipeline -> build.url + "pipeline-graph/"
        ui == BrowserUi.BLUE_OCEAN -> blueOcean(build)
        else -> build.url
    }

    /** /blue/organizations/jenkins/<pipeline path with %2F>/detail/<branch or job name>/<n>/pipeline */
    private fun blueOcean(build: BuildInfo): String {
        val base = build.jobUrl.substringBefore("/job/") + "/"
        val parts = build.jobFullName.split('/').map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        val pipeline = if (parts.size > 1) parts.dropLast(1) else parts
        val detail = parts.last()
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
        return base + "blue/organizations/jenkins/" + enc(pipeline.joinToString("/")) + "/detail/" + enc(detail) + "/${build.number}/pipeline"
    }

    data class PastedJob(val baseUrl: String, val fullName: String)

    /**
     * Job behind a URL copied from the browser: classic job/build URLs and Blue Ocean pages.
     * [baseUrls] are the configured server URLs; the longest matching one wins.
     */
    fun parse(url: String, baseUrls: List<String>): PastedJob? {
        val clean = url.trim().substringBefore('#').substringBefore('?')
        val base = baseUrls.map(JenkinsPaths::normalizeBase)
            .filter { clean.startsWith(it, ignoreCase = true) || (clean + "/").startsWith(it, ignoreCase = true) }
            .maxByOrNull { it.length } ?: return null
        val path = (clean + "/").substring(base.length)
        val blue = Regex("^blue/organizations/[^/]+/([^/]+)(?:/detail/([^/]+))?").find(path)
        if (blue != null) {
            val pipeline = URLDecoder.decode(blue.groupValues[1], StandardCharsets.UTF_8).split('/')
            val detail = blue.groupValues[2].takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            // For multibranch pipelines the detail is the branch; Jenkins stores it URL-encoded.
            val segments = if (detail != null && detail != pipeline.last()) pipeline + detail else pipeline
            return PastedJob(base, segments.joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") })
        }
        if (!path.startsWith("job/")) return null
        val fullName = JenkinsPaths.fullNameFromUrl(base + path)
        return fullName.takeIf { it.isNotEmpty() }?.let { PastedJob(base, it) }
    }
}
