package com.litechat.app.capture.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One screenshot, taken through the accessibility service (no MediaProjection,
 * no root, no cast permission dialog). Requires `android:canTakeScreenshot="true"`
 * in the service config — and that flag only takes effect after the user turns
 * the accessibility service OFF and ON again.
 *
 * Contract: [capture] is called on the main thread and answers on the main
 * thread, exactly once, with either a software [Result.Ok] bitmap or a
 * [Result.Failed] carrying a sentence the overlay can show as-is.
 *
 * Three things here exist because the platform bites otherwise:
 * - The result arrives as a HardwareBuffer. It must be copied into an ARGB_8888
 *   bitmap and closed immediately; leaking buffers starves the compositor.
 * - The system throttles screenshots (errorCode 3). We throttle ourselves first
 *   (>= 1s between attempts) and back off 1s -> 2s -> 4s ... 30s while failing.
 * - Our own floating overlay is part of the display and would be baked into the
 *   picture, so the caller hands us hide/restore callbacks.
 */
class ScreenCapture(
    private val service: AccessibilityService
) {

    sealed class Result {
        /**
         * [scaleX]/[scaleY] = bitmap size / captured area size, and
         * [originX]/[originY] = where that area starts on screen.
         *
         * A window shot (API 34+) is NOT the whole display: in split screen, or
         * whenever the window excludes the status bar, the picture is both
         * smaller than the display and offset from its origin. So node rects map
         * as `bitmapX = (screenX - originX) * scaleX`, and OCR boxes map back the
         * other way.
         */
        data class Ok(
            val bitmap: Bitmap,
            val scaleX: Float,
            val scaleY: Float,
            val originX: Int = 0,
            val originY: Int = 0,
            /**
             * True when the picture is the chat window on its own, so our own
             * floating panel cannot be in it. The caller then has no reason to
             * throw away anything that looks like it is under the panel - doing
             * so deleted real messages.
             */
            val windowScoped: Boolean = false
        ) : Result()
        data class Failed(val code: Int, val humanMessage: String) : Result()
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * How long until the next shot would be allowed (0 = right now).
     *
     * The caller uses this to come back at the earliest legal moment instead of
     * waiting for the next accessibility event, which is up to a second of dead
     * time on the critical path between "the other person sent something" and
     * "the candidates appear".
     */
    fun nextAttemptInMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return (requiredInterval() - (now - lastAttemptAt)).coerceAtLeast(0L)
    }

    /**
     * Take one screenshot. [onResult] runs on the main thread, exactly once.
     *
     * [preferWindow] false means "use the whole-display picture" - see [shoot]
     * for why WeChat asks for that.
     */
    fun capture(shouldCapture: () -> Boolean = { true },
                preferWindow: Boolean = true,
                onResult: (Result) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val need = requiredInterval()
        if (now - lastAttemptAt < need) {
            onResult(Result.Failed(CODE_THROTTLED, "截屏太频繁"))
            return
        }
        lastAttemptAt = now

        val done = AtomicBoolean(false)
        val finish: (Result) -> Unit = { r ->
            if (done.compareAndSet(false, true)) {
                // Only a real capture problem may slow the next attempt down.
                // A cancelled shot (the conversation moved on) and our own
                // throttle are not the device refusing to be photographed, and
                // counting them walked the interval up to 30 seconds - which is
                // exactly the "it takes forever / it stopped answering" the
                // panel showed after a few app switches.
                if (r is Result.Ok) failStreak = 0
                else if (isBackoffFailure(r)) {
                    failStreak = (failStreak + 1).coerceAtMost(MAX_STREAK)
                }
                onResult(r)
            }
        }

        // The panel is deliberately NOT hidden first (see
        // OverlayController.obscuredRect): hiding never actually kept it out of
        // the picture, and it made the panel blink once a second. Whatever we
        // read from behind the panel is thrown away by the caller instead.
        //
        // One frame of settle is still given, so a shot taken right after a
        // scroll or a screen change is not a torn one.
        main.postDelayed({
            // The foreground can change while we wait. Do not shoot the next
            // app and label its pixels as the original conversation.
            if (shouldCapture()) shoot(done, finish, preferWindow)
            else finish(Result.Failed(CODE_CANCELLED, "会话已变化，已取消截屏"))
        }, SHOT_SETTLE_MS)
    }

    private fun shoot(done: AtomicBoolean, finish: (Result) -> Unit, preferWindow: Boolean) {
        val exec = service.mainExecutor
        // The system can simply never call back (seen when a shot lands on a
        // protected window during a transition). Without this the overlay stays
        // INVISIBLE and the caller's busy flag is stuck until the service dies.
        val timeout = Runnable { finish(Result.Failed(CODE_TIMEOUT, humanMessage(CODE_TIMEOUT))) }

        // One attempt. [area] is the region the picture will cover (null = the
        // whole display), decided BEFORE the shot is issued and handed to
        // [toBitmap] inside the callback so the mapping matches the call that
        // actually produced the bitmap.
        fun attempt(area: Rect?, windowId: Int, verifyWindow: Boolean,
                    onFailure: (Int) -> Unit) {
            val cb = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    main.removeCallbacks(timeout)
                    // Already timed out: this result is void. Drop the buffer
                    // (never leak it) and do not touch the caller a second time.
                    if (done.get()) { runCatching { result.hardwareBuffer.close() }; return }
                    // A window shot's coordinates are only meaningful if the
                    // window stayed where it was: an animation running under the
                    // shutter ("the window now starts at x=666") is exactly how
                    // every later coordinate ends up shifted. Comparing the
                    // bounds before and after the capture catches it.
                    if (verifyWindow && !windowStayedPut(area)) {
                        runCatching { result.hardwareBuffer.close() }
                        Log.w(TAG, "window moved during the shot")
                        onFailure(CODE_MOVED)
                        return
                    }
                    finish(toBitmap(result, area))
                }

                override fun onFailure(errorCode: Int) {
                    main.removeCallbacks(timeout)
                    onFailure(errorCode)
                }
            }
            try {
                if (area == null) {
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, exec, cb)
                } else {
                    service.takeScreenshotOfWindow(windowId, exec, cb)
                }
                // The watchdog is armed after a successful ISSUE only: a call
                // that threw never reaches the callback at all.
                main.postDelayed(timeout, TIMEOUT_MS)
            } catch (e: Throwable) {
                Log.w(TAG, "screenshot call failed: ${e.javaClass.simpleName}")
                onFailure(CODE_INTERNAL)
            }
        }

        fun displayShot(afterFail: (Int) -> Unit) =
            attempt(null, -1, verifyWindow = false, afterFail)

        // The active window first (API 34+), because a window shot contains that
        // window and nothing else: our own floating panel is a different window,
        // so it cannot land in the picture no matter how the compositor behaves.
        // That matters - with the display shot the panel IS in the picture, and
        // when it covers the conversation the reader loses the messages behind
        // it (measured on Android 16: the panel covered 19 of the 21 lines the
        // OCR found, and the other person's bubbles were among the casualties).
        //
        // Its one weakness is the coordinate frame, and that is handled: the
        // bounds are checked before AND after the capture, and a window that
        // moved in between is discarded rather than trusted (that mistake is what
        // put "origin=666,0" into a picture of the whole screen).
        //
        // The display shot stays as the fallback - for OEM builds that refuse a
        // window shot, and for when the window is moving.
        // WeChat asks for the display picture on purpose. Its chat screen is
        // drawn by the app itself, and a window-scoped capture can come back
        // without that content (or blank), which made WeChat the one app that
        // "could not be read" while every other chat app was fine. The display
        // picture is what every working version of this reader used for WeChat;
        // the panel that sits on top of it is masked out downstream.
        val win = if (preferWindow) windowShotArea() else null
        if (win != null) {
            attempt(win.rect, win.windowId, verifyWindow = true) { code ->
                Log.w(TAG, "window shot unusable ($code), falling back to the display")
                displayShot { c2 -> finish(Result.Failed(c2, humanMessage(c2))) }
            }
        } else {
            displayShot { c2 -> finish(Result.Failed(c2, humanMessage(c2))) }
        }
    }

    /** Did the active window keep the bounds the shot was issued for? */
    private fun windowStayedPut(area: Rect?): Boolean {
        if (area == null) return true
        val node = runCatching { service.rootInActiveWindow }.getOrNull() ?: return false
        val now = runCatching {
            val out = Rect()
            node.window?.getBoundsInScreen(out)
            out
        }.getOrNull() ?: return false
        return kotlin.math.abs(now.left - area.left) <= 2 &&
            kotlin.math.abs(now.top - area.top) <= 2 &&
            kotlin.math.abs(now.right - area.right) <= 2 &&
            kotlin.math.abs(now.bottom - area.bottom) <= 2
    }

    private data class WindowShot(val rect: Rect, val windowId: Int)

    /**
     * The active window's bounds, but only when taking a window shot is both
     * possible and believable: API 34+, a real window id, and a rectangle that
     * fits inside the display. Everything else returns null, which means "use
     * the display shot".
     */
    private fun windowShotArea(): WindowShot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val node = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val id = node.windowId
        if (id == -1) return null
        val r = runCatching {
            val out = Rect()
            node.window?.getBoundsInScreen(out)
            out
        }.getOrNull() ?: return null
        val dm = realDisplaySize()
        val fits = r.width() > 0 && r.height() > 0 &&
            r.left >= 0 && r.top >= 0 &&
            r.right <= dm[0] && r.bottom <= dm[1]
        if (!fits) return null
        return WindowShot(r, id)
    }

    /**
     * The real display size in pixels: `[width, height]`.
     *
     * `resources.displayMetrics` is deliberately NOT used here. An accessibility
     * service is not a normal app window, and what it gets back is the *usable*
     * area - measured on a 1080x2400 device it says 1080x2146, i.e. the screen
     * minus the status and navigation bars. Dividing a full-screen screenshot
     * (2400 px tall) by that made the vertical scale 1.118 instead of 1.0, which
     * stretches every y coordinate by 12% - enough to move the message band and
     * mis-read the speaker.
     */
    @Suppress("DEPRECATION")
    private fun realDisplaySize(): IntArray {
        val fallback = service.resources.displayMetrics
        val out = intArrayOf(fallback.widthPixels, fallback.heightPixels)
        runCatching {
            val p = android.graphics.Point()
            val d = service.display ?: return@runCatching
            d.getRealSize(p)
            if (p.x > 0 && p.y > 0) { out[0] = p.x; out[1] = p.y }
        }
        return out
    }

    /**
     * HardwareBuffer -> software bitmap. The buffer is closed no matter what.
     *
     * [window] is the area the shot covers, in screen coordinates, or null for a
     * whole-display shot - which is 1:1 with the screen by definition, so it
     * needs neither a scale nor an origin.
     */
    private fun toBitmap(result: AccessibilityService.ScreenshotResult, window: Rect?): Result {
        val buffer = result.hardwareBuffer
        return try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
            runCatching { hw?.recycle() }
            if (bmp == null) {
                Result.Failed(CODE_INTERNAL, "截屏失败：拿到的画面读不出来")
            } else if (isBlank(bmp)) {
                // A uniform picture is not a screen: it is what the platform
                // hands back when the content is protected, when a window
                // capture misses everything the app draws itself, or when a
                // frame is taken before anything is painted. Saying so beats
                // saying "没认出文字", which sends the user looking for the
                // wrong problem.
                Result.Failed(CODE_BLANK,
                    "截到的是空白画面（内容被系统或该应用挡住了截图）")
            } else {
                val g = if (window == null) {
                    CaptureGeometry(0, 0, 1f, 1f)
                } else {
                    val dm = realDisplaySize()
                    resolveCaptureGeometry(
                        bmp.width, bmp.height, dm[0], dm[1],
                        window.left, window.top, window.right, window.bottom)
                }
                Result.Ok(bmp, g.scaleX, g.scaleY, g.originX, g.originY,
                    windowScoped = window != null)
            }
        } catch (e: Throwable) {
            Result.Failed(CODE_INTERNAL, "截屏失败：${e.javaClass.simpleName}")
        } finally {
            runCatching { buffer.close() }
        }
    }

    /** True when the picture holds essentially one colour. */
    private fun isBlank(bmp: Bitmap): Boolean {
        if (bmp.width < 8 || bmp.height < 8) return true
        val first = bmp.getPixel(bmp.width / 8, bmp.height / 8)
        var different = 0
        for (fy in intArrayOf(1, 3, 5, 7)) {
            for (fx in intArrayOf(1, 3, 5, 7)) {
                val p = bmp.getPixel(bmp.width * fx / 8, bmp.height * fy / 8)
                if (kotlin.math.abs((p and 0xFF) - (first and 0xFF)) > 6 ||
                    kotlin.math.abs(((p shr 8) and 0xFF) - ((first shr 8) and 0xFF)) > 6 ||
                    kotlin.math.abs(((p shr 16) and 0xFF) - ((first shr 16) and 0xFF)) > 6) {
                    different++
                }
            }
        }
        // A real screen always has text, bars and bubbles in it; 16 samples
        // landing on one colour means there was nothing to photograph.
        return different == 0
    }

    companion object {
        private const val TAG = "LITECHAT"

        /** Our own throttle, not a platform code. */
        const val CODE_THROTTLED = -1
        /** Our own watchdog: the platform callback never arrived. */
        const val CODE_TIMEOUT = -2
        /** The window moved between reading its bounds and taking the shot. */
        const val CODE_MOVED = -4
        const val CODE_CANCELLED = -3
        /** The picture came back as one flat colour (see [isBlank]). */
        const val CODE_BLANK = -5
        private const val CODE_INTERNAL = 1

        private const val MIN_INTERVAL_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_STREAK = 6
        /** One frame (at 30 Hz) of settle before the shutter opens. */
        private const val SHOT_SETTLE_MS = 60L

        /** How long we wait for the screenshot callback before giving up. */
        private const val TIMEOUT_MS = 3000L

        // Global across instances on purpose: the system limit is per service,
        // and the service may build a new ScreenCapture per call site.
        @Volatile private var lastAttemptAt = 0L
        @Volatile private var failStreak = 0

        /** 1s normally; 1s, 2s, 4s ... capped at 30s while failures repeat. */
        private fun requiredInterval(): Long {
            if (failStreak <= 0) return MIN_INTERVAL_MS
            val shifted = MIN_INTERVAL_MS shl (failStreak - 1).coerceAtMost(MAX_STREAK)
            return shifted.coerceAtMost(MAX_BACKOFF_MS)
        }

        /** Does this outcome mean "the device would not give us a picture"? */
        private fun isBackoffFailure(r: Result): Boolean =
            r is Result.Failed && r.code != CODE_CANCELLED &&
                r.code != CODE_THROTTLED && r.code != CODE_MOVED &&
                r.code != CODE_BLANK

        /** Platform error codes, in words a user can act on. */
        fun humanMessage(code: Int): String = when (code) {
            CODE_THROTTLED -> "截屏太频繁"
            CODE_TIMEOUT -> "截屏超时"
            1 -> "截屏失败：内部错误（系统拒绝，可能是该无障碍服务不被允许截屏）"
            2 -> "截屏失败：无障碍服务未声明截屏能力（去设置里把无障碍关掉再开启）"
            3 -> "截屏失败：间隔太短，等一秒再试"
            4 -> "截屏失败：没有有效的显示"
            6 -> "截屏失败：窗口不可见或受保护，这类界面拿不到画面"
            else -> "截屏失败（码 ${code}）"
        }
    }
}

