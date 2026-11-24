package de.pdenis.jenkspotter.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.JenkSpotterIcons
import de.pdenis.jenkspotter.core.JobHit
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * One job of a search result: icon of its kind, name with highlighted matches, folder path and,
 * depending on the layout, the Jenkins name in front or after it. Used by the jobs list and the
 * popup. Behind the name: how many jobs trigger it and how many follow-up jobs it has; a click on
 * either opens the call graph ([ChainsPopup]).
 */
class JobHitRenderer(
    /** Where the Jenkins name goes for the current row. */
    private val serverLabel: () -> ServerLabel,
) : ListCellRenderer<JobHit> {
    enum class ServerLabel { NONE, PREFIX, SUFFIX }

    /** Left indent in px (unscaled), e.g. below a Jenkins group node. */
    var indent: Int = 0

    /** Path of the folder node the row is listed under; not repeated next to the job. */
    var folderShown: String? = null

    /** Branch below its multibranch pipeline: "├" / "└" in front of the icon; null = none. */
    var connector: Connector? = null

    /** How the row relates to the job under the mouse: tinted while hovering, null = not related. */
    var relation: Relation? = null

    enum class Relation { UPSTREAM, DOWNSTREAM }

    enum class Connector { MIDDLE, LAST }

    // Two text renderers, so none is ever moved between parents: one stands alone, the other
    // sits permanently in a panel with the badges (text cannot hold icons behind it).
    private val plain = Line()
    private val inBadge = Line()
    // "2 -> 3": triggered by 2 jobs, triggers 3; either number is left out when it is 0.
    /** Number of jobs triggering it, right in front of the arrow. */
    private val upBadge = badgeLabel()
    /** The arrow, then the number of follow-up jobs. */
    private val downBadge = badgeLabel().apply { border = JBUI.Borders.emptyLeft(2) }
    private val withBadge = object : JPanel(BorderLayout()) {
        override fun getToolTipText(): String? = inBadge.toolTipText
    }.apply {
        isOpaque = true
        add(inBadge, BorderLayout.WEST)
        // GridBag centers the labels vertically on the text row; FlowLayout pinned them to the top.
        add(JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            val c = java.awt.GridBagConstraints().apply { anchor = java.awt.GridBagConstraints.WEST }
            add(upBadge, c)
            add(downBadge, c)
            add(javax.swing.Box.createHorizontalGlue(), c.apply { weightx = 1.0; fill = java.awt.GridBagConstraints.HORIZONTAL })
        }, BorderLayout.CENTER)
    }

    private fun badgeLabel() = JBLabel().apply { iconTextGap = JBUI.scale(1); border = JBUI.Borders.emptyLeft(8) }

    override fun getListCellRendererComponent(list: JList<out JobHit>, value: JobHit, index: Int, selected: Boolean, hasFocus: Boolean): Component {
        val tint = relation?.takeIf { !selected }?.let { if (it == Relation.UPSTREAM) UPSTREAM_TINT else DOWNSTREAM_TINT }
        val background = tint ?: Stripes.background(index % 2 == 1, selected, hasFocus)
        val view = JenkSpotterSettings.getInstance().current
        // A lookup: the list computed the badges of its rows before showing them.
        val badges = JobIndexService.getInstance().badges(value.serverId, value.job)
        val triggering = if (view.showTriggers) badges.triggeredBy else 0
        val followUps = if (view.showTriggers) badges.followUps else 0
        if (triggering == 0 && followUps == 0) {
            return plain.getListCellRendererComponent(list, value, index, selected, hasFocus).also { it.background = background }
        }
        inBadge.getListCellRendererComponent(list, value, index, selected, hasFocus)
        inBadge.background = background
        withBadge.background = background
        fun color(warn: Boolean) = when {
            selected -> UIUtil.getListForeground(true, hasFocus)
            warn -> CUT_OFF_COLOR
            else -> UIUtil.getContextHelpForeground()
        }
        upBadge.isVisible = triggering > 0
        if (triggering > 0) {
            upBadge.font = list.font
            upBadge.text = triggering.toString()
            upBadge.foreground = color(badges.blocked)
        }
        // The arrow stays for jobs only triggered by others: "2 ->" reads as "triggered, triggers nothing".
        downBadge.font = list.font
        downBadge.icon = JenkSpotterIcons.FollowUps
        // "3", or "2/3" when a disabled job keeps one of the 3 follow-up jobs from running.
        downBadge.text = when {
            followUps == 0 -> ""
            badges.activeFollowUps == followUps -> "$followUps"
            else -> "${badges.activeFollowUps}/$followUps"
        }
        downBadge.foreground = color(badges.activeFollowUps < followUps)
        upBadge.border = JBUI.Borders.emptyLeft(8)
        return withBadge
    }

    /**
     * Whether [point] (relative to the cell) hits one of the badges of the row just rendered
     * into [component] with [size].
     */
    fun isOnChainsBadge(component: Component, size: java.awt.Dimension, point: java.awt.Point): Boolean {
        if (component !== withBadge) return false
        withBadge.setBounds(0, 0, size.width, size.height)
        fun layout(c: java.awt.Container) {
            c.doLayout()
            c.components.filterIsInstance<java.awt.Container>().forEach(::layout)
        }
        layout(withBadge)
        return listOf(upBadge, downBadge).any { it.isVisible && javax.swing.SwingUtilities.convertRectangle(it.parent, it.bounds, withBadge).contains(point) }
    }

    private inner class Line : ColoredListCellRenderer<JobHit>() {
    /** Built only when the tooltip is shown: rendering all rows must stay cheap with thousands of jobs. */
    private var tooltip: () -> String? = { null }
    /** Colour of the workflow the job belongs to, painted as a thin strip at the left edge; null = none. */
    private var workflowColor: java.awt.Color? = null

    override fun customizeCellRenderer(list: JList<out JobHit>, value: JobHit, index: Int, selected: Boolean, hasFocus: Boolean) {
        val view = JenkSpotterSettings.getInstance().current
        val badges = JobIndexService.getInstance().badges(value.serverId, value.job)
        workflowColor = if (view.showWorkflowColors) workflowColor(value.serverId, badges) else null
        // The connector sits right below the multibranch pipeline's icon, the branch icon after it.
        val tree = when (connector) {
            Connector.MIDDLE -> CONNECTOR_MIDDLE
            Connector.LAST -> CONNECTOR_LAST
            null -> null
        }
        ipad = JBUI.insets(1, 2 + indent, 1, 2)
        val disabled = value.job.disabled
        val type = JenkSpotterIcons.jobType(value.job, badges.inMultiBranch)
        val base = JenkSpotterIcons.job(type, value.job.isParameterized).let { if (disabled) IconLoader.getDisabledIcon(it) else it }
        val marks = listOfNotNull(JenkSpotterIcons.Pinned.takeIf { value.pinned }, JenkSpotterIcons.Watched.takeIf { value.watched })
        val icons = listOfNotNull(tree, base) + marks
        icon = if (icons.size == 1) base else RowIcon(*icons.toTypedArray())
        val server = JenkSpotterSettings.getInstance().server(value.serverId)?.displayName.orEmpty()
        val label = serverLabel()
        if (label == ServerLabel.PREFIX) {
            appendHighlighted(server, value.match.serverRanges, SimpleTextAttributes.GRAYED_ATTRIBUTES, selected)
            append("  ›  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        // A branch alone ("main") does not say which repository it builds: its multibranch pipeline goes in front,
        // unless the row sits right below that pipeline's node.
        if (type == JenkSpotterIcons.JobType.BRANCH && value.path != folderShown) {
            val pipeline = de.pdenis.jenkspotter.api.JenkinsPaths.displayName(value.job.fullName.substringBeforeLast('/').substringAfterLast('/'))
            append(pipeline, if (disabled) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(" \u203A ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        appendHighlighted(value.name, value.match.nameRanges, if (disabled) DISABLED_NAME else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, selected)
        if (disabled) append("  " + JenkSpotterBundle.message("jobs.disabled"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        if (view.showJobPath && value.path.isNotEmpty() && value.path != folderShown) {
            append("   ")
            appendHighlighted(value.path, value.match.pathRanges, SimpleTextAttributes.GRAYED_ATTRIBUTES, selected)
        }
        if (label == ServerLabel.SUFFIX) {
            append("   ")
            appendHighlighted(server, value.match.serverRanges, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, selected)
        }
        tooltip = { tooltipHtml(value, type, badges) }
    }

    override fun getToolTipText(): String? = tooltip()

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        val color = workflowColor ?: return
        g.color = color
        g.fillRect(0, 0, JBUI.scale(WORKFLOW_STRIP), height)
    }

    private fun tooltipHtml(value: JobHit, type: JenkSpotterIcons.JobType, badges: de.pdenis.jenkspotter.core.JobBadges): String {
        val tips = mutableListOf("<b>" + JenkSpotterBundle.message(type.key) + "</b>")
        if (value.job.disabled) tips += "<b>" + JenkSpotterBundle.message("jobs.disabled.tooltip") + "</b>"
        if (value.job.isParameterized) tips += JenkSpotterBundle.message("jobs.parameters", value.job.parameterCount)
        if (value.watched) tips += JenkSpotterBundle.message("jobs.watched")
        val triggering = badges.triggeredBy
        if (triggering > 0) tips += JenkSpotterBundle.message("jobs.triggeredBy", triggering)
        if (value.job.downstream.isNotEmpty()) tips += JenkSpotterBundle.message("jobs.followUps", value.job.downstream.size)
        // The graph itself is in [ChainsPopup]; the tooltip only says how to open it.
        if (triggering > 0 || value.job.downstream.isNotEmpty()) tips += "<i>" + JenkSpotterBundle.message("jobs.chains.badge.tooltip") + "</i>"
        return "<html>" + tips.joinToString("<br>") + "</html>"
    }

    private fun appendHighlighted(text: String, ranges: List<IntRange>, plain: SimpleTextAttributes, selected: Boolean) {
        val highlight = if (selected) {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_UNDERLINE, null)
        } else {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, MATCH_COLOR)
        }
        var position = 0
        for (range in ranges.sortedBy { it.first }) {
            val start = range.first.coerceIn(position, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (start > position) append(text.substring(position, start), plain)
            if (end > start) append(text.substring(start, end), highlight)
            position = end
        }
        if (position < text.length) append(text.substring(position), plain)
    }

    }

    /** "├─" or "└─", over the full row height so consecutive rows join into one line. */
    private class ConnectorIcon(private val kind: Connector) : javax.swing.Icon {
        override fun paintIcon(c: Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.color = UIUtil.getContextHelpForeground()
                val cx = x + JBUI.scale(CONNECTOR_WIDTH) / 2
                val cy = y + iconHeight / 2
                // Over the whole cell height, so the lines of consecutive rows join.
                val top = 0
                val bottom = if (kind == Connector.LAST) cy else c?.height ?: (y + iconHeight)
                g2.drawLine(cx, top, cx, bottom)
                g2.drawLine(cx, cy, x + JBUI.scale(CONNECTOR_WIDTH) - JBUI.scale(2), cy)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth() = JBUI.scale(CONNECTOR_WIDTH)
        override fun getIconHeight() = JBUI.scale(16)
    }

    companion object {
        private const val CONNECTOR_WIDTH = 14
        private const val WORKFLOW_STRIP = 3
        private val UPSTREAM_TINT = JBColor(0xE3ECFA, 0x2C3A50)
        private val DOWNSTREAM_TINT = JBColor(0xE3F3E1, 0x2D4230)

        /** Muted colours that stay apart from each other and from the selection, in light and dark themes. */
        private val WORKFLOW_COLORS = listOf(
            JBColor(0x5B8DEF, 0x4A73C2), JBColor(0x4CAF7D, 0x3F9168), JBColor(0xE0A030, 0xB8862E),
            JBColor(0xB76AD9, 0x9558B0), JBColor(0xE06C75, 0xB85A62), JBColor(0x3FB5C0, 0x35939C),
            JBColor(0x9C8B5E, 0x82744F), JBColor(0xD97FB5, 0xB06A93),
        )

        /**
         * Jobs of one workflow (the same job starting it) share a colour, chosen from its name so it
         * stays the same; jobs in no workflow have none.
         */
        fun workflowColor(serverId: String, badges: de.pdenis.jenkspotter.core.JobBadges): java.awt.Color? {
            val root = badges.workflow ?: return null
            return WORKFLOW_COLORS[Math.floorMod((serverId + root).hashCode(), WORKFLOW_COLORS.size)]
        }

        private val CONNECTOR_MIDDLE = ConnectorIcon(Connector.MIDDLE)
        private val CONNECTOR_LAST = ConnectorIcon(Connector.LAST)
        private val MATCH_COLOR = JBColor.namedColor("JenkSpotter.matchForeground", JBColor(0xC25A0A, 0xE08855))
        val CUT_OFF_COLOR = JBColor(0xB07800, 0xD9A343)
        private val DISABLED_NAME = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getInactiveTextColor())
    }
}
