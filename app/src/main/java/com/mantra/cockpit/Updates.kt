package com.mantra.cockpit

/**
 * WHETHER THERE IS A NEWER BUILD, decided in pure code so it can be got wrong on a desk rather
 * than on a phone. Copied from MINIMALIST_STOPWATCH (13.9.2026), the repository name changed.
 *
 * A tag is a string; a version is a number; "v9" is newer than "v35" if anybody compares them as
 * text, and that fault appears exactly once, at version 10, and then never announces itself again.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: Int) : UpdateState
    data class Available(val version: Int, val url: String) : UpdateState
    data class Failed(val why: String) : UpdateState
}

object Updates {

    // The repository is PRIVATE until Marko says otherwise, so this answers 404 to an anonymous
    // request: the check then says "github 404 (a private repository)" rather than lying about
    // being up to date. When the repository goes public the same line starts working.
    const val LATEST_RELEASE =
        "https://api.github.com/repos/markoboskoauroville/COCKPIT_ANDROID/releases/latest"

    fun versionOf(tag: String?): Int? {
        val t = tag?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (t.isEmpty() || t.any { !it.isDigit() }) return null
        return t.toIntOrNull()
    }

    fun compare(current: Int, tag: String?, url: String?): UpdateState {
        val latest = versionOf(tag) ?: return UpdateState.Failed("no version in the release")
        return when {
            latest > current && url != null -> UpdateState.Available(latest, url)
            else -> UpdateState.UpToDate(maxOf(current, latest))
        }
    }

    fun describe(state: UpdateState, current: Int): String = when (state) {
        UpdateState.Idle -> "v$current"
        UpdateState.Checking -> "checking"
        is UpdateState.UpToDate -> "v$current  newest"
        is UpdateState.Available -> "v${state.version} ready \u2193"
        is UpdateState.Failed -> "v$current  " + state.why
    }
}
