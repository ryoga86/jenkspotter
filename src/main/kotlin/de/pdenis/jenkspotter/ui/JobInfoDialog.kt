package de.pdenis.jenkspotter.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.api.BuildInfo
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.api.ParameterDefinition
import de.pdenis.jenkspotter.core.ChainNode
import de.pdenis.jenkspotter.core.JenkinsConnections
import de.pdenis.jenkspotter.core.JobHit
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/** Details of one job: path, follow-up chain, parameters and the latest builds (loaded on open). */
class JobInfoDialog(project: Project, private val hit: JobHit) : DialogWrapper(project, false) {
    private val pane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) BuildActions.openInBrowser(it.url.toString()) }
    }
    private var parameters: List<ParameterDefinition>? = null
    private var builds: List<BuildInfo>? = null
    private var error: String? = null

    init {
        title = JenkSpotterBundle.message("jobInfo.title", hit.name)
        setOKButtonText(JenkSpotterBundle.message("jobInfo.close"))
        init()
        render()
        load()
    }

    override fun createActions() = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = JBScrollPane(pane).apply { preferredSize = JBUI.size(560, 460) }

    private fun load() {
        if (hit.job.isFolder) return
        val modality = ModalityState.stateForComponent(pane)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = JenkinsConnections.getInstance().client(hit.serverId)
                parameters = client?.parameterDefinitions(hit.job.url)
                builds = client?.recentBuilds(hit.job.url, 5)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            ApplicationManager.getApplication().invokeLater({ render() }, modality)
        }
    }

    private fun render() {
        val e = StringUtil::escapeXmlEntities
        val job = hit.job
        val server = JenkSpotterSettings.getInstance().server(hit.serverId)?.displayName.orEmpty()
        val html = StringBuilder("<html><body style='padding:4px'>")
        html.append("<h3>${e(hit.name)}</h3>")
        html.append("<p>${e(JenkinsPaths.displayName(job.fullName))}<br>")
        html.append("$server &middot; ${e(job.jobClass.substringAfterLast('.'))} &middot; <a href='${e(job.url)}'>${JenkSpotterBundle.message("action.openInBrowser")}</a></p>")

        val chain = JobIndexService.getInstance().chain(hit.serverId, job)
        html.append("<h4>${JenkSpotterBundle.message("jobInfo.chain")}</h4>")
        html.append(if (chain.isEmpty()) "<p>${JenkSpotterBundle.message("jobInfo.noChain")}</p>" else "<p>${chainHtml(chain)}</p>")

        if (!job.isFolder) {
            html.append("<h4>${JenkSpotterBundle.message("jobInfo.parameters")}</h4>")
            val params = parameters
            when {
                error != null -> html.append("<p>${e(error!!)}</p>")
                params == null -> html.append("<p>${JenkSpotterBundle.message("jobInfo.loading")}</p>")
                params.isEmpty() -> html.append("<p>${JenkSpotterBundle.message("jobInfo.noParameters")}</p>")
                else -> {
                    html.append("<table cellpadding='2'>")
                    for (p in params) {
                        val default = p.defaultValue?.let { " = <code>${e(it)}</code>" }.orEmpty()
                        val choices = if (p.choices.isNotEmpty()) " (${e(p.choices.joinToString(", "))})" else ""
                        html.append("<tr><td><b>${e(p.name)}</b></td><td>${e(p.type.name.lowercase())}$default$choices")
                        p.description?.let { html.append("<br><i>${e(it)}</i>") }
                        html.append("</td></tr>")
                    }
                    html.append("</table>")
                }
            }
            html.append("<h4>${JenkSpotterBundle.message("jobInfo.builds")}</h4>")
            val recent = builds
            when {
                error != null -> Unit
                recent == null -> html.append("<p>${JenkSpotterBundle.message("jobInfo.loading")}</p>")
                recent.isEmpty() -> html.append("<p>${JenkSpotterBundle.message("jobInfo.noBuilds")}</p>")
                else -> {
                    html.append("<table cellpadding='2'>")
                    for (b in recent) {
                        html.append("<tr><td><a href='${e(b.url)}'>#${b.number}</a></td><td>${JenkSpotterBundle.message("result.${b.result.name}")}</td>")
                        html.append("<td>${DateFormatUtil.formatPrettyDateTime(b.timestamp)}</td><td>${e(b.causeDescriptions.firstOrNull().orEmpty())}</td></tr>")
                    }
                    html.append("</table>")
                }
            }
        }
        pane.text = html.append("</body></html>").toString()
        pane.caretPosition = 0
    }

    companion object {
        /** Indented follow-up tree as HTML. */
        fun chainHtml(chain: List<ChainNode>): String {
            val out = StringBuilder()
            fun walk(nodes: List<ChainNode>, depth: Int) {
                for (node in nodes) {
                    out.append("<br>").append("&nbsp;&nbsp;&nbsp;&nbsp;".repeat(depth)).append("&#8627; ")
                        .append(StringUtil.escapeXmlEntities(JenkinsPaths.displayName(node.fullName)))
                    walk(node.children, depth + 1)
                }
            }
            walk(chain, 0)
            return out.toString()
        }
    }
}
