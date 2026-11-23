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
import com.example.captureautomator.core.TouchViewport

/** Trusted, visible Accessibility overlays. No SYSTEM_ALERT_WINDOW permission is requested. */
internal class TouchWindows(private val service: AutomationAccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val attached = mutableSetOf<View>()
    private var lastBarX: Int? = null
    private var lastBarY: Int? = null
    var controlsDragging = false
        private set
    fun controlBounds(area: TouchViewport, actionCount: Int): TouchViewport {
        val width=minOf(area.width,dp(if(actionCount>3)340 else 300))
        val x=(lastBarX ?: (area.left+(area.width-width)/2)).coerceIn(area.left,area.left+area.width-width)
        val y=(lastBarY ?: area.top).coerceIn(area.top,maxOf(area.top,area.top+area.height-dp(50)))
        return TouchViewport(x,y,width,dp(50))
    }
    fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun params(width: Int, height: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT).apply {
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        gravity = Gravity.TOP or Gravity.LEFT
        this.x = x; this.y = y
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
    fun addCanvas(view: View, profile: TouchProfile, area: TouchViewport = TouchViewport(profile.left, profile.top, profile.width, profile.height)) {
        if (view in attached) return
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        wm.addView(view, params(area.width, area.height, area.left, area.top))
        attached += view
    }
    fun bar(profile: TouchProfile, title: String, actions: List<Pair<String, () -> Unit>>, area: TouchViewport = TouchViewport(profile.left, profile.top, profile.width, profile.height)): Pair<View, TextView> {
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(36, 52, 44), Color.rgb(23, 32, 28))).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(72, 94, 81))
            }
            elevation = dp(5).toFloat()
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
                this.text = text; textSize = 10f
                setTextColor(Color.rgb(12, 38, 27))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(135, 225, 188))
                 minWidth = 0; minimumWidth = 0
                setPadding(dp(5), 0, dp(5), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(53), dp(44)))
        }
        val control = controlBounds(area,actions.size)
        val width = control.width
        val layout = params(width, WindowManager.LayoutParams.WRAP_CONTENT, control.left, control.top)
        var downX = 0f; var downY = 0f; var originalX = 0; var originalY = 0
        label.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    controlsDragging = true
                    downX = event.rawX; downY = event.rawY; originalX = layout.x; originalY = layout.y
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (originalX + event.rawX - downX).toInt().coerceIn(area.left, area.left + area.width - width)
                    layout.y = (originalY + event.rawY - downY).toInt().coerceIn(area.top,
                        maxOf(area.top, area.top + area.height - row.height))
                    lastBarX = layout.x; lastBarY = layout.y
                    if (row in attached) wm.updateViewLayout(row, layout)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> controlsDragging = false
            }
            true
        }
        wm.addView(row, layout); attached += row
        return row to label
    }
    fun remove(view: View?) {
        if (view != null && attached.remove(view)) runCatching { wm.removeViewImmediate(view) }
    }
    fun close() { controlsDragging = false; attached.toList().forEach(::remove) }
}
