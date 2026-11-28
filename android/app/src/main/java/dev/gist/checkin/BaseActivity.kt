package dev.gist.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View

/**
 * Every screen extends this so the Light / Dark / System choice from Settings applies
 * everywhere, on every supported Android version, by overriding the night-mode bit of the
 * screen's configuration.
 *
 * Each screen works in a profile (see [Profiles]): the one its intent names, else the one
 * you're on. Screens it opens are for the same profile. The theme is the one you're on's.
 *
 * Every page has the ☰ menu ([NavDrawer]): its button is left of the page's title (see
 * [withProfileBadge]) and the menu goes over whatever [setContentView] shows. Not the alarm
 * screen or the scanner ([hasMenu]).
 */
open class BaseActivity : Activity(), ProfileScoped {
    private var appliedTheme = Store.THEME_SYSTEM

    /** Whether this page has the ☰ menu. */
    open val hasMenu: Boolean get() = true

    val drawer: NavDrawer by lazy { NavDrawer(this) }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (hasMenu) drawer.attach()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (hasMenu && drawer.isOpen) drawer.close() else super.onBackPressed()
    }

    override val profileId: String
        get() = intent?.getStringExtra(Profiles.EXTRA) ?: Profiles.current(this)

    /** The theme: the profile you're on's. */
    private fun theme(c: Context) = Store(Profiles.scoped(c, Profiles.current(c))).theme

    override fun startActivity(intent: Intent, options: Bundle?) {
        tag(intent)
        super.startActivity(intent, options)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        tag(intent)
        super.startActivityForResult(intent, requestCode, options)
    }

    /** A screen of this app opened from here is for this screen's profile (unless it says). */
    private fun tag(intent: Intent) {
        if (intent.component?.packageName == packageName && !intent.hasExtra(Profiles.EXTRA)) intent.putExtra(Profiles.EXTRA, profileId)
    }

    override fun attachBaseContext(newBase: Context) {
        appliedTheme = theme(newBase)
        val night = when (appliedTheme) {
            Store.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Store.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> null
        }
        if (night == null) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onResume() {
        super.onResume()
        // The theme was changed on another screen: redraw this one with it.
        if (theme(this) != appliedTheme) recreate()
    }

    /** Redraws with the theme if it changed meanwhile (say on another of your devices); true if so. */
    protected fun recreateForTheme(): Boolean {
        if (theme(this) == appliedTheme) return false
        recreate()
        return true
    }
}
