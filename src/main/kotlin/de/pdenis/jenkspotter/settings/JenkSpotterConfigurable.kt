package de.pdenis.jenkspotter.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.api.BuildResult
import de.pdenis.jenkspotter.api.JenkinsClient
import de.pdenis.jenkspotter.api.JenkinsPaths
import de.pdenis.jenkspotter.core.JenkinsConnections
import de.pdenis.jenkspotter.core.JobIndexService
import de.pdenis.jenkspotter.notify.SoundPlayer
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class JenkSpotterConfigurable : Configurable {
    private val settings = JenkSpotterSettings.getInstance()
    private var working = settings.current.copy()

    /** Tokens typed in this dialog; absent = unchanged. */
    private val editedTokens = HashMap<String, String>()
    private val storedTokens = HashMap<String, String>()

    private val serverModel = CollectionListModel<ServerConfig>()
    private val serverList = JBList(serverModel)
    private var shown: ServerConfig? = null

    private val nameField = JBTextField()
    private val urlField = JBTextField()
    private val userField = JBTextField()
    private val tokenField = JBPasswordField()
    private val activePoll = JSpinner(SpinnerNumberModel(5, 2, 300, 1))
    private val discoveryPoll = JSpinner(SpinnerNumberModel(10, 2, 600, 1))
    private val idlePoll = JSpinner(SpinnerNumberModel(30, 5, 3600, 5))
    private val duplicateWarning = JBLabel().apply { foreground = com.intellij.ui.JBColor.RED }
    private val trackOwn = JBCheckBox(JenkSpotterBundle.message("settings.trackOwn"))
    private val enabledBox = JBCheckBox(JenkSpotterBundle.message("settings.enabled"))
    private val viewsModel = CollectionListModel<String>()
    private val viewsList = JBList(viewsModel).apply {
        visibleRowCount = 4
        emptyText.text = JenkSpotterBundle.message("settings.views.empty")
    }
    private val memoryOnlyWarning = JBLabel(JenkSpotterBundle.message("settings.memoryOnly"), com.intellij.icons.AllIcons.General.Warning, JBLabel.LEFT).apply {
        isVisible = runCatching { com.intellij.ide.passwordSafe.PasswordSafe.instance.isMemoryOnly }.getOrDefault(false)
    }
    private val notifyInput = JBCheckBox(JenkSpotterBundle.message("settings.notifyInput"))
    private val browserUi = ComboBox(BrowserUi.entries.toTypedArray()).apply {
        renderer = textRenderer { JenkSpotterBundle.message("settings.browserUi.${it.name}") }
    }
    private val testResult = JBLabel()
    private val detailPanel = JPanel(BorderLayout())

    private val notifySuccess = JBCheckBox(JenkSpotterBundle.message("result.SUCCESS"))
    private val notifyFailure = JBCheckBox(JenkSpotterBundle.message("result.FAILURE"))
    private val notifyUnstable = JBCheckBox(JenkSpotterBundle.message("result.UNSTABLE"))
    private val notifyAborted = JBCheckBox(JenkSpotterBundle.message("result.ABORTED"))
    private val scopeCombo = ComboBox(arrayOf(NotifyScope.OWN_ONLY, NotifyScope.ALL_VISIBLE)).apply {
        renderer = textRenderer { JenkSpotterBundle.message("settings.scope.${it.name}") }
    }
    private val systemNotify = JBCheckBox(JenkSpotterBundle.message("settings.systemNotify"))
    private val hideSuccess = JSpinner(SpinnerNumberModel(8, 0, 3600, 1))
    private val hideFailure = JSpinner(SpinnerNumberModel(0, 0, 3600, 1))
    private val groupNotifications = JBCheckBox(JenkSpotterBundle.message("settings.groupNotifications"))
    private val soundEnabled = JBCheckBox(JenkSpotterBundle.message("settings.soundEnabled"))
    private val volume = JSlider(0, 100, 70)
    private val successSound = TextFieldWithBrowseButton()
    private val failureSound = TextFieldWithBrowseButton()
    private val historyHours = JSpinner(SpinnerNumberModel(24, 1, 24 * 14, 1))
    private val buildsPerPin = JSpinner(SpinnerNumberModel(5, 1, 50, 1))
    private val wizardingMode = JBCheckBox(JenkSpotterBundle.message("settings.wizardingMode"))

    override fun getDisplayName() = "JenkSpotter"

    override fun createComponent(): JComponent {
        serverList.cellRenderer = object : ColoredListCellRenderer<ServerConfig>() {
            override fun customizeCellRenderer(list: JList<out ServerConfig>, value: ServerConfig?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                val name = value.displayName.ifBlank { JenkSpotterBundle.message("settings.newServer") }
                if (value.enabled) append(name) else append(name + "  " + JenkSpotterBundle.message("settings.disabledSuffix"), com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        serverList.addListSelectionListener { if (!it.valueIsAdjusting) showServer(serverList.selectedValue) }
        val serversPanel = ToolbarDecorator.createDecorator(serverList)
            .setAddAction {
                commitServerForm()
                val server = ServerConfig().apply { name = ""; url = "https://" }
                serverModel.add(server)
                serverList.selectedIndex = serverModel.size - 1
                nameField.requestFocusInWindow()
            }
            .setRemoveAction {
                serverList.selectedValue?.let { serverModel.remove(it); shown = null }
                serverList.selectedIndex = (serverModel.size - 1).coerceAtLeast(-1)
                if (serverModel.isEmpty) showServer(null)
            }
            .disableUpDownActions()
            .createPanel()

        val testButton = JButton(JenkSpotterBundle.message("settings.testConnection")).apply { addActionListener { testConnection() } }
        val serverForm = FormBuilder.createFormBuilder()
            .addComponent(enabledBox)
            .addLabeledComponent(JenkSpotterBundle.message("settings.name"), nameField)
            .addLabeledComponent(JenkSpotterBundle.message("settings.url"), urlField)
            .addLabeledComponent(JenkSpotterBundle.message("settings.user"), userField)
            .addLabeledComponent(JenkSpotterBundle.message("settings.token"), tokenField)
            .addComponentToRightColumn(JBLabel(JenkSpotterBundle.message("settings.tokenHint")).apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL })
            .addComponentToRightColumn(memoryOnlyWarning)
            .addComponentToRightColumn(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(testButton); add(JBLabel("  ")); add(testResult) })
            .addLabeledComponent(JenkSpotterBundle.message("settings.activePoll"), activePoll)
            .addComponentToRightColumn(duplicateWarning)
            .addLabeledComponent(JenkSpotterBundle.message("settings.discoveryPoll"), discoveryPoll)
            .addLabeledComponent(JenkSpotterBundle.message("settings.idlePoll"), idlePoll)
            .addComponent(trackOwn)
            .addLabeledComponent(JenkSpotterBundle.message("settings.views"), viewsPanel(), true)
            .addComponentToRightColumn(JBLabel(JenkSpotterBundle.message("settings.viewsHint")).apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL })
            .panel
        detailPanel.add(serverForm, BorderLayout.NORTH)

        val splitter = JBSplitter(false, 0.3f).apply {
            firstComponent = serversPanel
            secondComponent = detailPanel
            preferredSize = JBUI.size(700, 360)
        }

        successSound.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor("wav"))
        failureSound.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor("wav"))
        fun preview(result: BuildResult, field: TextFieldWithBrowseButton) = JButton(JenkSpotterBundle.message("settings.preview")).apply {
            addActionListener {
                // Preview with the values currently in the form, not the saved ones.
                SoundPlayer.playWith(result, field.text.trim(), volume.value, wizardingMode.isSelected)
            }
        }
        fun row(vararg components: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { components.forEach(::add) }

        val global = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator(JenkSpotterBundle.message("settings.notifications")))
            .addLabeledComponent(JenkSpotterBundle.message("settings.notifyOn"), row(notifySuccess, notifyFailure, notifyUnstable, notifyAborted))
            .addLabeledComponent(JenkSpotterBundle.message("settings.notifyScope"), scopeCombo)
            .addComponent(systemNotify)
            .addComponent(groupNotifications)
            .addComponent(notifyInput)
            .addLabeledComponent(JenkSpotterBundle.message("settings.hideSuccess"), row(hideSuccess, JBLabel(JenkSpotterBundle.message("settings.hideHint"))))
            .addLabeledComponent(JenkSpotterBundle.message("settings.hideFailure"), row(hideFailure, JBLabel(JenkSpotterBundle.message("settings.hideHint"))))
            .addComponent(TitledSeparator(JenkSpotterBundle.message("settings.sounds")))
            .addComponent(soundEnabled)
            .addLabeledComponent(JenkSpotterBundle.message("settings.volume"), volume)
            .addLabeledComponent(JenkSpotterBundle.message("settings.successSound"), row(successSound.apply { preferredSize = JBUI.size(320, preferredSize.height) }, preview(BuildResult.SUCCESS, successSound)))
            .addLabeledComponent(JenkSpotterBundle.message("settings.failureSound"), row(failureSound.apply { preferredSize = JBUI.size(320, preferredSize.height) }, preview(BuildResult.FAILURE, failureSound)))
            .addComponent(TitledSeparator(JenkSpotterBundle.message("settings.general")))
            .addLabeledComponent(JenkSpotterBundle.message("settings.historyHours"), historyHours)
            .addLabeledComponent(JenkSpotterBundle.message("settings.buildsPerPin"), buildsPerPin)
            .addLabeledComponent(JenkSpotterBundle.message("settings.browserUi"), browserUi)
            .addComponent(wizardingMode)
            .panel

        reset()
        return JPanel(BorderLayout(0, 8)).apply {
            add(TitledSeparator(JenkSpotterBundle.message("settings.servers")), BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(global, BorderLayout.SOUTH)
        }
    }

    /** Views list: add by name/path/URL, or pick from the views the server actually has. */
    private fun viewsPanel(): JComponent = ToolbarDecorator.createDecorator(viewsList)
        .setAddAction {
            val name = com.intellij.openapi.ui.Messages.showInputDialog(
                viewsList, JenkSpotterBundle.message("settings.views.addPrompt"), JenkSpotterBundle.message("settings.views.addTitle"), null,
            )?.trim()
            if (!name.isNullOrEmpty() && name !in viewsModel.items) viewsModel.add(name)
        }
        .setRemoveAction { viewsList.selectedValuesList.forEach(viewsModel::remove) }
        .addExtraAction(object : com.intellij.openapi.project.DumbAwareAction(
            { JenkSpotterBundle.message("settings.views.pick") }, com.intellij.icons.AllIcons.Actions.Download,
        ) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = pickViews()
            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })
        .disableUpDownActions()
        .setPreferredSize(JBUI.size(300, 90))
        .createPanel()

    private fun pickViews() {
        commitServerForm()
        val server = shown?.copy() ?: return
        val token = editedTokens[server.id] ?: storedTokens[server.id].orEmpty()
        var views: List<de.pdenis.jenkspotter.api.ViewRef> = emptyList()
        var error: String? = null
        com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    views = JenkinsClient(server.url, server.username, token, JenkinsConnections.createHttpClient()).views()
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
            },
            JenkSpotterBundle.message("settings.views.loading"), true, null, viewsList,
        )
        error?.let {
            com.intellij.openapi.ui.Messages.showErrorDialog(viewsList, it, JenkSpotterBundle.message("settings.views.pick"))
            return
        }
        val candidates = views.filter { it.url !in viewsModel.items && it.name !in viewsModel.items }
        if (candidates.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(viewsList, JenkSpotterBundle.message("settings.views.none"), JenkSpotterBundle.message("settings.views.pick"))
            return
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(candidates)
            .setRenderer(textRenderer<de.pdenis.jenkspotter.api.ViewRef> { "${it.name}   ${it.url.removePrefix(JenkinsPaths.normalizeBase(server.url))}" })
            .setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            .setTitle(JenkSpotterBundle.message("settings.views.pick"))
            .setItemsChosenCallback { chosen: Set<de.pdenis.jenkspotter.api.ViewRef> -> chosen.forEach { viewsModel.add(it.url) } }
            .createPopup()
            .showUnderneathOf(viewsList)
    }

    private fun showServer(server: ServerConfig?) {
        commitServerForm()
        shown = server
        val enabled = server != null
        listOf(enabledBox, nameField, urlField, userField, tokenField, activePoll, discoveryPoll, idlePoll, trackOwn, viewsList).forEach { it.isEnabled = enabled }
        testResult.text = ""
        nameField.text = server?.name.orEmpty()
        urlField.text = server?.url.orEmpty()
        userField.text = server?.username.orEmpty()
        tokenField.text = server?.let { editedTokens[it.id] ?: storedTokens[it.id] }.orEmpty()
        activePoll.value = server?.activePollSeconds ?: 5
        discoveryPoll.value = server?.discoveryPollSeconds ?: 10
        idlePoll.value = server?.idlePollSeconds ?: 30
        updateDuplicateWarning()
        trackOwn.isSelected = server?.trackOwnBuilds ?: true
        enabledBox.isSelected = server?.enabled ?: true
        viewsModel.replaceAll(server?.views.orEmpty())
    }

    private fun commitServerForm() {
        val server = shown ?: return
        server.name = nameField.text.trim()
        server.url = urlField.text.trim()
        server.username = userField.text.trim()
        server.activePollSeconds = activePoll.value as Int
        server.discoveryPollSeconds = discoveryPoll.value as Int
        server.idlePollSeconds = idlePoll.value as Int
        server.trackOwnBuilds = trackOwn.isSelected
        server.enabled = enabledBox.isSelected
        server.views = viewsModel.items.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        val token = String(tokenField.password)
        if (token != (storedTokens[server.id] ?: "")) editedTokens[server.id] = token else editedTokens.remove(server.id)
        serverList.repaint()
        updateDuplicateWarning()
    }

    private fun updateDuplicateWarning() {
        val server = shown
        val norm = { u: String -> u.trim().trimEnd('/').lowercase() }
        val duplicate = server != null && serverModel.items.any {
            it !== server && norm(it.url) == norm(urlField.text) && it.username.trim() == userField.text.trim()
        }
        duplicateWarning.text = if (duplicate) JenkSpotterBundle.message("settings.duplicateUrl") else ""
    }

    private fun commitAll(): JenkSpotterState {
        commitServerForm()
        return working.copy().apply {
            servers = serverModel.items.map(ServerConfig::copy).toMutableList()
            notifySuccess = this@JenkSpotterConfigurable.notifySuccess.isSelected
            notifyFailure = this@JenkSpotterConfigurable.notifyFailure.isSelected
            notifyUnstable = this@JenkSpotterConfigurable.notifyUnstable.isSelected
            notifyAborted = this@JenkSpotterConfigurable.notifyAborted.isSelected
            notifyScope = scopeCombo.item ?: NotifyScope.OWN_ONLY
            systemNotificationWhenInactive = systemNotify.isSelected
            hideSuccessAfterSeconds = hideSuccess.value as Int
            hideFailureAfterSeconds = hideFailure.value as Int
            groupNotifications = this@JenkSpotterConfigurable.groupNotifications.isSelected
            notifyInput = this@JenkSpotterConfigurable.notifyInput.isSelected
            browserUi = this@JenkSpotterConfigurable.browserUi.item ?: BrowserUi.CLASSIC
            soundEnabled = this@JenkSpotterConfigurable.soundEnabled.isSelected
            soundVolume = volume.value
            customSuccessSound = successSound.text.trim()
            customFailureSound = failureSound.text.trim()
            ownHistoryHours = historyHours.value as Int
            buildsPerPinnedJob = buildsPerPin.value as Int
            wizardingMode = this@JenkSpotterConfigurable.wizardingMode.isSelected
        }
    }

    override fun isModified(): Boolean = !commitAll().sameSettingsAs(settings.current) || editedTokens.isNotEmpty()

    override fun apply() {
        val next = commitAll()
        // Pins, filters and recents are edited live from the tool window; keep the latest ones.
        val live = settings.current
        next.pinnedJobs = live.pinnedJobs.filter { pin -> next.servers.any { it.id == pin.serverId } }.map(PinnedJob::copy).toMutableList()
        next.filters = live.filters.map(RegexFilter::copy).toMutableList()
        next.recentJobs = live.recentJobs.filter { key -> next.servers.any { key.startsWith(it.id + " ") } }.toMutableList()
        next.rememberedParameters = live.rememberedParameters.toMutableMap(); next.rememberParameters = live.rememberParameters
        next.buildColumns = live.buildColumns; next.buildColumnWidths = live.buildColumnWidths; next.showJobPath = live.showJobPath
        next.showFolderEntries = live.showFolderEntries; next.showDownstream = live.showDownstream
        next.showJobServer = live.showJobServer
        val removed = settings.current.servers.map { it.id } - next.servers.map { it.id }.toSet()
        removed.forEach { id ->
            TokenStore.set(id, "", null)
            JobIndexService.getInstance().forget(id)
        }
        for ((id, token) in editedTokens) {
            val server = next.servers.firstOrNull { it.id == id } ?: continue
            TokenStore.set(id, server.username, token)
            storedTokens[id] = token
        }
        editedTokens.clear()
        JenkinsConnections.getInstance().invalidate()
        settings.update(next)
        working = next.copy()
    }

    override fun reset() {
        working = settings.current.copy()
        editedTokens.clear()
        shown = null
        serverModel.replaceAll(working.servers.map(ServerConfig::copy))
        notifySuccess.isSelected = working.notifySuccess
        notifyFailure.isSelected = working.notifyFailure
        notifyUnstable.isSelected = working.notifyUnstable
        notifyAborted.isSelected = working.notifyAborted
        scopeCombo.item = working.notifyScope
        systemNotify.isSelected = working.systemNotificationWhenInactive
        hideSuccess.value = working.hideSuccessAfterSeconds
        hideFailure.value = working.hideFailureAfterSeconds
        groupNotifications.isSelected = working.groupNotifications
        notifyInput.isSelected = working.notifyInput
        browserUi.item = working.browserUi
        soundEnabled.isSelected = working.soundEnabled
        volume.value = working.soundVolume
        successSound.text = working.customSuccessSound
        failureSound.text = working.customFailureSound
        historyHours.value = working.ownHistoryHours
        buildsPerPin.value = working.buildsPerPinnedJob
        wizardingMode.isSelected = working.wizardingMode
        if (serverModel.isEmpty) showServer(null) else serverList.selectedIndex = 0
        loadTokensAsync(working.servers.map { it.id })
    }

    /** PasswordSafe may block on the OS keychain, so tokens are loaded off the EDT. */
    private fun loadTokensAsync(ids: List<String>) {
        val modality = ModalityState.stateForComponent(serverList)
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = ids.associateWith { TokenStore.get(it).orEmpty() }
            ApplicationManager.getApplication().invokeLater({
                storedTokens.putAll(loaded)
                shown?.let { s -> if (s.id !in editedTokens) tokenField.text = storedTokens[s.id].orEmpty() }
            }, modality)
        }
    }

    private fun testConnection() {
        commitServerForm()
        val server = shown?.copy() ?: return
        val token = editedTokens[server.id] ?: storedTokens[server.id].orEmpty()
        testResult.text = JenkSpotterBundle.message("settings.testing")
        val modality = ModalityState.stateForComponent(testResult)
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                val user = JenkinsClient(server.url, server.username, token, JenkinsConnections.createHttpClient()).whoAmI()
                JenkSpotterBundle.message("settings.testOk", user.fullName, user.id)
            } catch (e: Exception) {
                JenkSpotterBundle.message("settings.testFailed", e.message ?: e.javaClass.simpleName)
            }
            ApplicationManager.getApplication().invokeLater({ testResult.text = text }, modality)
        }
    }
}

private fun <T> textRenderer(text: (T) -> String) = object : ColoredListCellRenderer<T>() {
    override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value != null) append(text(value))
    }
}
