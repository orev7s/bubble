package com.accio.whisperbubble

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * Watches for a focused editable field (so the bubble can appear) and writes
 * the transcript into that field.
 *
 * The last focused field is remembered, so a result still lands correctly if
 * the keyboard was closed while the clip was uploading.
 */
class InputWatchService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var hideTask: Runnable? = null
    private var reportedFocused = false
    private var lastTarget: AccessibilityNodeInfo? = null
    private var lastEditablePackage = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.post { hideNow() }
        }
    }

    private val insertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(OverlayService.EXTRA_TEXT) ?: return
            if (text.isBlank()) return
            handler.post { report(inject(text)) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this,
            insertReceiver,
            IntentFilter(OverlayService.ACTION_INSERT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.post { notifyFocus(findEditable() != null) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg == packageName) return

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onWindowChanged(pkg)
            return
        }

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (findEditable() != null) notifyFocus(true) else scheduleHideCheck()
            }
        }
    }

    /**
     * A window change is one of: the keyboard opening, another app coming to
     * the front, or the field going away. The bubble must go on the last two,
     * and it must go at once.
     */
    private fun onWindowChanged(pkg: String) {
        if (isIme(pkg)) return

        if (findEditable() != null) {
            notifyFocus(true)
            return
        }

        if (!reportedFocused) return

        if (pkg == lastEditablePackage) {
            // Same app still in front; allow for a dialog or a re-layout.
            scheduleHideCheck()
        } else {
            hideNow()
        }
    }

    private fun hideNow() {
        hideTask?.let { handler.removeCallbacks(it) }
        hideTask = null
        if (!reportedFocused) return
        reportedFocused = false
        broadcastFocus(false)
    }

    private fun isIme(pkg: String): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return runCatching {
            imm.enabledInputMethodList.any { it.packageName == pkg }
        }.getOrDefault(false)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(insertReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        lastTarget = null
        super.onDestroy()
    }

    // --------------------------------------------------------------- focus

    private fun notifyFocus(focused: Boolean) {
        if (focused) {
            hideTask?.let { handler.removeCallbacks(it) }
            hideTask = null
            if (!reportedFocused) {
                reportedFocused = true
                broadcastFocus(true)
            }
        } else {
            scheduleHideCheck()
        }
    }

    /** Focus often drops briefly while a window animates, so confirm before hiding. */
    private fun scheduleHideCheck() {
        if (!reportedFocused) return
        hideTask?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            if (findEditable() == null) {
                reportedFocused = false
                broadcastFocus(false)
            }
        }
        hideTask = task
        handler.postDelayed(task, 150)
    }

    private fun broadcastFocus(focused: Boolean) {
        sendBroadcast(
            Intent(OverlayService.ACTION_FOCUS)
                .setPackage(packageName)
                .putExtra(OverlayService.EXTRA_FOCUSED, focused)
        )
    }

    // -------------------------------------------------------------- target

    private fun findEditable(): AccessibilityNodeInfo? = runCatching {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && focused.isVisibleToUser) {
            lastTarget = focused
            lastEditablePackage = focused.packageName?.toString().orEmpty()
            return focused
        }
        val found = search(root, 0)
        if (found != null) {
            lastTarget = found
            lastEditablePackage = found.packageName?.toString().orEmpty()
        }
        return found
    }.getOrNull()

    private fun search(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 14) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val hit = search(child, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    /** Prefers the remembered node (it survives the keyboard closing), then live focus. */
    private fun targetForInsert(): AccessibilityNodeInfo? {
        val cached = lastTarget
        if (cached != null) {
            val alive = runCatching { cached.refresh() }.getOrDefault(false)
            if (alive && runCatching { cached.isEditable }.getOrDefault(false)) return cached
        }
        return findEditable()
    }

    // --------------------------------------------------------------- inject

    private fun inject(text: String): Boolean {
        val node = targetForInsert() ?: return false

        val base = existing(node)
        val merged = when {
            base.isEmpty() -> text
            base.last().isWhitespace() -> base + text
            else -> "$base $text"
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                merged
            )
        }
        val written = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)

        if (written) {
            moveCursorToEnd(node, merged.length)
            return true
        }

        // Some app-owned editors reject ACTION_SET_TEXT; fall back to pasting.
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = runCatching {
            clip.setPrimaryClip(ClipData.newPlainText("Bubble", merged))
        }.isSuccess

        if (!copied) return false

        val focused = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }.getOrDefault(false)

        if (!focused) return false

        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    /**
     * Placeholder text ("Search YouTube", "Message") is reported as the field's
     * text by several apps, so treat a match with the hint as an empty field.
     */
    private fun existing(node: AccessibilityNodeInfo): String {
        val current = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        if (current.isEmpty()) return ""

        val hint = runCatching { node.hintText?.toString().orEmpty() }.getOrDefault("")
        if (hint.isNotEmpty() && current == hint) return ""

        val className = runCatching { node.className?.toString().orEmpty() }.getOrDefault("")
        val id = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
        if (className.contains("EditText") && current == id) return ""

        return current
    }

    private fun moveCursorToEnd(node: AccessibilityNodeInfo, length: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
        }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args) }
    }

    private fun report(ok: Boolean) {
        sendBroadcast(
            Intent(OverlayService.ACTION_INSERT_RESULT)
                .setPackage(packageName)
                .putExtra(OverlayService.EXTRA_OK, ok)
        )
    }
}
