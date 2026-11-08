package dev.gist.checkin

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * Every screen extends this so the Light / Dark / System choice from Settings applies
 * everywhere, on every supported Android version, by overriding the night-mode bit of the
 * screen's configuration.
 */
open class BaseActivity : Activity() {
    private var appliedTheme = Store.THEME_SYSTEM

    override fun attachBaseContext(newBase: Context) {
        appliedTheme = Store(newBase).theme
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
        if (Store(this).theme != appliedTheme) recreate()
    }
}
