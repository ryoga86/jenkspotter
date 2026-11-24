package de.pdenis.jenkspotter.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.FailureHints.Hint

/** Why a build failed or is unstable ([Hint]) as HTML, for the builds tooltip and the "Reason" tab. */
object FailureHintHtml {
    val FAILED_COLOR = JBColor(0xC7222D, 0xFF6B68)
    val UNSTABLE_COLOR = JBColor(0xB86E00, 0xE0A040)

    /** Lines start with "<br>"; with [lineLink] each error line links to it ("line:<index>"). */
    fun render(hint: Hint?, lineLink: Boolean = false): String? {
        val e = StringUtil::escapeXmlEntities
        return when (hint) {
            null -> null
            Hint.Loading -> "<br><i>" + e(JenkSpotterBundle.message("builds.failure.loading")) + "</i>"
            is Hint.Unavailable -> "<br><i>" + e(JenkSpotterBundle.message("builds.failure.unavailable", hint.reason)) + "</i>"
            is Hint.Found -> buildString {
                // Unstable in orange, failed in red, like their icons.
                val color = ColorUtil.toHex(if (hint.unstable) UNSTABLE_COLOR else FAILED_COLOR)
                fun line(text: String, link: String?) {
                    append("<br>")
                    if (link != null) append("<a href='").append(link).append("'>")
                    append("<font color='#").append(color).append("'><code>").append(e(text)).append("</code></font>")
                    if (link != null) append("</a>")
                }
                hint.stage?.let { append("<br><b>").append(e(JenkSpotterBundle.message(if (hint.unstable) "builds.unstable.stage" else "builds.failure.stage", it))).append("</b>") }
                if (hint.tests.isNotEmpty()) {
                    append("<br><b>").append(e(JenkSpotterBundle.message("builds.failure.tests", hint.failedTests))).append("</b>")
                    hint.tests.forEach { line(it, if (lineLink) "tests:" else null) }
                    if (hint.failedTests > hint.tests.size) append("<br><i>").append(e(JenkSpotterBundle.message("builds.failure.moreTests", hint.failedTests - hint.tests.size))).append("</i>")
                }
                if (hint.lines.isEmpty() && hint.tests.isEmpty()) append("<br><i>").append(e(JenkSpotterBundle.message(if (hint.unstable) "builds.unstable.noLines" else "builds.failure.noLines"))).append("</i>")
                if (hint.lines.isNotEmpty() && lineLink) append("<br><br><b>").append(e(JenkSpotterBundle.message("details.reason.log"))).append("</b>")
                hint.lines.forEachIndexed { i, text -> line(text, if (lineLink) "line:$i" else null) }
            }
        }
    }
}
