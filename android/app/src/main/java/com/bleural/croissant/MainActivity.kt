package com.bleural.croissant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.key
import com.bleural.croissant.ui.Designs

/**
 * The host: the window, the permissions, and the hardware keys. What a press means is
 * [Core]'s (the side button) or the UI's (the wheel); what the app can do is [Core]'s;
 * what it looks like is [UiShell]'s. This file is the only place the three meet.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * What the side button produces on the R1: device/keylayout/mtk-kpd.kl maps
         * its scancode (116, KEY_POWER) to F12 so the framework leaves it to us. Not
         * CAMERA: the window policy never lets CAMERA wake a dark screen.
         */
        private const val PTT_KEYCODE = KeyEvent.KEYCODE_F12
    }

    /** Every design the device knows, handed to Core, which builds the one chosen in Settings. */
    private val core by lazy { Core(this, Designs::build) }

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) core.bind()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        core.create()
        goFullScreen()
        core.openLink(intent)

        // Keyed on the design, so a swap composes the new one from nothing rather than
        // letting it inherit the old one's remembered state by position.
        setContent { key(core.ui) { core.ui.Content(core) } }

        val needed = buildList {
            if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (!has(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!has(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needed.isEmpty()) core.bind() else requestPerms.launch(needed.toTypedArray())
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        core.openLink(intent)
    }

    // ---- the window --------------------------------------------------------

    /**
     * Lock task hides home and recents but leaves the 3-button nav bar (back only) and
     * keeps the disabled status bar's band reserved, so without this the app gets
     * 480x556 of the 480x640 panel. Both bars are hidden and the content is laid out
     * edge to edge; a swipe from an edge shows them transiently, which is acceptable.
     */
    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        // The keyboard brings the nav bar back and leaves it there when it goes. Whenever
        // the insets change with the IME down and a bar showing, hide the bars again.
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            if (!insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                insets.isVisible(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.statusBars())
            ) hideBars()
            insets
        }
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A dialog or the IME can bring the bars back; re-hide whenever we regain focus.
        if (hasFocus) hideBars()
    }

    // ---- hardware keys -----------------------------------------------------

    /**
     * The scroll wheel. On the R1, device/keylayout/och1970_holl_key.kl maps it to F11
     * (up) and F10 (down) rather than the stock DPAD_UP/DOWN, because the framework
     * treats DPAD as navigation: while in touch mode (after any tap) the first press is
     * swallowed just to leave touch mode, and Compose then walks focus through every
     * clickable row (with the focus sound) before the key ever reaches the app. Function
     * keys are nothing to anyone, so every detent arrives here. DPAD is kept for the
     * emulator. Taken in dispatchKeyEvent, ahead of the view tree.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_DPAD_DOWN -> +1
            else -> return super.dispatchKeyEvent(event)
        }
        // Taken all the same while an update installs, and nothing comes of it.
        if (event.action == KeyEvent.ACTION_DOWN && !core.updating) core.ui.wheel(delta)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != PTT_KEYCODE) return super.onKeyDown(keyCode, event)
        // Repeats are the same hold.
        if (event.repeatCount == 0) core.pttDown()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != PTT_KEYCODE) return super.onKeyUp(keyCode, event)
        core.pttUp()
        return true
    }
}
