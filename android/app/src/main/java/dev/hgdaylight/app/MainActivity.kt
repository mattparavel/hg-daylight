package dev.hgdaylight.app

import android.app.Activity
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.hgdaylight.rom.NdsRom
import java.io.File
import kotlin.concurrent.thread

/**
 * Starts the field: finds the player's ROM (imported once into the app's files, never shipped in the APK),
 * checks it is SoulSilver (US), and shows the walk-around demo full screen.
 *
 * Import: the file picker, or for development `adb push soulsilver.us.nds
 * /sdcard/Android/data/dev.hgdaylight.ss/files/` and launch.
 */
class MainActivity : Activity() {
    private var view: GLSurfaceView? = null
    private lateinit var status: TextView
    private val romFile by lazy { File(filesDir, "rom/soulsilver.us.nds") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFF202020.toInt())
            setBackgroundColor(0xFFF4F4F4.toInt())
            text = "Reading cartridge…"
        }
        setContentView(FrameLayout(this).apply { addView(status) })
        immersive()
        start()
    }

    private fun start() {
        thread(name = "load") {
            val dropped = getExternalFilesDir(null)?.let { File(it, "soulsilver.us.nds") }
            if (!romFile.exists() && dropped != null && dropped.exists()) importFrom(dropped)
            if (!romFile.exists()) {
                runOnUiThread { status.text = "Choose your Pokémon SoulSilver (US) ROM"; pick() }
                return@thread
            }
            try {
                val rom = NdsRom.load(romFile)
                val game = FieldGame(rom)
                runOnUiThread { show(game) }
            } catch (t: Throwable) {
                Log.e(TAG, "load", t)
                runOnUiThread { status.text = "Could not start: ${t.message}" }
            }
        }
    }

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, PICK)
    }

    @Deprecated("Activity result API is not used in this small activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK || resultCode != RESULT_OK || uri == null) return
        status.text = "Checking the cartridge…"
        thread(name = "import") {
            val tmp = File(cacheDir, "import.nds")
            contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            importFrom(tmp)
            tmp.delete()
            if (romFile.exists()) start() else runOnUiThread { status.text = "That is not Pokémon SoulSilver (US).\nTap to choose again."; status.setOnClickListener { pick() } }
        }
    }

    /** Copies [src] in if its SHA-1 is the US SoulSilver the decomp matches; atomically (tmp, then rename). */
    private fun importFrom(src: File) {
        val sha = NdsRom.sha1(src)
        if (sha != NdsRom.SOULSILVER_US_SHA1) {
            Log.w(TAG, "rejected ROM $sha")
            return
        }
        romFile.parentFile?.mkdirs()
        val tmp = File(romFile.path + ".tmp")
        src.copyTo(tmp, overwrite = true)
        tmp.renameTo(romFile)
    }

    private fun show(game: FieldGame) {
        val joystick = Joystick(resources.displayMetrics.density)
        game.input = joystick
        val v = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean = joystick.onTouch(event)
        }
        v.setEGLContextClientVersion(3)
        v.preserveEGLContextOnPause = true
        v.setRenderer(FieldRenderer(game))
        v.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        view = v
        val panel = PanelView(this, game.touchMenu)
        // The icons' screens (Pokégear, Pokémon, Bag, ...) are not built yet; a press only highlights.
        panel.onIcon = { Log.i(TAG, "touch menu icon $it") }
        setContentView(FrameLayout(this).apply {
            addView(v)
            addView(panel)
        })
        immersive()
        prefer60Hz()
    }

    /** The game ticks at 60 Hz; ask for the display mode nearest 60 (the DC-1 defaults to 90), as DQ does. */
    private fun prefer60Hz() {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return
        val mode = display.supportedModes.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }

    private fun immersive() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } ?: run {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onPause() { super.onPause(); view?.onPause() }
    override fun onResume() { super.onResume(); view?.onResume() }

    companion object {
        private const val TAG = "SSDaylight"
        private const val PICK = 1
    }
}
