package dev.gist.checkin

/** Web pages the app links to (opened in the browser; Gist itself never goes online). */
object Links {
    const val REPO = "https://github.com/gohenderson/gist-accountability"
    /** The README's "Donate" section (its heading is "## Donate"). */
    const val DONATE = "$REPO#donate"
    const val ISSUES = "$REPO/issues"
    const val LICENSE = "$REPO/blob/main/LICENSE"
    /** Syncthing (in Gist for Easy Connect): its source, under the Mozilla Public License 2.0. */
    const val SYNCTHING = "https://github.com/syncthing/syncthing"
}
