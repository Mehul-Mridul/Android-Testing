package com.example.captureautomator.automation

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.captureautomator.core.TouchProfile

/** Trusted, visible Accessibility overlays. No SYSTEM_ALERT_WINDOW permission is requested. */
internal class TouchWindows(private val service: AutomationAccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val attached = mutableSetOf<View>()
    private var lastBarX: Int? = null
    private var lastBarY: Int? = null
    fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun params(width: Int, height: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        this.x = x; this.y = y
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
    fun addCanvas(view: View, profile: TouchProfile) {
        if (view in attached) return
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        wm.addView(view, params(profile.width, profile.height, profile.left, profile.top))
        attached += view
    }
    fun bar(profile: TouchProfile, title: String, actions: List<Pair<String, () -> Unit>>): Pair<View, TextView> {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(38, 44, 55))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            contentDescription = "Visible touch controls"
        }
        val label = TextView(service).apply {
            text = title; setTextColor(Color.WHITE); textSize = 11f
            setPadding(dp(5), dp(8), dp(5), dp(8))
            contentDescription = "Move touch controls"
        }
        row.addView(label, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.forEach { (text, action) ->
            row.addView(Button(service).apply {
                this.text = text; textSize = 10f; minWidth = 0; minimumWidth = 0
                setPadding(dp(5), 0, dp(5), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(53), dp(44)))
        }
        val width = minOf(profile.width, dp(if (actions.size > 3) 390 else 355))
        val layout = params(width, WindowManager.LayoutParams.WRAP_CONTENT,
            (lastBarX ?: profile.left).coerceIn(profile.left, profile.left + profile.width - width),
            (lastBarY ?: profile.top).coerceIn(profile.top, maxOf(profile.top, profile.top + profile.height - dp(50))))
        var downX = 0f; var downY = 0f; var originalX = 0; var originalY = 0
        label.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; originalX = layout.x; originalY = layout.y
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (originalX + event.rawX - downX).toInt().coerceIn(profile.left, profile.left + profile.width - width)
                    layout.y = (originalY + event.rawY - downY).toInt().coerceIn(profile.top,
                        maxOf(profile.top, profile.top + profile.height - row.height))
                    lastBarX = layout.x; lastBarY = layout.y
                    if (row in attached) wm.updateViewLayout(row, layout)
                }
            }
            true
        }
        wm.addView(row, layout); attached += row
        return row to label
    }
    fun remove(view: View?) {
        if (view != null && attached.remove(view)) runCatching { wm.removeViewImmediate(view) }
    }
    fun close() { attached.toList().forEach(::remove) }
}
