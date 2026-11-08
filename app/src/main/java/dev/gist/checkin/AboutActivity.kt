package dev.gist.checkin

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/** What Gist is, its version, privacy, the source code, and a way to donate. */
class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.add(header("About Gist", "Version ${versionName()}").apply { tag = "about-header" })

        val what = card()
        what.add(label("Alarms that won't stop until you check in.", 18f, bold = true))
        what.add(label(
            "Each check-in rings on your schedule and runs its steps (math, a scan, yes/no questions) " +
                "before it lets you go. It keeps a simple log, so you see the gist of your habits " +
                "without counting every calorie.", 15f), dp(6))
        col.add(what, dp(20))

        val privacy = card()
        privacy.add(sectionTitle("Privacy"))
        privacy.add(label(
            "Gist has no internet permission. Your check-ins and answers stay on this phone, and only " +
                "leave it when you export a backup or a CSV yourself. The buttons below open your browser.", 15f), dp(6))
        col.add(privacy, dp(12))

        val support = card()
        support.add(sectionTitle("Support Gist"))
        support.add(label("Gist is free and open source. If it helps you, consider a donation.", 15f), dp(6))
        support.add(primaryButton("♥ Donate") { open(Links.DONATE) }.apply { tag = "donate" }, dp(12))
        col.add(support, dp(12))

        val code = card()
        code.add(sectionTitle("Source code"))
        code.add(label("Read every line, build it yourself, or report a problem.", 15f), dp(6))
        code.add(outlineButton("Source code on GitHub") { open(Links.REPO) }.apply { tag = "source" }, dp(12))
        code.add(outlineButton("Report a problem") { open(Links.ISSUES) }, dp(8))
        col.add(code, dp(12))

        setContentView(screen(col))
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
    } catch (e: Exception) {
        "dev"
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found. The page is $url", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AboutActivity::class.java)
    }
}
