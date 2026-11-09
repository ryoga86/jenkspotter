package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.settings.FilterMode
import de.pdenis.jenkspotter.settings.RegexFilter
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel

/**
 * Rounded chip for one filter rule. Click toggles it, the x removes it, the context menu offers
 * everything else (edit, switch mode). Red = hides matching jobs, green = shows only matching jobs;
 * switched-off chips are drawn as a grey outline.
 */
class FilterChip(
    private val filter: RegexFilter,
    private val onToggle: (Boolean) -> Unit,
    private val onEdit: () -> Unit,
    /** null for rules whose mode cannot be switched (folder overview). */
    private val onSwitchMode: (() -> Unit)?,
    private val onRemove: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)) {
    private val valid = filter.regexOrNull() != null
    private val include = filter.mode == FilterMode.INCLUDE
    private val active = filter.enabled && valid

    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 8, 1, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val prefix = if (include) "+" else "\u2212"
        val label = JBLabel("$prefix ${filter.label}").apply {
            foreground = if (active) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
        }
        val close = JBLabel(AllIcons.Actions.Close).apply {
            toolTipText = JenkSpotterBundle.message("filter.remove")
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { icon = AllIcons.Actions.CloseHovered }
                override fun mouseExited(e: MouseEvent) { icon = AllIcons.Actions.Close }
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) onRemove()
                    e.consume()
                }
            })
        }
        add(label)
        add(close)

        val modeText = JenkSpotterBundle.message(if (include) "filter.mode.include" else "filter.mode.exclude")
        toolTipText = "<html><b>${escape(filter.label)}</b><br>$modeText: <code>${escape(filter.pattern)}</code>" +
            (if (valid) "" else "<br><font color='red'>${JenkSpotterBundle.message("filter.invalid")}</font>") +
            "<br>${JenkSpotterBundle.message("filter.chip.help")}</html>"
        label.toolTipText = toolTipText

        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.isPopupTrigger || e.button != MouseEvent.BUTTON1) return
                if (e.clickCount == 1 && valid) onToggle(!filter.enabled)
            }
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
        }
        addMouseListener(mouse)
        label.addMouseListener(mouse)
    }

    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        JBPopupMenu().apply {
            if (valid) {
                add(item(if (filter.enabled) "filter.disable" else "filter.enable") { onToggle(!filter.enabled) })
            }
            add(item("filter.edit") { onEdit() })
            onSwitchMode?.let { switch -> add(item(if (include) "filter.switch.toExclude" else "filter.switch.toInclude") { switch() }) }
            addSeparator()
            add(item("filter.remove") { onRemove() })
        }.show(e.component, e.x, e.y)
    }

    private fun item(key: String, action: () -> Unit) =
        JMenuItem(JenkSpotterBundle.message(key)).apply { addActionListener { action() } }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height
            val accent = if (include) INCLUDE_COLOR else EXCLUDE_COLOR
            if (active) {
                g2.color = ColorUtilCompat.withAlpha(accent, 0.22)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = accent
            } else {
                g2.color = JBColor.border()
            }
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun escape(text: String) = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text)

    companion object {
        val EXCLUDE_COLOR = JBColor(0xD9534F, 0xC75450)
        val INCLUDE_COLOR = JBColor(0x3E9B4F, 0x5AA469)
    }
}

private object ColorUtilCompat {
    fun withAlpha(c: java.awt.Color, alpha: Double) = java.awt.Color(c.red, c.green, c.blue, (alpha * 255).toInt())
}
