package dev.gist.checkin

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import java.time.LocalDate

/** Appearance, backup / restore, and CSV export. */
class SettingsActivity : BaseActivity() {
    private lateinit var store: Store

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
            label("One file with all your check-ins, their results and background photos. " +
                "Keep it somewhere safe (Drive, email to yourself) to move to a new phone.", 15f),
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

    @Suppress("DEPRECATION")
    private fun exportBackup() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "gist-backup-${LocalDate.now()}.json"),
            REQ_EXPORT_BACKUP
        )
    }

    @Suppress("DEPRECATION")
    private fun importBackup() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")),
            REQ_IMPORT_BACKUP
        )
    }

    private fun confirmRestore(contents: Backup.Contents) {
        val now = store.checkins.size
        AlertDialog.Builder(this)
            .setTitle("Replace everything?")
            .setMessage(
                "Your $now check-in${if (now == 1) "" else "s"} and all logged answers will be replaced by the " +
                    "backup's ${contents.checkins.size} check-in${if (contents.checkins.size == 1) "" else "s"} " +
                    "and ${contents.days} day${if (contents.days == 1) "" else "s"} of answers. This can't be undone, " +
                    "so export a backup of this phone first if you might need it."
            )
            .setPositiveButton("Replace") { _, _ ->
                try {
                    Backup.restore(this, contents)
                    toast("Restored ${contents.checkins.size} check-ins. Alarms are set.")
                } catch (e: Exception) {
                    toast("Restore failed: ${e.message}")
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
        // provider), never a file path or one of this app's own providers.
        if (uri.scheme != "content" || uri.authority.orEmpty().startsWith(packageName)) {
            toast("That file location isn't supported.")
            return
        }
        try {
            when (requestCode) {
                REQ_EXPORT_BACKUP -> {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(Backup.export(this).toByteArray()) }
                    toast("Backup saved")
                }
                REQ_IMPORT_BACKUP -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalArgumentException("Couldn't read the file.")
                    confirmRestore(Backup.parse(this, text))
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
