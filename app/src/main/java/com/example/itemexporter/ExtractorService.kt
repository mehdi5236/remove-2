package com.example.itemexporter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ExtractorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var stopRequested = false

    private var windowManager: WindowManager? = null
    private var button: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null

    private val checkRunnable = Runnable { refreshButton() }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scheduleCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == packageName) return
        if (job?.isActive == true) return
        scheduleCheck()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopRequested = true
        handler.removeCallbacksAndMessages(null)
        hideButton()
        scope.cancel()
        super.onDestroy()
    }

    private fun scheduleCheck() {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, 400)
    }

    private fun refreshButton() {
        val root = rootInActiveWindow ?: return
        if (pageMatches(root)) showButton() else hideButton()
    }

    // ------------------------------------------------------------------ floating button

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        if (button != null) return
        val wm = windowManager ?: return
        val dp = resources.displayMetrics.density

        val tv = TextView(this).apply {
            text = IDLE_LABEL
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 40 * dp
                setColor(COLOR_IDLE)
            }
            elevation = 8 * dp
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.btnX(this@ExtractorService)
            y = Prefs.btnY(this@ExtractorService)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; downTime = e.eventTime
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Prefs.setBtnPos(this, lp.x, lp.y)
                    else if (e.eventTime - downTime > 700) dumpTree()
                    else onButtonClick()
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(tv, lp)
            button = tv
            buttonParams = lp
        } catch (_: Exception) {
        }
    }

    private fun hideButton() {
        val tv = button ?: return
        try { windowManager?.removeView(tv) } catch (_: Exception) {}
        button = null
        buttonParams = null
    }

    private fun setLabel(text: String, running: Boolean) {
        val tv = button ?: return
        tv.text = text
        (tv.background as? GradientDrawable)?.setColor(if (running) COLOR_RUNNING else COLOR_IDLE)
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()

    private fun onButtonClick() {
        if (job?.isActive == true) {
            stopRequested = true
            setLabel("در حال ذخیره…", true)
        } else {
            job = scope.launch {
                try {
                    runExtraction()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("خطا: ${e.message}")
                } finally {
                    setLabel(IDLE_LABEL, false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ extraction

    private suspend fun runExtraction() {
        stopRequested = false
        val items = LinkedHashMap<String, Item>()
        var total: Int? = null

        // ---- Fast pass: read EVERY node the app has already loaded (even off-screen), no scrolling ----
        setLabel("در حال خواندن…", true)
        val fastRoot = rootInActiveWindow
        if (fastRoot != null) {
            val raw = withContext(Dispatchers.Default) { collect(fastRoot, onlyVisible = false) }
            val parsed = withContext(Dispatchers.Default) { Parser.parse(raw, Int.MIN_VALUE) }
            Parser.parseTotal(raw)?.let { total = it }
            for (item in parsed) {
                val old = items[item.barcode]
                if (old == null || item.score > old.score) items[item.barcode] = item
            }
            if (items.isNotEmpty() && (total == null || items.size >= total!!)) {
                // Everything is already in the tree (or the page shows no total to compare with
                // and the whole list was read in one go).
                if (total != null || items.size > 15) {
                    saveResult(items, total, "")
                    return
                }
            }
        }

        // ---- Fallback: the app only keeps visible cards in memory, so scroll and merge ----
        setLabel("رفتن به بالا… (توقف)", true)
        scrollToTop()

        var prevSig: Set<String>? = null
        var stuck = 0
        var empty = 0
        var offPage = 0
        var lostOverlap = 0
        var factor = 0.5f
        var finishedReason = ""

        while (!stopRequested) {
            val root = rootInActiveWindow
            if (root == null || !pageMatches(root)) {
                offPage++
                if (offPage > 8) { finishedReason = "از صفحه خارج شدید"; break }
                delay(500)
                continue
            }
            offPage = 0

            val listRect = Rect()
            val list = findScrollable(root)
            list?.getBoundsInScreen(listRect)
            val raw = collect(root)
            val parsed = Parser.parse(raw, if (list != null) listRect.top else 0)
            Parser.parseTotal(raw)?.let { total = it }

            if (parsed.isEmpty()) {
                empty++
                if (empty > 6) { finishedReason = "کالایی روی صفحه پیدا نشد"; break }
                delay(700)
                continue
            }
            empty = 0

            for (item in parsed) {
                val old = items[item.barcode]
                if (old == null || item.score > old.score) items[item.barcode] = item
            }
            val sig = parsed.map { it.barcode }.toSet()
            setLabel("${items.size}" + (total?.let { " / $it" } ?: "") + "  (توقف)", true)

            if (total != null && items.size >= total!!) break

            if (sig == prevSig) {
                stuck++
                if (stuck >= 2) break          // reached the end of the list
            } else {
                stuck = 0
            }

            // If the new screen shares nothing with the previous one we may have skipped cards:
            // go back, and use a smaller scroll step next time.
            if (prevSig != null && stuck == 0 && sig.none { it in prevSig!! } && lostOverlap < 8) {
                lostOverlap++
                scrollBy(list, listRect, -factor)
                factor = max(0.2f, factor * 0.6f)
                delay(900)
                continue
            }

            prevSig = sig
            scrollBy(list, listRect, factor)
            delay(900)
        }

        saveResult(items, total, finishedReason)
    }

    private suspend fun saveResult(items: Map<String, Item>, total: Int?, reason: String) {
        val list = items.values.toList()
        if (list.isEmpty()) {
            toast(if (reason.isNotEmpty()) reason else "داده‌ای استخراج نشد")
            return
        }
        val name = "items_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".xlsx"
        val uri = withContext(Dispatchers.Default) {
            Storage.save(this@ExtractorService, name, Storage.XLSX_MIME, XlsxWriter.build(list))
        }
        Prefs.setLastUri(this, uri)
        val totalNote = if (total != null && list.size < total) " (از $total)" else ""
        toast("${list.size} قلم$totalNote ذخیره شد\nDownload/ItemExporter/$name")
    }

    private suspend fun scrollToTop() {
        var prev: Set<String>? = null
        var same = 0
        for (i in 0 until 80) {
            if (stopRequested) return
            val root = rootInActiveWindow ?: return
            val rect = Rect()
            val list = findScrollable(root)
            list?.getBoundsInScreen(rect)
            val sig = Parser.parse(collect(root), if (list != null) rect.top else 0)
                .map { it.barcode }.toSet()
            if (sig == prev) {
                same++
                if (same >= 2) return
            } else same = 0
            prev = sig
            scrollBy(list, rect, -0.76f, fast = true)
            delay(450)
        }
    }

    /** fraction > 0 scrolls forward (down the list), < 0 backwards. */
    private suspend fun scrollBy(list: AccessibilityNodeInfo?, listRect: Rect, fraction: Float, fast: Boolean = false) {
        val area = if (list != null && listRect.height() > 200) listRect else {
            val m = resources.displayMetrics
            Rect(0, (m.heightPixels * 0.3).toInt(), m.widthPixels, (m.heightPixels * 0.92).toInt())
        }
        val f = min(abs(fraction), 0.76f)
        val h = area.height().toFloat()
        val x = area.centerX().toFloat()
        val forward = fraction > 0
        val startY = if (forward) area.bottom - 0.12f * h else area.top + 0.12f * h
        val endY = if (forward) startY - f * h else startY + f * h
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (fast) 150L else 700L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
        if (!ok && list != null) {
            list.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
        }
    }

    // ------------------------------------------------------------------ tree helpers

    /** One native search (cheap) instead of walking the whole tree on every event. */
    private fun pageMatches(root: AccessibilityNodeInfo): Boolean {
        val t = Prefs.trigger(this)
        val variants = setOf(t, Parser.normalize(t), t.replace('ی', 'ي').replace('ک', 'ك'))
        return variants.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
    }

    private fun collect(root: AccessibilityNodeInfo, onlyVisible: Boolean = true): List<RawNode> {
        val out = ArrayList<RawNode>()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            if (!onlyVisible || n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()
                if (!t.isNullOrBlank()) {
                    n.getBoundsInScreen(r)
                    val lines = t.split('\n').filter { it.isNotBlank() }
                    if (lines.size <= 1) {
                        out.add(RawNode(t, r.left, r.top, r.right, r.bottom))
                    } else {
                        // one node holding several lines: split its bounds vertically
                        val lh = (r.bottom - r.top) / lines.size
                        lines.forEachIndexed { i, line ->
                            out.add(RawNode(line, r.left, r.top + i * lh, r.right, r.top + (i + 1) * lh))
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return out
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40) return
            if (n.isScrollable && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                val area = r.width().toLong() * r.height().toLong()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return best
    }

    /** Long-press on the button: saves the raw accessibility tree to a text file for debugging. */
    private fun dumpTree() {
        val root = rootInActiveWindow
        if (root == null) { toast("صفحه‌ای برای ذخیره پیدا نشد"); return }
        val sb = StringBuilder()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40) return
            n.getBoundsInScreen(r)
            sb.append("  ".repeat(d)).append(n.className)
                .append(" id=").append(n.viewIdResourceName)
                .append(" scrollable=").append(n.isScrollable)
                .append(" visible=").append(n.isVisibleToUser)
                .append(" [").append(r.toShortString()).append("]")
                .append(" text=").append(n.text)
                .append(" desc=").append(n.contentDescription)
                .append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        try {
            val name = "dump_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            Storage.save(this, name, "text/plain", sb.toString().toByteArray(Charsets.UTF_8))
            toast("ساختار صفحه ذخیره شد: Download/ItemExporter/$name")
        } catch (e: Exception) {
            toast("خطا: ${e.message}")
        }
    }

    companion object {
        private const val IDLE_LABEL = "⬇ استخراج اکسل"
        private const val COLOR_IDLE = 0xFFD81B60.toInt()
        private const val COLOR_RUNNING = 0xFF2E7D32.toInt()
    }
}
