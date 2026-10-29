package com.accio.whisperbubble

import android.content.Context
import android.content.SharedPreferences

enum class Rotation { PER_REQUEST, EVERY_N, TIME_INTERVAL, ON_FAILURE }
enum class Trigger { HOLD, TAP }
enum class ThemeMode { AUTO, LIGHT, DARK }

/**
 * Thin wrapper over SharedPreferences. All values are read on demand so the
 * services always see the latest settings without any observer plumbing.
 */
object Prefs {

    private const val FILE = "bubble_prefs"
    private const val SEP = "\u0001"

    @Volatile
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences = sp ?: error("Prefs.init not called")

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        val e = p().edit()
        e.block()
        e.apply()
    }

    var keys: List<String>
        get() = p().getString("keys", "").orEmpty()
            .split(SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) = put { putString("keys", value.joinToString(SEP)) }

    var rotation: Rotation
        get() = runCatching {
            Rotation.valueOf(p().getString("rotation", Rotation.ON_FAILURE.name)!!)
        }.getOrDefault(Rotation.ON_FAILURE)
        set(value) = put { putString("rotation", value.name) }

    var everyN: Int
        get() = p().getInt("every_n", 3).coerceAtLeast(1)
        set(value) = put { putInt("every_n", value.coerceAtLeast(1)) }

    var intervalMinutes: Int
        get() = p().getInt("interval_min", 120).coerceAtLeast(1)
        set(value) = put { putInt("interval_min", value.coerceAtLeast(1)) }

    var trigger: Trigger
        get() = runCatching {
            Trigger.valueOf(p().getString("trigger", Trigger.HOLD.name)!!)
        }.getOrDefault(Trigger.HOLD)
        set(value) = put { putString("trigger", value.name) }

    var onlyWhenFocused: Boolean
        get() = p().getBoolean("only_focused", true)
        set(value) = put { putBoolean("only_focused", value) }

    /** How long the bubble must be held before it starts listening. */
    var pressDelayMs: Int
        get() = p().getInt("press_delay", 150).coerceIn(0, 1000)
        set(value) = put { putInt("press_delay", value.coerceIn(0, 1000)) }

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(p().getString("theme", ThemeMode.AUTO.name)!!)
        }.getOrDefault(ThemeMode.AUTO)
        set(value) = put { putString("theme", value.name) }

    var language: String
        get() = p().getString("language", "").orEmpty()
        set(value) = put { putString("language", value.trim()) }

    /** Set when the user pressed Start, so the app can explain the current state. */
    var running: Boolean
        get() = p().getBoolean("running", false)
        set(value) = put { putBoolean("running", value) }

    // --- key rotation bookkeeping (internal) ---
    var keyIndex: Int
        get() = p().getInt("key_index", 0)
        set(value) = put { putInt("key_index", value) }

    var lastRotateAt: Long
        get() = p().getLong("last_rotate", 0L)
        set(value) = put { putLong("last_rotate", value) }

    var requestCount: Int
        get() = p().getInt("req_count", 0)
        set(value) = put { putInt("req_count", value) }

    // --- bubble position ---
    var bubbleX: Int
        get() = p().getInt("bx", -1)
        set(value) = put { putInt("bx", value) }

    var bubbleY: Int
        get() = p().getInt("by", -1)
        set(value) = put { putInt("by", value) }
}
