package de.pdenis.jenkspotter.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.core.BuildMonitorService
import de.pdenis.jenkspotter.core.ConnectionState
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.core.MonitorSnapshot
import de.pdenis.jenkspotter.settings.JenkSpotterConfigurable
import de.pdenis.jenkspotter.settings.JenkSpotterSettings
import de.pdenis.jenkspotter.settings.ServerConfig
import de.pdenis.jenkspotter.settings.VisibilityToggle
import java.awt.BasicStroke
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel

/** Connection state of a configured Jenkins: icon, short text and tooltip. */
object ServerStatusUi {
    fun state(server: ServerConfig, snapshot: MonitorSnapshot = BuildMonitorService.getInstance().snapshot): ConnectionState =
        ConnectionState.of(server.enabled, snapshot.servers[server.id])

    fun icon(state: ConnectionState): Icon = when (state) {
        ConnectionState.CONNECTED -> StatusDot(GREEN, filled = true)
        ConnectionState.ERROR -> StatusDot(JBColor.ORANGE, filled = true)
        ConnectionState.OFFLINE -> StatusDot(JBColor.RED, filled = true)
        ConnectionState.DISABLED -> StatusDot(JBColor.GRAY, filled = false)
        ConnectionState.CONNECTING -> StatusDot(JBColor.GRAY, filled = true)
    }

    fun label(state: ConnectionState): String = JenkSpotterBundle.message("connection.${state.name}")

    /** One line for status texts, e.g. "Jenkins A: offline - Cannot reach ...". */
    fun describe(server: ServerConfig, snapshot: MonitorSnapshot = BuildMonitorService.getInstance().snapshot): String {
        val status = snapshot.servers[server.id]
        return when (val state = state(server, snapshot)) {
            ConnectionState.CONNECTED -> JenkSpotterBundle.message("status.online", server.displayName, status?.userId ?: "?")
            ConnectionState.ERROR, ConnectionState.OFFLINE ->
                JenkSpotterBundle.message("status.problem", server.displayName, label(state), status?.error.orEmpty())
            ConnectionState.CONNECTING -> JenkSpotterBundle.message("status.connecting", server.displayName)
            ConnectionState.DISABLED -> JenkSpotterBundle.message("status.problem", server.displayName, label(state), "").trimEnd(' ', '-')
        }
    }

    fun tooltip(server: ServerConfig, snapshot: MonitorSnapshot = BuildMonitorService.getInstance().snapshot): String {
        val e = StringUtil::escapeXmlEntities
        val status = snapshot.servers[server.id]
        val state = state(server, snapshot)
        val out = StringBuilder("<html><b>").append(e(server.displayName)).append("</b> - ").append(e(label(state)))
        out.append("<br>").append(e(server.url))
        if (state == ConnectionState.CONNECTED || state == ConnectionState.ERROR) status?.userId?.let {
            out.append("<br>").append(e(JenkSpotterBundle.message("connection.user", it)))
        }
        if (state == ConnectionState.ERROR || state == ConnectionState.OFFLINE) status?.error?.let {
            out.append("<br><font color='#").append(com.intellij.ui.ColorUtil.toHex(JBColor.RED)).append("'>").append(e(it)).append("</font>")
        }
        status?.lastUpdate?.takeIf { it > 0 }?.let {
            out.append("<br>").append(e(JenkSpotterBundle.message("connection.lastUpdate", DateFormatUtil.formatPrettyDateTime(it))))
        }
        JobIndexService.getInstance().state(server.id).takeIf { server.enabled }?.let { index ->
            when {
                index.loading -> out.append("<br>").append(e(JenkSpotterBundle.message("jobs.indexing", index.jobs.size)))
                index.error != null -> out.append("<br>").append(e(JenkSpotterBundle.message("connection.indexError", index.error)))
            }
        }
        return out.append("</html>").toString()
    }

