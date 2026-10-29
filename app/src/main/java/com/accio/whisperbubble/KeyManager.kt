package com.accio.whisperbubble

/**
 * Picks which API key to use and advances the cursor according to the
 * strategy selected in settings.
 *
 *  PER_REQUEST   - next key on every request
 *  EVERY_N       - next key after N requests
 *  TIME_INTERVAL - next key when the interval has elapsed
 *  ON_FAILURE    - keep using the current key until a request fails
 */
object KeyManager {

    @Synchronized
    fun current(): String? {
        val keys = Prefs.keys
        if (keys.isEmpty()) return null
        val idx = Prefs.keyIndex.mod(keys.size)

        if (Prefs.rotation == Rotation.TIME_INTERVAL) {
            val now = System.currentTimeMillis()
            val last = Prefs.lastRotateAt
            if (last == 0L) {
                Prefs.lastRotateAt = now
            } else if (now - last >= Prefs.intervalMinutes * 60_000L) {
                val next = (idx + 1).mod(keys.size)
                Prefs.keyIndex = next
                Prefs.lastRotateAt = now
                Prefs.requestCount = 0
                return keys[next]
            }
        }
        return keys[idx]
    }

    /** Called after a request succeeded, to advance per the strategy. */
    @Synchronized
    fun onSuccess() {
        val size = Prefs.keys.size
        if (size <= 1) return
        when (Prefs.rotation) {
            Rotation.PER_REQUEST -> {
                Prefs.keyIndex = (Prefs.keyIndex + 1).mod(size)
                Prefs.requestCount = 0
            }
            Rotation.EVERY_N -> {
                val count = Prefs.requestCount + 1
                if (count >= Prefs.everyN) {
                    Prefs.requestCount = 0
                    Prefs.keyIndex = (Prefs.keyIndex + 1).mod(size)
                } else {
                    Prefs.requestCount = count
                }
            }
            Rotation.TIME_INTERVAL -> {
                if (Prefs.lastRotateAt == 0L) Prefs.lastRotateAt = System.currentTimeMillis()
            }
            Rotation.ON_FAILURE -> Unit
        }
    }

    /** Called when a key was rejected, so the next attempt uses a different one. */
    @Synchronized
    fun onFailure() {
        val size = Prefs.keys.size
        if (size <= 1) return
        Prefs.keyIndex = (Prefs.keyIndex + 1).mod(size)
        Prefs.requestCount = 0
    }

    fun mask(key: String): String =
        if (key.length <= 12) key.take(4) + "…"
        else key.take(6) + "…" + key.takeLast(4)
}
