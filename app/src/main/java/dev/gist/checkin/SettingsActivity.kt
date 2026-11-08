package dev.gist.checkin

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
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
        col.add(label("Settings", 26f, bold = true))

        // Appearance
        col.add(label("Appearance", 20f, bold = true), dp(28))
        val group = RadioGroup(this)
        val options = listOf(
            Store.THEME_SYSTEM to "Same as phone",
            Store.THEME_LIGHT to "Light",
            Store.THEME_DARK to "Dark",
        )
        val ids = options.map { (mode, text) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                isChecked = store.theme == mode
            }
            group.addView(rb)
            rb.id to mode
        }.toMap()
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = ids[checkedId] ?: return@setOnCheckedChangeListener
            if (mode != store.theme) {
                store.theme = mode
                recreate()
            }
        }
        col.add(group, dp(4))

        // Backup
        col.add(label("Backup", 20f, bold = true), dp(28))
        col.add(
            label("One file with all your check-ins, their answers and background photos. " +
                "Keep it somewhere safe (Drive, email to yourself) to move to a new phone.", 14f),
            dp(4)
        )
        col.add(button("Export backup…") { exportBackup() }, dp(8))
        col.add(button("Import backup…") { importBackup() }, dp(4))

        // CSV
        col.add(label("Spreadsheet", 20f, bold = true), dp(28))
        col.add(label("All answers as a CSV file, one row per day.", 14f), dp(4))
        col.add(button("Export answers (CSV)…") { exportCsv() }, dp(8))

        val sv = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(sv, dp(20))
        setContentView(sv)
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
        val cols = store.checkins.flatMap { c -> c.questions.map { q -> c to q } }
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder()
        sb.append("date")
        cols.forEach { (c, q) -> sb.append(',').append(esc("${c.name}: ${q.text}")) }
        sb.append('\n')
        store.allEntries().forEach { (day, e) ->
            sb.append(day)
            cols.forEach { (_, q) ->
                sb.append(',')
                e[q.id]?.let { sb.append(if (it) "yes" else "no") }
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
