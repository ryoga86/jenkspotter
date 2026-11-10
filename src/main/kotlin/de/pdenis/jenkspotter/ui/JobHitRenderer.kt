package de.pdenis.jenkspotter.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.JenkSpotterIcons
import de.pdenis.jenkspotter.core.ChainNode
import de.pdenis.jenkspotter.core.ChainPosition
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.JobHit
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import de.pdenis.jenkspotter.settings.FollowUpIcon
import de.pdenis.jenkspotter.settings.ChainStepIcon
import de.pdenis.jenkspotter.settings.ChainDisplay

/**
 * One job of a search result: name with highlighted matches, folder path and, depending on the
 * layout, the Jenkins name in front or after it. Used by the jobs list and the popup. Jobs with
 * follow-up jobs show their count behind the configured mark, the whole chain is in the tooltip.
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

    /** [ChainDisplay.TREE]: nesting below the job that triggers it (0 = listed on its own). */
    var chainDepth: Int = 0

    /** [ChainDisplay.TREE]: true/false = follow-up jobs shown/hidden, null = it has none. */
    var expanded: Boolean? = null

    /** [ChainDisplay.LANE]: piece of the chain line drawn at the left edge. */
    var lane: Lane = Lane.NONE

    /** [ChainDisplay.LANE]: the line into / out of this row is dashed (that job is not triggered). */
    var laneDashedIn: Boolean = false
    var laneDashedOut: Boolean = false

    /** [ChainDisplay.HIGHLIGHT]: how the row relates to the selected job, null = not at all. */
    var relation: Relation? = null

    enum class Lane { NONE, START, MIDDLE, END }

    enum class Relation { UPSTREAM, DOWNSTREAM, NOT_TRIGGERED }

    // Two text renderers, so none is ever moved between parents: one stands alone, the other
    // sits permanently in a panel with labels for the step and follow-up icons (text cannot hold icons).
    private val plain = Line()
    private val inBadge = Line()
    /** "2 <icon>": step of a job triggered by others, number in front. */
    private val stepBadge = JBLabel().apply {
        iconTextGap = JBUI.scale(2)
        border = JBUI.Borders.emptyLeft(6)
        horizontalTextPosition = javax.swing.SwingConstants.LEFT
    }
    /** "<icon> 3": follow-up jobs, number after. */
    private val badge = JBLabel().apply { iconTextGap = JBUI.scale(2); border = JBUI.Borders.emptyLeft(6) }
    private val withBadge = JPanel(BorderLayout()).apply {
        isOpaque = true
        add(inBadge, BorderLayout.WEST)
        // GridBag centers the labels vertically on the text row; FlowLayout pinned them to the top.
        add(JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            val c = java.awt.GridBagConstraints().apply { anchor = java.awt.GridBagConstraints.WEST }
            add(stepBadge, c)
            add(badge, c)
            add(javax.swing.Box.createHorizontalGlue(), c.apply { weightx = 1.0; fill = java.awt.GridBagConstraints.HORIZONTAL })
        }, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(list: JList<out JobHit>, value: JobHit, index: Int, selected: Boolean, hasFocus: Boolean): Component {
        val component = badgedComponent(list, value, index, selected, hasFocus)
        val tint = relation?.takeIf { !selected }?.let {
            when (it) {
                Relation.UPSTREAM -> UPSTREAM_TINT
                Relation.DOWNSTREAM -> DOWNSTREAM_TINT
                Relation.NOT_TRIGGERED -> NOT_TRIGGERED_TINT
            }
        }
        if (tint != null) {
            component.background = tint
            if (component === withBadge) inBadge.background = tint
        }
        return component
    }

    private fun badgedComponent(list: JList<out JobHit>, value: JobHit, index: Int, selected: Boolean, hasFocus: Boolean): Component {
        val view = JenkSpotterSettings.getInstance().current
        if (view.chainDisplay != ChainDisplay.BADGES) return plain.getListCellRendererComponent(list, value, index, selected, hasFocus)
        val icon = JenkSpotterIcons.followUp(view.followUpIcon)
        val chain = if (icon != null && view.showDownstream && value.job.downstream.isNotEmpty()) {
            JobIndexService.getInstance().chain(value.serverId, value.job)
        } else {
            emptyList()
        }
        val stepIcon = JenkSpotterIcons.chainStep(view.chainStepIcon, view.followUpIcon)
        val position = if (stepIcon != null && view.showChainStep && !value.job.isFolder) {
            JobIndexService.getInstance().position(value.serverId, value.job)
        } else {
            null
        }
        if (chain.isEmpty() && position == null) return plain.getListCellRendererComponent(list, value, index, selected, hasFocus)
        inBadge.getListCellRendererComponent(list, value, index, selected, hasFocus)
        stepBadge.font = list.font
        badge.font = list.font
        val background = UIUtil.getListBackground(selected, hasFocus)
        inBadge.background = background
        withBadge.background = background
        fun color(warn: Boolean) = when {
            selected -> UIUtil.getListForeground(true, hasFocus)
            warn -> CUT_OFF_COLOR
            else -> UIUtil.getContextHelpForeground()
        }
        stepBadge.isVisible = position != null
        if (position != null) {
            stepBadge.icon = stepIcon
            stepBadge.text = position.step.toString()
            stepBadge.foreground = color(position.blockedBy != null)
        }
        badge.isVisible = chain.isNotEmpty()
        badge.icon = icon
        badge.text = countText(chain)
        badge.foreground = color(cutOff(chain))
        withBadge.toolTipText = inBadge.toolTipText
        return withBadge
    }

    private inner class Line : ColoredListCellRenderer<JobHit>() {
    private var paintedLane = Lane.NONE
    private var dashedIn = false
    private var dashedOut = false
    /** Disabled or behind a disabled job: hollow dot. */
    private var hollow = false

    override fun customizeCellRenderer(list: JList<out JobHit>, value: JobHit, index: Int, selected: Boolean, hasFocus: Boolean) {
        val view = JenkSpotterSettings.getInstance().current
        val display = view.chainDisplay
        paintedLane = if (display == ChainDisplay.LANE) lane else Lane.NONE
        dashedIn = laneDashedIn
        dashedOut = laneDashedOut
        hollow = laneDashedIn || value.job.disabled
        val laneSpace = if (display == ChainDisplay.LANE) LANE_WIDTH else 0
        ipad = JBUI.insets(1, 2 + indent + chainDepth * CHAIN_INDENT + laneSpace, 1, 2)
        val disabled = value.job.disabled
        val base = (if (value.job.isFolder) JenkSpotterIcons.Folder else JenkSpotterIcons.job(view.jobIcon, value.job.isParameterized))
            ?.let { if (disabled) IconLoader.getDisabledIcon(it) else it }
        val marks = listOfNotNull(JenkSpotterIcons.Pinned.takeIf { value.pinned }, JenkSpotterIcons.Watched.takeIf { value.watched })
        // Tree: an expand arrow in front, or the same space for jobs without follow-ups.
        val arrow = if (display != ChainDisplay.TREE) {
            null
        } else {
            when (expanded) {
                true -> UIUtil.getTreeExpandedIcon()
                false -> UIUtil.getTreeCollapsedIcon()
                null -> com.intellij.util.ui.EmptyIcon.create(UIUtil.getTreeCollapsedIcon())
            }
        }
        val icons = listOfNotNull(arrow, base) + marks
        icon = when (icons.size) {
            0 -> null
            1 -> icons.single()
            else -> RowIcon(*icons.toTypedArray())
        }
        val upstream = display != ChainDisplay.BADGES || view.showChainStep
        val position = if (upstream && !value.job.isFolder) JobIndexService.getInstance().position(value.serverId, value.job) else null
        if (display == ChainDisplay.TREE && chainDepth > 0) append("\u2514 ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (display == ChainDisplay.ARROWS && position != null) {
            append("\u25C2 ", if (position.blockedBy != null) SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, CUT_OFF_COLOR) else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        val server = JenkSpotterSettings.getInstance().server(value.serverId)?.displayName.orEmpty()
        val label = serverLabel()
        if (label == ServerLabel.PREFIX) {
            appendHighlighted(server, value.match.serverRanges, SimpleTextAttributes.GRAYED_ATTRIBUTES, selected)
            append("  ›  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        appendHighlighted(value.name, value.match.nameRanges, if (disabled) DISABLED_NAME else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, selected)
        if (display == ChainDisplay.ARROWS && value.job.downstream.isNotEmpty()) append(" \u25B8", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (disabled) {
            append("  " + JenkSpotterBundle.message("jobs.disabled"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        } else if (display != ChainDisplay.BADGES && position?.blockedBy != null) {
            // Badges show this in orange; the other displays say it in words.
            append("  " + JenkSpotterBundle.message("jobs.notReached"), SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, CUT_OFF_COLOR))
        }
        if (view.showJobPath && value.path.isNotEmpty() && value.path != folderShown) {
            append("   ")
            appendHighlighted(value.path, value.match.pathRanges, SimpleTextAttributes.GRAYED_ATTRIBUTES, selected)
        }
        if (display == ChainDisplay.BADGES && position != null && view.chainStepIcon == ChainStepIcon.TEXT) {
            val color = if (position.blockedBy != null) CUT_OFF_COLOR else UIUtil.getContextHelpForeground()
            append("   " + JenkSpotterBundle.message("jobs.chainStep", position.step, position.length), SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color))
        }
        if (label == ServerLabel.SUFFIX) {
            append("   ")
            appendHighlighted(server, value.match.serverRanges, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, selected)
        }
        val chain = if ((display != ChainDisplay.BADGES || view.showDownstream) && value.job.downstream.isNotEmpty()) {
            JobIndexService.getInstance().chain(value.serverId, value.job)
        } else {
            emptyList()
        }
        val tips = mutableListOf<String>()
        if (disabled) tips += "<b>" + JenkSpotterBundle.message("jobs.disabled.tooltip") + "</b>"
        if (value.job.isParameterized) tips += JenkSpotterBundle.message("jobs.parameters", value.job.parameterCount)
        if (value.watched) tips += JenkSpotterBundle.message("jobs.watched")
        if (position != null) tips += positionTooltip(value.serverId, position)
        if (chain.isNotEmpty()) {
            if (display == ChainDisplay.BADGES && view.followUpIcon == FollowUpIcon.ARROW) {
                val attributes = if (cutOff(chain)) SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, CUT_OFF_COLOR) else SimpleTextAttributes.GRAYED_ATTRIBUTES
                append("   \u2192 " + countText(chain), attributes)
            }
            tips += chainTooltip(chain)
        }
        toolTipText = if (tips.isEmpty()) null else "<html>" + tips.joinToString("<br>") + "</html>"
    }

    /** Chain line: a dot per job, joined to the rows above and below it within the chain. */
    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        if (paintedLane == Lane.NONE) return
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            val x = JBUI.scale(2 + indent + LANE_WIDTH / 2)
            val mid = height / 2
            val solid = java.awt.BasicStroke(JBUI.scale(2f))
            val dashed = java.awt.BasicStroke(JBUI.scale(1.5f), java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f, floatArrayOf(JBUI.scale(3f), JBUI.scale(3f)), 0f)
            fun segment(y1: Int, y2: Int, isDashed: Boolean) {
                g2.color = if (isDashed) CUT_OFF_COLOR else LANE_COLOR
                g2.stroke = if (isDashed) dashed else solid
                g2.drawLine(x, y1, x, y2)
            }
            if (paintedLane != Lane.START) segment(0, mid, dashedIn)
            if (paintedLane != Lane.END) segment(mid, height, dashedOut)
            val r = JBUI.scale(3)
            if (hollow) {
                g2.color = UIUtil.getListBackground()
                g2.fillOval(x - r, mid - r, 2 * r, 2 * r)
                g2.color = CUT_OFF_COLOR
                g2.stroke = java.awt.BasicStroke(JBUI.scale(1.5f))
                g2.drawOval(x - r, mid - r, 2 * r, 2 * r)
            } else {
                g2.color = LANE_COLOR
                g2.fillOval(x - r, mid - r, 2 * r, 2 * r)
            }
        } finally {
            g2.dispose()
        }
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

    companion object {
        private val MATCH_COLOR = JBColor.namedColor("JenkSpotter.matchForeground", JBColor(0xC25A0A, 0xE08855))
        private val CUT_OFF_COLOR = JBColor(0xB07800, 0xD9A343)
        private val LANE_COLOR = JBColor(0x6E9BD8, 0x5C7FB0)
        private val UPSTREAM_TINT = JBColor(0xE6EEFA, 0x2E3B4F)
        private val DOWNSTREAM_TINT = JBColor(0xE5F3E3, 0x2F4231)
        private val NOT_TRIGGERED_TINT = JBColor(0xF6EEDC, 0x46402F)
        private const val LANE_WIDTH = 14
        private const val CHAIN_INDENT = 16
        private val DISABLED_NAME = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getInactiveTextColor())

        /** Some follow-up jobs will not run because a job of the chain is disabled. */
        fun cutOff(chain: List<ChainNode>) = chain.sumOf { it.activeSize } < chain.sumOf { it.size }

        /** "3", or "2/3" when only 2 of the 3 follow-up jobs will run. */
        fun countText(chain: List<ChainNode>): String {
            val total = chain.sumOf { it.size }
            val active = chain.sumOf { it.activeSize }
            return if (active == total) "$total" else "$active/$total"
        }

        /** "Step 2 of 4 in the chain started by build:" and the whole chain with this job in bold. */
        fun positionTooltip(serverId: String, position: ChainPosition): String {
            val e = com.intellij.openapi.util.text.StringUtil::escapeXmlEntities
            val root = position.path.first()
            val out = StringBuilder(JenkSpotterBundle.message("jobs.chainStep.tooltip", position.step, position.length, e(JenkinsPaths.displayName(root.fullName))))
            position.blockedBy?.let { out.append("<br><font color='#").append(com.intellij.ui.ColorUtil.toHex(CUT_OFF_COLOR)).append("'>")
                .append(e(JenkSpotterBundle.message("jobs.chainStep.blocked", JenkinsPaths.displayName(it.fullName)))).append("</font>") }
            out.append("<br>").append(e(JenkinsPaths.displayName(root.fullName)))
            out.append(JobInfoDialog.chainHtml(JobIndexService.getInstance().chain(serverId, root), highlight = position.job.fullName))
            return out.toString()
        }

        /** Indented tree of the whole chain (HTML fragment). */
        fun chainTooltip(chain: List<ChainNode>): String {
            val total = chain.sumOf { it.size }
            val active = chain.sumOf { it.activeSize }
            val head = if (active == total) JenkSpotterBundle.message("jobs.chain", total) else JenkSpotterBundle.message("jobs.chain.cutOff", active, total)
            return head + JobInfoDialog.chainHtml(chain)
        }
    }
}