/**
 * Where a captured picture sits on screen, and how its pixels map to screen
 * pixels: `screenX = bitmapX / scaleX + originX`.
 */
internal data class CaptureGeometry(
    val originX: Int,
    val originY: Int,
    val scaleX: Float,
    val scaleY: Float
)

/**
 * Decide the screen <-> bitmap mapping for one screenshot.
 *
 * Two halves are known and they can DISAGREE: the size of the picture that
 * actually arrived, and the bounds of the window the shot was issued for. When
 * they disagreed, every coordinate the app computed was shifted by the
 * difference: the message band was cropped from the wrong part of the screen,
 * the bubbles were grouped wrongly, the speaker was read off the wrong pixels
 * ("all of them are mine"), the newest-incoming-message fingerprint therefore
 * never changed, and the panel sat there without ever generating a reply.
 *
 * The picture's own size is the half worth trusting. An offset is only
 * meaningful when the picture is correspondingly smaller than the screen on
 * that axis; a picture as wide as the display cannot start 666 px to the right
 * of it. Anything that does not fit the display is treated as a full-screen
 * shot, whose origin is (0,0) by definition and cannot be wrong.
 *
 * Plain integers rather than `android.graphics.Rect`, so this can be unit
 * tested off-device - the rule is too important to leave untested.
 */
