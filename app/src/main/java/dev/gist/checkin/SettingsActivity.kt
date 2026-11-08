package dev.gist.checkin

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import java.time.LocalDate

/** Appearance, backup / restore, and CSV export. */
class SettingsActivity : BaseActivity() {
    private lateinit var store: Store
    private var pendingExport: List<Backup.ExportChoice>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        render()
    }

    private fun render() {
        val col = column()
        col.add(header("Settings"))

        // Appearance
        val look = card()
        look.add(sectionTitle("Appearance"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val options = listOf(
            Store.THEME_SYSTEM to "System",
            Store.THEME_LIGHT to "Light",
            Store.THEME_DARK to "Dark",
        )
        val ids = options.mapIndexed { i, (mode, text) ->
            val rb = choiceTile(text).apply {
                id = View.generateViewId()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                isChecked = store.theme == mode
            }
            group.addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
            rb.id to mode
        }.toMap()
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = ids[checkedId] ?: return@setOnCheckedChangeListener
            if (mode != store.theme) {
                store.theme = mode
                recreate()
            }
        }
        look.add(group, dp(10))
        col.add(look, dp(20))

        // Backup
        val backup = card()
        backup.add(sectionTitle("Backup"))
        backup.add(
            label("A YAML file with the check-ins you choose, their backgrounds (photos and videos) and, if you " +
                "like, their history. Keep it somewhere safe (Drive, email to yourself) to move to a new phone " +
                "or share a check-in.", 15f),
            dp(6)
        )
        val b = row()
        b.addWeighted(primaryButton("Export backup…") { exportBackup() })
        b.addWeighted(outlineButton("Import backup…") { importBackup() }, startMargin = dp(8))
        backup.add(b, dp(12))
        col.add(backup, dp(12))

        // CSV
        val csv = card()
        csv.add(sectionTitle("Spreadsheet"))
        csv.add(label("Every result as a CSV file: one row per day, check-in and time.", 15f), dp(6))
        csv.add(outlineButton("Export results (CSV)…") { exportCsv() }, dp(12))
        col.add(csv, dp(12))

        setContentView(screen(col))
    }

    // ---------------------------------------------------------------- backup

    private fun exportBackup() {
        if (store.checkins.isEmpty()) {
            toast("There are no check-ins to export.")
            return
        }
        showExportChoices { choices -> pickExportFile(choices) }
    }

    @Suppress("DEPRECATION")
    private fun pickExportFile(choices: List<Backup.ExportChoice>) {
        pendingExport = choices
        val one = choices.singleOrNull()?.let { store.checkin(it.checkinId)?.name }
        val slug = one?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.ifEmpty { null }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/yaml")
                .putExtra(Intent.EXTRA_TITLE, "gist-${slug ?: "backup"}-${LocalDate.now()}.yaml"),
            REQ_EXPORT_BACKUP
        )
    }

    /**
     * Which check-ins to export, and for each whether to include its history. Calls
     * [onChosen] with the choices (at least one check-in).
     */
    fun showExportChoices(onChosen: (List<Backup.ExportChoice>) -> Unit) {
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val rows = store.checkins.map { c ->
            val days = store.historyJson(c.id).length()
            val include = CheckBox(this).apply {
                text = c.name
                isChecked = true
                tag = "export:${c.id}"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            }
            val history = CheckBox(this).apply {
                text = if (days == 0) "No history yet" else "Include history ($days day${if (days == 1) "" else "s"})"
                isChecked = days > 0
                isEnabled = days > 0
                tag = "export-history:${c.id}"
            }
            include.setOnCheckedChangeListener { _, on -> history.isEnabled = on && days > 0 }
            box.add(include, dp(8))
            box.add(history.apply { setPadding(paddingLeft + dp(8), 0, 0, 0) }, dp(0))
            Triple(c.id, include, history)
        }
        AlertDialog.Builder(this)
            .setTitle("Export which check-ins?")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Export…") { _, _ ->
                val choices = rows.filter { it.second.isChecked }
                    .map { (id, _, h) -> Backup.ExportChoice(id, h.isChecked && h.isEnabled) }
                if (choices.isEmpty()) toast("Nothing selected.") else onChosen(choices)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun importBackup() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml",
                    "text/plain", "application/octet-stream",
                )),
            REQ_IMPORT_BACKUP
        )
    }

    /**
     * For each check-in in the file: import it or not; if you already have it, whether to
     * update, replace or add a copy; and whether to bring its history.
     */
    fun showImportChoices(contents: Backup.Contents) {
        val local = store.checkins
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.add(muted("Check-ins you don't pick, and ones on this phone that aren't in the file, are left alone.", 13f))
        class Row(val id: String, val include: CheckBox, val modes: Map<Backup.Mode, RadioButton>, val history: CheckBox)
        val rows = contents.checkins.map { c ->
            val match = Backup.match(local, c)
            val days = contents.historyDays(c.id)
            val card = card()
            val include = CheckBox(this).apply {
                text = c.name
                isChecked = true
                tag = "import:${c.id}"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            }
            card.add(include)
            card.add(muted(CheckinText.oneLine(c), 13f), dp(0))
            val modes = mutableMapOf<Backup.Mode, RadioButton>()
            val group = RadioGroup(this)
            if (match == null) {
                card.add(label("New on this phone", 14f, color = colorGood), dp(6))
            } else {
                card.add(label("You already have “${match.name}”:", 14f, bold = true), dp(8))
                listOf(
                    Backup.Mode.UPDATE to "Update it: settings from the file, keep its history",
                    Backup.Mode.REPLACE to "Replace it, history too",
                    Backup.Mode.COPY to "Keep it and add this as a copy",
                ).forEach { (mode, text) ->
                    val rb = RadioButton(this).apply {
                        id = View.generateViewId()
                        this.text = text
                        tag = "import-mode:${c.id}:${mode.name.lowercase()}"
                        isChecked = mode == Backup.Mode.UPDATE
                    }
                    group.addView(rb)
                    modes[mode] = rb
                }
                card.add(group, dp(2))
            }
            val history = CheckBox(this).apply {
                text = if (days == 0) "No history in the file" else "Import its history ($days day${if (days == 1) "" else "s"})"
                isChecked = days > 0
                isEnabled = days > 0
                tag = "import-history:${c.id}"
            }
            card.add(history, dp(4))
            include.setOnCheckedChangeListener { _, on ->
                history.isEnabled = on && days > 0
                modes.values.forEach { it.isEnabled = on }
            }
            box.add(card, dp(12))
            Row(c.id, include, modes, history)
        }
        AlertDialog.Builder(this)
            .setTitle("Import from backup")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Import") { _, _ ->
                val choices = rows.filter { it.include.isChecked }.map { r ->
                    val mode = r.modes.entries.firstOrNull { it.value.isChecked }?.key ?: Backup.Mode.NEW
                    Backup.ImportChoice(r.id, mode, r.history.isChecked && r.history.isEnabled)
                }
                if (choices.isEmpty()) {
                    toast("Nothing imported.")
                    return@setPositiveButton
                }
                try {
                    val done = Backup.import(this, contents, choices)
                    toast("Imported ${done.size} check-in${if (done.size == 1) "" else "s"}: ${done.joinToString { it.name }}. Alarms are set.")
                } catch (e: Exception) {
                    toast("Import failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- CSV

    @Suppress("DEPRECATION")
    private fun exportCsv() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, "gist-log-${Days.today()}.csv"),
            REQ_EXPORT_CSV
        )
    }

    private fun buildCsv(): String {
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val checkins = store.checkins
        val qs = checkins.flatMap { it.questions }
        val sb = StringBuilder()
        sb.append("date,time,check-in,status,early")
        qs.forEach { sb.append(',').append(esc(it.text)) }
        sb.append('\n')
        val rows = checkins.flatMap { c ->
            store.allResults(c.id).flatMap { (day, slots) -> slots.map { (time, r) -> Triple(day, time, c to r) } }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        for ((day, time, cr) in rows) {
            val (c, r) = cr
            sb.append(day).append(',').append(time).append(',').append(esc(c.name))
                .append(',').append(if (r.status == SlotResult.RANG) "missed" else r.status)
                .append(',').append(if (r.early) "yes" else "")
            qs.forEach { q ->
                sb.append(',')
                r.answers[q.id]?.let { sb.append(if (it) "yes" else "no") }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- results

    @Deprecated("Platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        // Only accept documents from the system file picker (content:// from another app's
        // provider), never a file path, one of this app's own providers, or anything whose
        // normalized path points into private storage.
        if (uri.scheme != "content" || uri.authority.orEmpty().startsWith(packageName)) {
            toast("That file location isn't supported.")
            return
        }
        val path = uri.path
        if (path.isNullOrBlank()) {
            toast("That file location isn't supported.")
            return
        }
        val normalized = java.nio.file.FileSystems.getDefault().getPath(path).normalize()
        if (normalized.startsWith("/data") || normalized.any { it.toString() == ".." }) {
            toast("That file location isn't supported.")
            return
        }
        try {
            when (requestCode) {
                REQ_EXPORT_BACKUP -> {
                    val choices = pendingExport ?: Backup.allChoices(this)
                    pendingExport = null
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(Backup.export(this, choices).toByteArray()) }
                    toast("Backup saved")
                }
                REQ_IMPORT_BACKUP -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalArgumentException("Couldn't read the file.")
                    showImportChoices(Backup.parse(this, text))
                }
                REQ_EXPORT_CSV -> {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(buildCsv().toByteArray()) }
                    toast("CSV saved")
                }
            }
        } catch (e: IllegalArgumentException) {
            AlertDialog.Builder(this).setTitle("Can't import").setMessage(e.message).setPositiveButton("OK", null).show()
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_EXPORT_BACKUP = 11
        private const val REQ_IMPORT_BACKUP = 12
        private const val REQ_EXPORT_CSV = 13
    }
}
