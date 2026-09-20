package com.elchanan.rhythm.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * The play, next and previous keys on a keyboard.
 *
 * Android gave these away: a MediaSession is registered with the system and
 * the keys arrive as transport commands without a line of code. Windows has
 * no equivalent for a JVM, so they are claimed directly - RegisterHotKey
 * against the media key virtual codes, and a thread sitting on the message
 * queue they are posted to.
 *
 * Global rather than while focused, which is the point: a media key pressed
 * while reading something else is exactly when one is pressed.
 *
 * Everything here is best effort. On anything but Windows, or if the library
 * will not load, it simply never starts - the keys do nothing, which is what
 * they did before, and nothing else in the app notices.
 */
object MediaKeys {

    private const val WM_HOTKEY = 0x0312
    private const val VK_MEDIA_NEXT_TRACK = 0xB0
    private const val VK_MEDIA_PREV_TRACK = 0xB1
    private const val VK_MEDIA_PLAY_PAUSE = 0xB3

    private const val ID_PLAY = 1
    private const val ID_NEXT = 2
    private const val ID_PREVIOUS = 3

    private interface User32 : StdCallLibrary {
        fun RegisterHotKey(hWnd: Pointer?, id: Int, modifiers: Int, virtualKey: Int): Boolean
        fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
        fun GetMessage(msg: WinUser.MSG, hWnd: Pointer?, min: Int, max: Int): Int
        fun PostThreadMessage(threadId: Int, message: Int, wParam: Pointer?, lParam: Pointer?): Boolean
        fun GetCurrentThreadId(): Int
    }

    private var worker: Thread? = null
    @Volatile private var threadId: Int = 0
    @Volatile private var running = false

    fun start(onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
        if (worker != null) return
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        val user32 = runCatching {
            Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.getOrNull() ?: return

        running = true
        worker = Thread({
            // The hotkeys belong to the thread that registered them and the
            // messages are posted to that thread's queue, so registering and
            // listening have to happen on the same one.
            threadId = runCatching { user32.GetCurrentThreadId() }.getOrDefault(0)
            val claimed = runCatching {
                user32.RegisterHotKey(null, ID_PLAY, 0, VK_MEDIA_PLAY_PAUSE) &&
                    user32.RegisterHotKey(null, ID_NEXT, 0, VK_MEDIA_NEXT_TRACK) &&
                    user32.RegisterHotKey(null, ID_PREVIOUS, 0, VK_MEDIA_PREV_TRACK)
            }.getOrDefault(false)
            if (!claimed) {
                // Another player already holds them. Nothing to be done and
                // nothing worth saying: the keys work, they just work for it.
                running = false
                return@Thread
            }

            val msg = WinUser.MSG()
            while (running) {
                // Blocks until a message arrives, which is why this needs a
                // thread of its own and why stopping means posting to it.
                val result = runCatching { user32.GetMessage(msg, null, 0, 0) }.getOrDefault(-1)
                if (result <= 0) break
                if (msg.message != WM_HOTKEY) continue
                when (msg.wParam?.toInt() ?: 0) {
                    ID_PLAY -> onPlayPause()
                    ID_NEXT -> onNext()
                    ID_PREVIOUS -> onPrevious()
                }
            }

            runCatching {
                user32.UnregisterHotKey(null, ID_PLAY)
                user32.UnregisterHotKey(null, ID_NEXT)
                user32.UnregisterHotKey(null, ID_PREVIOUS)
            }
        }, "rhythm-media-keys").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        val id = threadId
        worker = null
        if (id == 0) return
        // GetMessage is blocking, so the only way out is a message. WM_QUIT
        // is the one it returns zero for.
        runCatching {
            Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS)
                .PostThreadMessage(id, 0x0012, null, null)
        }
    }
}