internal fun resolveCaptureGeometry(
    bitmapW: Int, bitmapH: Int,
    displayW: Int, displayH: Int,
    winLeft: Int, winTop: Int, winRight: Int, winBottom: Int
): CaptureGeometry {
    fun displayScale(w: Int, h: Int) = CaptureGeometry(
        0, 0,
        if (displayW > 0) w / displayW.toFloat() else 1f,
        if (displayH > 0) h / displayH.toFloat() else 1f
    )
    val winW = winRight - winLeft
    val winH = winBottom - winTop
    val believable = winW > 0 && winH > 0 &&
        winLeft >= 0 && winTop >= 0 &&
        winRight <= displayW + 2 && winBottom <= displayH + 2
    if (!believable) return displayScale(bitmapW, bitmapH)

    var originX = winLeft
    var originY = winTop
    var scaleX = bitmapW / winW.toFloat()
    var scaleY = bitmapH / winH.toFloat()
    // Per-axis contradiction: a picture that is as wide as the display cannot
    // be an offset picture on that axis, and its scale is 1.
    if (originX != 0 && bitmapW >= displayW - 2) {
        originX = 0
        scaleX = if (displayW > 0) bitmapW / displayW.toFloat() else 1f
    }
    if (originY != 0 && bitmapH >= displayH - 2) {
        originY = 0
        scaleY = if (displayH > 0) bitmapH / displayH.toFloat() else 1f
    }
    return CaptureGeometry(originX, originY, scaleX, scaleY)
}
