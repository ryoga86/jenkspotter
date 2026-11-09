package de.pdenis.jenkspotter.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import de.pdenis.jenkspotter.JenkSpotterBundle
import de.pdenis.jenkspotter.api.BuildForm
import de.pdenis.jenkspotter.api.FormChoices
import de.pdenis.jenkspotter.api.ParameterDefinition
import de.pdenis.jenkspotter.api.ParameterType
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Build parameters form. Values come from (in this order): the build being rebuilt, the values
 * remembered for this job, the job defaults. Choices of Active Choices / credentials / run
 * parameters come from Jenkins' build form; cascades are re-evaluated when a referenced
 * parameter changes ([cascade] calls Jenkins off the EDT).
 */
class TriggerBuildDialog(
    project: Project,
    private val jobName: String,
    private val definitions: List<ParameterDefinition>,
    initialValues: Map<String, String>,
    private val form: BuildForm = BuildForm.EMPTY,
    private val cascade: ((name: String, referenced: Map<String, String>) -> FormChoices?)? = null,
    rememberByDefault: Boolean = true,
    titleText: String? = null,
    okText: String? = null,
) : DialogWrapper(project) {
    private val initial = initialValues
    private val readers = LinkedHashMap<String, () -> String>()
    private val choiceSetters = HashMap<String, (FormChoices) -> Unit>()
    private val changeListeners = HashMap<String, MutableList<() -> Unit>>()
    private val showRemember = okText == null
    private val remember = JBCheckBox(JenkSpotterBundle.message("dialog.trigger.remember"), rememberByDefault)

    init {
        title = titleText ?: JenkSpotterBundle.message("dialog.trigger.title", jobName)
        setOKButtonText(okText ?: JenkSpotterBundle.message("dialog.trigger.ok"))
        init()
        wireCascades()
    }

    val rememberValues: Boolean get() = remember.isSelected

    override fun createCenterPanel(): JComponent = panel {
        if (definitions.isEmpty()) {
            row { cell(JBLabel(JenkSpotterBundle.message("dialog.trigger.noParameters", jobName))) }
        }
        for (def in definitions) {
            val value = initial[def.name] ?: def.defaultValue ?: ""
            val choices = form.parameters[def.name]
            val row = row(def.name) { cell(editor(def, value, choices)).align(AlignX.FILL) }
            val hint = listOfNotNull(
                def.description,
                when (def.type) {
                    ParameterType.FILE -> JenkSpotterBundle.message("dialog.trigger.file")
                    ParameterType.RUN -> def.projectName?.let { JenkSpotterBundle.message("dialog.trigger.run", it) }
                    ParameterType.UNSUPPORTED -> JenkSpotterBundle.message("dialog.trigger.unsupported", def.rawType)
                    else -> null
                },
            ).joinToString(" - ")
            if (hint.isNotEmpty()) row.rowComment(hint)
        }
        if (definitions.isNotEmpty() && showRemember) row { cell(remember) }
    }

    private fun editor(def: ParameterDefinition, value: String, choices: FormChoices?): JComponent {
        val formChoices = choices?.takeIf { it.values.isNotEmpty() || it.cascade != null }
        return when {
            def.type == ParameterType.BOOLEAN -> JBCheckBox().apply {
                isSelected = value.toBoolean()
                readers[def.name] = { isSelected.toString() }
                addActionListener { fire(def.name) }
            }
            def.type == ParameterType.PASSWORD -> JBPasswordField().apply {
                // Jenkins never returns previous password values and they are never remembered.
                readers[def.name] = { String(password) }
            }
            def.type == ParameterType.TEXT -> {
                val area = JBTextArea(value, 4, 40)
                readers[def.name] = { area.text }
                JBScrollPane(area)
            }
            def.type == ParameterType.FILE -> JBTextField().apply {
                isEnabled = false
                readers[def.name] = { "" }
            }
            def.type == ParameterType.MULTI_CHOICE || formChoices?.multiple == true -> multiSelect(def, value, formChoices)
            def.type == ParameterType.CHOICE || formChoices != null -> combo(def, value, formChoices, editable = def.type != ParameterType.CHOICE)
            else -> JBTextField(value, 40).apply {
                readers[def.name] = { text }
                document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                    override fun textChanged(e: javax.swing.event.DocumentEvent) = fire(def.name)
                })
            }
        }
    }

    private data class Item(val value: String, val label: String) {
        override fun toString() = label
    }

    private fun combo(def: ParameterDefinition, value: String, choices: FormChoices?, editable: Boolean): JComponent {
        val combo = ComboBox<Any>()
        combo.isEditable = editable
        fun fill(c: FormChoices?, preferred: String) {
            val items = c?.values?.zip(c.labels)?.map { (v, l) -> Item(v, l) } ?: def.choices.map { Item(it, it) }
            combo.removeAllItems()
            items.forEach(combo::addItem)
            val pick = items.firstOrNull { it.value == preferred } ?: items.firstOrNull { it.value in c?.selected.orEmpty() } ?: items.firstOrNull()
            if (pick != null) combo.selectedItem = pick else if (editable) combo.editor.item = preferred
        }
        fill(choices, value)
        readers[def.name] = {
            when (val item = if (combo.isEditable) combo.editor.item else combo.selectedItem) {
                is Item -> item.value
                null -> ""
                // Typed text that matches a label maps back to its value.
                else -> (0 until combo.itemCount).map { combo.getItemAt(it) as Item }.firstOrNull { it.label == item.toString() }?.value ?: item.toString()
            }
        }
        choiceSetters[def.name] = { c -> fill(c, readers.getValue(def.name)()) }
        combo.addActionListener { fire(def.name) }
        return combo
    }

    private fun multiSelect(def: ParameterDefinition, value: String, choices: FormChoices?): JComponent {
        val model = CollectionListModel<Item>()
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            visibleRowCount = 5
        }
        fun fill(c: FormChoices?, preferred: Set<String>) {
            val items = c?.values?.zip(c.labels)?.map { (v, l) -> Item(v, l) } ?: def.choices.map { Item(it, it) }
            model.replaceAll(items)
            val wanted = preferred.ifEmpty { c?.selected.orEmpty() }
            list.selectedIndices = items.indices.filter { items[it].value in wanted }.toIntArray()
        }
        fill(choices, value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
        // Jenkins (Active Choices, extended choice) expects multiple values comma-separated.
        readers[def.name] = { list.selectedValuesList.joinToString(",") { it.value } }
        choiceSetters[def.name] = { c -> fill(c, readers.getValue(def.name)().split(',').filter { it.isNotEmpty() }.toSet()) }
        list.addListSelectionListener { if (!it.valueIsAdjusting) fire(def.name) }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JBScrollPane(list).apply { preferredSize = JBUI.size(280, 100) })
        }
    }

    private fun fire(name: String) {
        changeListeners[name]?.forEach { it() }
    }

    /** Re-evaluates Active Choices cascades whenever one of their referenced parameters changes. */
    private fun wireCascades() {
        val load = cascade ?: return
        for ((name, choices) in form.parameters) {
            val proxy = choices.cascade ?: continue
            val update = {
                val referenced = proxy.referencedParameters.associateWith { readers[it]?.invoke().orEmpty() }
                val modality = ModalityState.stateForComponent(contentPane)
                ApplicationManager.getApplication().executeOnPooledThread {
                    val next = runCatching { load(name, referenced) }.getOrNull()
                    if (next != null) ApplicationManager.getApplication().invokeLater({ choiceSetters[name]?.invoke(next) }, modality)
                }
            }
            proxy.referencedParameters.forEach { changeListeners.getOrPut(it) { mutableListOf() } += { update() } }
            // The rendered form only contains the initial cascade values; compute them for our defaults.
            update()
        }
    }

    /** Values to submit. Empty password fields are omitted so Jenkins falls back to the default. */
    fun values(): Map<String, String> = readers.mapValues { it.value() }
        .filterNot { (name, value) ->
            val type = definitions.first { it.name == name }.type
            (value.isEmpty() && type == ParameterType.PASSWORD) || type == ParameterType.FILE
        }

    /** What may be remembered: never passwords or file parameters. */
    fun rememberableValues(): Map<String, String> = values().filterKeys { name ->
        definitions.first { it.name == name }.type !in setOf(ParameterType.PASSWORD, ParameterType.FILE)
    }
}