    /** Enable/disable, poll now, reload job list, settings. */
    fun actions(project: Project, serverId: String) = DefaultActionGroup().apply {
        val settings = JenkSpotterSettings.getInstance()
        val enabled = settings.server(serverId)?.enabled == true
        add(simple(if (enabled) "connection.disable" else "connection.enable", null) { settings.setServerEnabled(serverId, !enabled) })
        if (enabled) {
            val hidden = serverId in settings.current.hiddenServers
            add(simple(if (hidden) "connection.show" else "connection.hide", AllIcons.Actions.ToggleVisibility) { settings.setServerHidden(serverId, !hidden) })
        }
        if (enabled) {
            add(simple("action.refresh", AllIcons.Actions.Refresh) { BuildMonitorService.getInstance().refresh(serverId) })
            add(simple("action.refreshIndex", AllIcons.Actions.ForceRefresh) {
                settings.server(serverId)?.let { JobIndexService.getInstance().refreshIfNeeded(it, force = true) }
            })
        }
        addSeparator()
        add(simple("action.configure", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, JenkSpotterConfigurable::class.java)
        })
    }

    private fun simple(key: String, icon: Icon?, block: () -> Unit) =
        object : DumbAwareAction({ JenkSpotterBundle.message(key) }, icon) {
            override fun actionPerformed(e: AnActionEvent) = block()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        }

    private val GREEN = JBColor(0x3E9B4F, 0x5AA469)

    /** Icon of the show/hide toggle; styles without a "hidden" variant are greyed out instead. Null for no icon. */
    fun toggleIcon(style: VisibilityToggle, hidden: Boolean): Icon? {
        val (shown, off) = when (style) {
            VisibilityToggle.EYE -> AllIcons.Actions.ToggleVisibility to null
            VisibilityToggle.EYE_CROSSED -> AllIcons.General.TbShown to AllIcons.General.TbHidden
            VisibilityToggle.SMALL_EYE -> com.intellij.util.IconUtil.scale(AllIcons.Actions.ToggleVisibility, null, 0.7f) to null
            VisibilityToggle.SHOW -> AllIcons.Actions.Show to null
            VisibilityToggle.CHECKMARK -> AllIcons.Actions.Checked to null
            VisibilityToggle.BULB -> AllIcons.Actions.IntentionBulb to AllIcons.Actions.QuickfixOffBulb
            VisibilityToggle.TRAFFIC -> AllIcons.General.InspectionsOK to AllIcons.General.InspectionsTrafficOff
            VisibilityToggle.VISIBILITY -> AllIcons.Nodes.Public to AllIcons.Nodes.Private
            VisibilityToggle.LOCK -> AllIcons.Ide.Readwrite to AllIcons.Ide.Readonly
            VisibilityToggle.LIKE -> AllIcons.Ide.Like to AllIcons.Ide.LikeDimmed
            VisibilityToggle.STAR -> AllIcons.Nodes.Favorite to null
            VisibilityToggle.CHECKBOX, VisibilityToggle.NONE -> return null
        }
        return if (!hidden) shown else off ?: com.intellij.openapi.util.IconLoader.getDisabledIcon(shown)
    }
}

/** Small round status light; hollow for disabled servers. */
class StatusDot(private val color: java.awt.Color, private val filled: Boolean) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val d = JBUI.scale(8)
            val dx = x + (iconWidth - d) / 2
            val dy = y + (iconHeight - d) / 2
            g2.color = color
            if (filled) {
                g2.fillOval(dx, dy, d, d)
            } else {
                g2.stroke = BasicStroke(JBUI.scale(1.5f))
                g2.drawOval(dx, dy, d - 1, d - 1)
            }
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth() = JBUI.scale(12)
    override fun getIconHeight() = JBUI.scale(12)
}

/**
 * Bottom of the Jenkins view: one status light plus name per configured Jenkins (disabled ones
 * too). Click for enable/disable, poll now and reload job list; the eye next to it hides the
 * Jenkins' jobs and builds in the views without stopping its polling.
 */
class ServerStatusBar(private val project: Project) : JPanel(com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))) {
    init {
        isOpaque = false
    }

    fun update(snapshot: MonitorSnapshot) {
        removeAll()
        val settings = JenkSpotterSettings.getInstance()
        for (server in settings.current.servers) {
            val state = ServerStatusUi.state(server, snapshot)
            val hidden = server.id in settings.current.hiddenServers
            val entry = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
            entry.add(object : JBLabel(server.displayName, ServerStatusUi.icon(state), LEFT) {
                // Computed when shown, so "last update" stays current.
                override fun getToolTipText(event: MouseEvent?): String = ServerStatusUi.tooltip(server)
            }.apply {
                iconTextGap = JBUI.scale(2)
                componentStyle = UIUtil.ComponentStyle.SMALL
                if (state == ConnectionState.DISABLED || hidden) foreground = UIUtil.getContextHelpForeground()
                toolTipText = server.displayName
                object : ClickListener() {
                    override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                        JBPopupFactory.getInstance()
                            .createActionGroupPopup(null, ServerStatusUi.actions(project, server.id), com.intellij.ide.DataManager.getInstance().getDataContext(this@apply),
                                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                            .show(RelativePoint(event))
                        return true
                    }
                }.installOn(this)
            })
            if (state != ConnectionState.DISABLED) visibilityToggle(server.id, hidden)?.let(entry::add)
            add(entry)
        }
        revalidate()
        repaint()
    }

    private fun visibilityToggle(serverId: String, hidden: Boolean): javax.swing.JComponent? {
        val style = JenkSpotterSettings.getInstance().current.visibilityToggle
        if (style == VisibilityToggle.CHECKBOX) {
            return com.intellij.ui.components.JBCheckBox(null, !hidden).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(2)
                toolTipText = JenkSpotterBundle.message(if (hidden) "connection.show" else "connection.hide")
                addActionListener { JenkSpotterSettings.getInstance().setServerHidden(serverId, !isSelected) }
            }
        }
        val icon = ServerStatusUi.toggleIcon(style, hidden) ?: return null
        return JBLabel(icon).apply {
            border = JBUI.Borders.emptyLeft(2)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = JenkSpotterBundle.message(if (hidden) "connection.show" else "connection.hide")
            object : ClickListener() {
                override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                    JenkSpotterSettings.getInstance().setServerHidden(serverId, !hidden)
                    return true
                }
            }.installOn(this)
        }
    }


}
