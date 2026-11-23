package com.example.captureautomator.automation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.example.captureautomator.core.*
import kotlin.math.hypot

internal class TouchSurface(context: Context, private val profile: TouchProfile,
    private val onStroke: (TouchStroke) -> Unit, private val onRejected: (String) -> Unit,
    calibration: TouchStroke? = null,
    area: TouchViewport? = null) : View(context) {
    private val area = area ?: TouchViewport(profile.left, profile.top, profile.width, profile.height)
    private var builder: TouchStrokeBuilder? = null
    private var downAt = 0L
    private val live = mutableListOf<TouchPoint>()
    var editedStroke: TouchStroke? = calibration
        private set
    private var editEnd = false
    private var editing = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; style = Paint.Style.STROKE }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = if (editedStroke == null) Color.rgb(255, 95, 75) else Color.CYAN
        val points = editedStroke?.points ?: live
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = profile.left + p.x * (profile.width - 1) - area.left; val y = profile.top + p.y * (profile.height - 1) - area.top
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
        points.firstOrNull()?.let { canvas.drawCircle(profile.left + it.x * (profile.width - 1) - area.left, profile.top + it.y * (profile.height - 1) - area.top, 22f, paint) }
        if (points.size > 1) points.last().let {
            paint.color = Color.rgb(255, 175, 40)
            canvas.drawCircle(profile.left + it.x * (profile.width - 1) - area.left, profile.top + it.y * (profile.height - 1) - area.top, 22f, paint)
        }
        paint.color = Color.rgb(255, 95, 75)
        canvas.drawRect((profile.left - area.left + 2).toFloat(), (profile.top - area.top + 2).toFloat(),
            (profile.left + profile.width - area.left - 2).toFloat(), (profile.top + profile.height - area.top - 2).toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / (width - 1).coerceAtLeast(1)
        val y = event.y / (height - 1).coerceAtLeast(1)
        editedStroke?.let { stroke ->
            if (event.pointerCount != 1) { editing = false; return true }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fun distance(p: TouchPoint) = hypot((p.x - x) * width, (p.y - y) * height)
                    editEnd = stroke.points.size > 1 && distance(stroke.points.last()) < distance(stroke.points.first())
                    editing = minOf(distance(stroke.points.first()), distance(stroke.points.last())) < 60 * resources.displayMetrics.density
                }
                MotionEvent.ACTION_MOVE -> if (editing) {
                    TouchPolicy.moveEndpoint(stroke, editEnd, x, y)?.let { editedStroke = it; invalidate() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> editing = false
            }
            return true
        }
        fun sample(px: Float, py: Float, time: Long) {
            val fx = area.left + px - profile.left; val fy = area.top + py - profile.top
            val outside = fx < 0 || fy < 0 || fx >= profile.width || fy >= profile.height
            if (outside) builder?.invalidate() else {
                val p = TouchPoint((fx / (profile.width - 1)).coerceIn(0f, 1f), (fy / (profile.height - 1)).coerceIn(0f, 1f), time - downAt)
                builder?.add(p.x, p.y, p.timeMs, event.pointerCount)
                if (live.size < TouchPolicy.MAX_POINTS) live += p
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val location = IntArray(2); getLocationOnScreen(location)
                if (location[0] != area.left || location[1] != area.top || width != area.width || height != area.height) {
                    onRejected("Overlay/window geometry changed; gesture was not forwarded")
                    return true
                }
                builder = TouchStrokeBuilder(); live.clear(); downAt = event.eventTime
                sample(event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> builder?.invalidate()
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) sample(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalEventTime(i))
                sample(event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_UP -> {
                sample(event.x, event.y, event.eventTime)
                val stroke = builder?.finish(event.eventTime - downAt)
                builder = null; live.clear()
                if (stroke == null) onRejected("Use one finger and a gesture under 10 seconds; nothing was forwarded") else onStroke(stroke)
            }
            MotionEvent.ACTION_CANCEL -> { builder = null; live.clear() }
        }
        invalidate()
        return true
    }
}
