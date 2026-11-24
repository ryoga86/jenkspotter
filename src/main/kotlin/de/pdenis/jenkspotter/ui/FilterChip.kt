package de.pdenis.jenkspotter.ui

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

/** The three states of a filter chip; a click moves to the next one. */
enum class ChipState(val key: String) {
    /** Green: show only matching jobs. */
    INCLUDE("filter.state.include"),
    /** Red: hide matching jobs. */
    EXCLUDE("filter.state.exclude"),
    /** Grey outline: the rule does nothing. */
    OFF("filter.state.off");

    companion object {
        fun of(filter: RegexFilter): ChipState = when {
            !filter.enabled -> OFF
            filter.mode == FilterMode.INCLUDE -> INCLUDE
            else -> EXCLUDE
        }
    }
}

/**
 * Rounded chip for one filter. Click moves through [states] (green = shows only matching entries,
 * red = hides them, grey outline = off), the context menu picks a state directly and offers edit and
 * remove. For a filter rule of the Jenkins view see [of].
 */
class FilterChip(
    private val text: String,
    initial: ChipState,
    /** States the chip cycles through, in click order. */
    private val states: List<ChipState>,
    private val onState: (ChipState) -> Unit,
    /** null for chips that are not a stored rule (this project's jobs, the builds table). */
    private val onEdit: (() -> Unit)?,
    private val onRemove: (() -> Unit)?,
    tooltip: String,
    /** false: broken rule, stays off and cannot be switched. */
    private val valid: Boolean = true,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)) {
    /** Moved on right away on a click, so a second click before the chips are rebuilt goes on from here. */
    private var state = if (valid) initial else ChipState.OFF
    private val label = JBLabel()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 8, 1, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        updateLabel()
        add(label)
        toolTipText = tooltip
        label.toolTipText = toolTipText

        // Reachable with Tab; Space or Enter switch like a click.
        isFocusable = true
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = repaint()
            override fun focusLost(e: java.awt.event.FocusEvent) = repaint()
        })
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode != java.awt.event.KeyEvent.VK_SPACE && e.keyCode != java.awt.event.KeyEvent.VK_ENTER) return
                e.consume()
                switchState()
            }
        })

        // Every left click switches, also quick ones in a row (click count 2, 3, ...) and ones where
        // the mouse moved a little between press and release, which mouseClicked would drop.
        val mouse = object : MouseAdapter() {
            private var pressed = false

            override fun mousePressed(e: MouseEvent) {
                pressed = isLeftClick(e)
                popup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                popup(e)
                if (!pressed || !isLeftClick(e)) return
                pressed = false
                if (e.component.contains(e.point)) switchState()
            }

            private fun isLeftClick(e: MouseEvent) =
                javax.swing.SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger && !(com.intellij.openapi.util.SystemInfo.isMac && e.isControlDown)
        }
        addMouseListener(mouse)
        label.addMouseListener(mouse)
    }

    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        JBPopupMenu().apply {
            if (valid) {
                for (s in states) {
                    add(javax.swing.JCheckBoxMenuItem(JenkSpotterBundle.message(s.key), s == state).apply { addActionListener { onState(s) } })
                }
                addSeparator()
            }
            onEdit?.let { edit -> add(item("filter.edit") { edit() }) }
            onRemove?.let { remove ->
                addSeparator()
                add(item("filter.remove") { remove() })
            }
        }.show(e.component, e.x, e.y)
    }

    private fun switchState() {
        if (!valid) return
        state = next()
        updateLabel()
        revalidate()
        repaint()
        onState(state)
    }

    /** The state is in the text too, not only in the colour: a check mark shows only, a cross hides. */
    private fun updateLabel() {
        label.text = when (state) {
            ChipState.INCLUDE -> "\u2713 $text"
            ChipState.EXCLUDE -> "\u2715 $text"
            ChipState.OFF -> text
        }
        label.foreground = if (state != ChipState.OFF) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
        getAccessibleContext().accessibleName = "$text: " + JenkSpotterBundle.message(state.key)
    }

    /** The state after [state] in click order. */
    private fun next(): ChipState = states[(states.indexOf(state) + 1) % states.size]

    private fun item(key: String, action: () -> Unit) =
        JMenuItem(JenkSpotterBundle.message(key)).apply { addActionListener { action() } }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height
            val accent = if (state == ChipState.INCLUDE) INCLUDE_COLOR else EXCLUDE_COLOR
            if (state != ChipState.OFF) {
                g2.color = ColorUtilCompat.withAlpha(accent, 0.22)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = accent
            } else {
                g2.color = JBColor.border()
            }
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            if (isFocusOwner) {
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                g2.drawRoundRect(1, 1, width - 3, height - 3, arc, arc)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    companion object {
        /** Chip for a filter rule; the tooltip shows its regular expression unless [tooltip] is given. */
        fun of(
            filter: RegexFilter,
            states: List<ChipState>,
            onState: (ChipState) -> Unit,
            onEdit: (() -> Unit)?,
            onRemove: (() -> Unit)?,
            tooltip: String? = null,
        ): FilterChip {
            val valid = filter.regexOrNull() != null
            val state = if (valid) ChipState.of(filter) else ChipState.OFF
            val e = com.intellij.openapi.util.text.StringUtil::escapeXmlEntities
            val text = tooltip ?: "<html><b>${e(filter.label)}</b> - ${JenkSpotterBundle.message(state.key)}<br><code>${e(filter.pattern)}</code>" +
                (if (valid) "" else "<br><font color='red'>${JenkSpotterBundle.message("filter.invalid")}</font>") +
                "<br>${JenkSpotterBundle.message("filter.chip.help")}</html>"
            return FilterChip(filter.label, state, states, onState, onEdit, onRemove, text, valid)
        }

        val EXCLUDE_COLOR = JBColor(0xD9534F, 0xC75450)
        val INCLUDE_COLOR = JBColor(0x3E9B4F, 0x5AA469)
    }
}

private object ColorUtilCompat {
    fun withAlpha(c: java.awt.Color, alpha: Double) = java.awt.Color(c.red, c.green, c.blue, (alpha * 255).toInt())
}
