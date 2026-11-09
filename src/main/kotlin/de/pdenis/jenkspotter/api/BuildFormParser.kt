package de.pdenis.jenkspotter.api

import com.intellij.openapi.util.text.StringUtil

/**
 * Reads parameter values from Jenkins' rendered "Build with Parameters" page. Needed for
 * parameters whose values the JSON API does not expose: Active Choices (uno-choice), credentials
 * and run parameters. Deliberately tolerant: anything it does not understand is left out and the
 * dialog falls back to a text field.
 */
object BuildFormParser {
    private val NAME_INPUT = Regex("""<input\s+name="name"\s+type="hidden"\s+value="([^"]*)"""")
    private val OPTION = Regex("""<option\s+([^>]*)>(.*?)</option>""", RegexOption.DOT_MATCHES_ALL)
    private val INPUT = Regex("""<input\s+([^>]*)>""")
    private val ATTR = Regex("""([a-zA-Z-]+)="([^"]*)"""")
    private val CRUMB = Regex("""data-crumb-value="([^"]+)"""")
    private val HOLDER = Regex("""<span\s+([^>]*class="(?:cascade-choice-parameter|dynamic-reference-parameter)-data-holder"[^>]*)>""")
    private val PROXY_SCRIPT = Regex("""\${'$'}stapler/bound/([0-9a-f-]+)\?var=([A-Za-z0-9_]+)""")

    fun parse(html: String): BuildForm {
        val proxies = PROXY_SCRIPT.findAll(html).associate { it.groupValues[2] to it.groupValues[1] }
        val cascades = HOLDER.findAll(html).mapNotNull { m ->
            val attrs = attrs(m.groupValues[1])
            val name = attrs["data-name"] ?: return@mapNotNull null
            val proxy = attrs["data-proxy-name"]?.let(proxies::get) ?: return@mapNotNull null
            val refs = attrs["data-referenced-parameters"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            name to CascadeProxy(proxy, refs)
        }.toMap()

        val names = NAME_INPUT.findAll(html).toList()
        val params = LinkedHashMap<String, FormChoices>()
        names.forEachIndexed { index, match ->
            val name = StringUtil.unescapeXmlEntities(match.groupValues[1])
            val end = names.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(match.range.last + 1, end)
            val values = mutableListOf<String>()
            val labels = mutableListOf<String>()
            val selected = linkedSetOf<String>()
            var multiple = false
            for (o in OPTION.findAll(block)) {
                val a = attrs(o.groupValues[1])
                val value = a["value"] ?: continue
                values += value
                labels += StringUtil.unescapeXmlEntities(StringUtil.stripHtml(o.groupValues[2], false)).trim().ifEmpty { value }
                if ("selected" in a) selected += value
            }
            for (i in INPUT.findAll(block)) {
                val a = attrs(i.groupValues[1])
                val type = a["type"]
                if (type != "checkbox" && type != "radio") continue
                // Plain boolean parameters also render a checkbox, but without a json value.
                val value = a["json"] ?: continue
                if (type == "checkbox") multiple = true
                values += value
                labels += a["title"] ?: a["alt"] ?: value
                if ("checked" in a) selected += value
            }
            if (values.isNotEmpty() || name in cascades) {
                params[name] = FormChoices(values, labels, selected, multiple, cascades[name])
            }
        }
        return BuildForm(CRUMB.find(html)?.groupValues?.get(1)?.let(StringUtil::unescapeXmlEntities), params)
    }

    /** Values returned by an Active Choices proxy: [[values], [labels]], ":selected" marks defaults. */
    fun parseCascade(values: List<String>, labels: List<String>, multiple: Boolean, proxy: CascadeProxy): FormChoices {
        val selected = linkedSetOf<String>()
        val clean = values.map { v ->
            val stripped = v.removeSuffix(":selected:disabled").removeSuffix(":disabled:selected").removeSuffix(":selected").removeSuffix(":disabled")
            if (v.contains(":selected")) selected += stripped
            stripped
        }
        val cleanLabels = labels.mapIndexed { i, l -> l.removeSuffix(":selected").removeSuffix(":disabled").ifEmpty { clean.getOrElse(i) { l } } }
        return FormChoices(clean, if (cleanLabels.size == clean.size) cleanLabels else clean, selected, multiple, proxy)
    }

    private fun attrs(text: String): Map<String, String> {
        val map = ATTR.findAll(text).associate { it.groupValues[1] to StringUtil.unescapeXmlEntities(it.groupValues[2]) }.toMutableMap()
        // Boolean attributes without a value (e.g. `checked`, `selected`).
        Regex("""(?:^|\s)(checked|selected)(?=\s|$)""").findAll(text).forEach { map.putIfAbsent(it.groupValues[1], "") }
        return map
    }
}
