package com.nazmiefearmutcu.flowmap.mobile.ui.chart

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** Callbacks raised by [chartGestures]. Positions are in plot pixels. */
class ChartGestureCallbacks(
    val onTap: (Offset) -> Unit,
    val onDoubleTap: (Offset) -> Unit,
    val onScrubStart: (Offset) -> Unit,
    val onScrub: (Offset) -> Unit,
    val onScrubEnd: () -> Unit,
    val onInteraction: () -> Unit = {},
)

/** Span below which an axis does not take part in a pinch (fingers almost aligned on that axis). */
private const val MIN_AXIS_SPAN_PX = 48f

internal fun spans(points: List<Offset>): Triple<Offset, Float, Float> {
    var cx = 0f
    var cy = 0f
    for (p in points) { cx += p.x; cy += p.y }
    cx /= points.size; cy /= points.size
    var sx = 0f
    var sy = 0f
    for (p in points) { sx += abs(p.x - cx); sy += abs(p.y - cy) }
    return Triple(Offset(cx, cy), 2 * sx / points.size, 2 * sy / points.size)
}

/** Per-axis scale factor from the change in finger span; 1 when that axis span is too small to read. */
internal fun axisZoom(prevSpan: Float, curSpan: Float): Float =
    if (prevSpan > MIN_AXIS_SPAN_PX && curSpan > MIN_AXIS_SPAN_PX) (curSpan / prevSpan).coerceIn(0.5f, 2f) else 1f

/**
 * Multi-touch chart gestures:
 *  - one finger: drag pans freely in both axes, with momentum on release;
 *  - two or more fingers: move together to pan, pinch to zoom — horizontal spread scales time,
 *    vertical spread scales price, diagonal scales both, so any part of the chart can be reached;
 *  - tap: inspect the point (tap again to hide);
 *  - long-press then drag: scrub the crosshair;
 *  - double-tap: reset the view.
 */
fun Modifier.chartGestures(viewport: ChartViewport, scope: CoroutineScope, cb: ChartGestureCallbacks): Modifier =
    pointerInput(viewport) {
        var flingJob: Job? = null
        var lastTapTime = 0L
        var lastTapPos = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            flingJob?.cancel()
            cb.onInteraction()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            val slop = viewConfiguration.touchSlop
            var travel = 0f
            var pointerCount = 1

            // Phase 1: tap, drag, second finger, or long-press?
            val outcome: String? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) return@withTimeoutOrNull "up"
                    if (pressed.size > 1) return@withTimeoutOrNull "multi"
                    val ch = pressed[0]
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    travel += ch.positionChange().getDistance()
                    if (travel > slop) return@withTimeoutOrNull "drag"
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

            when (outcome) {
                "up" -> {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 320 && (down.position - lastTapPos).getDistance() < 80f) {
                        lastTapTime = 0L
                        cb.onDoubleTap(down.position)
                    } else {
                        lastTapTime = now
                        lastTapPos = down.position
                        cb.onTap(down.position)
                    }
                }
                null -> {
                    // Long-press: scrub until every finger lifts.
                    cb.onScrubStart(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val p = ev.changes.firstOrNull { it.pressed } ?: break
                        cb.onScrub(p.position)
                        ev.changes.forEach { it.consume() }
                    }
                    cb.onScrubEnd()
                }
                else -> {
                    // Pan / pinch until every finger lifts.
                    var lastSingle = true
                    while (true) {
                        val ev: PointerEvent = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        pointerCount = maxOf(pointerCount, pressed.size)
                        if (pressed.size >= 2) {
                            val (pc, psx, psy) = spans(pressed.map { it.previousPosition })
                            val (cc, csx, csy) = spans(pressed.map { it.position })
                            val pan = cc - pc
                            viewport.pan(pan.x, pan.y)
                            viewport.zoom(axisZoom(psx, csx), axisZoom(psy, csy), cc.x, cc.y)
                            tracker.resetTracking()
                            lastSingle = false
                        } else {
                            val ch = pressed[0]
                            val d = ch.positionChange()
                            if (!lastSingle) tracker.resetTracking()
                            viewport.pan(d.x, d.y)
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            lastSingle = true
                        }
                        ev.changes.forEach { it.consume() }
                    }
                    if (lastSingle) {
                        val v = tracker.calculateVelocity()
                        if (hypot(v.x, v.y) > 350f) {
                            flingJob = scope.launch {
                                var vx = v.x
                                var vy = if (viewport.autoY) 0f else v.y
                                var last = withFrameNanos { it }
                                while (isActive && hypot(vx, vy) > 25f) {
                                    val now = withFrameNanos { it }
                                    val dt = (now - last) / 1e9f
                                    last = now
                                    viewport.pan(vx * dt, vy * dt)
                                    val k = exp(-dt * 4.2f)
                                    vx *= k; vy *= k
                                }
                            }
                        }
                    }
                }
            }
        }
    }

/** Vertical drag on the price axis scales price; double-tap restores auto-fit. */
fun Modifier.priceAxisGestures(viewport: ChartViewport, onAutoFit: () -> Unit): Modifier =
    pointerInput(viewport) {
        var lastTap = 0L
        awaitEachGesture {
            val down = awaitFirstDown()
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.pressed } ?: break
                val dy = ch.positionChange().y
                if (dy != 0f) {
                    moved = true
                    viewport.zoom(1f, exp(-dy * 0.006f), 0f, viewport.plotH / 2)
                }
                ch.consume()
            }
            if (!moved) {
                val now = System.currentTimeMillis()
                if (now - lastTap < 320) { onAutoFit(); lastTap = 0L } else lastTap = now
            }
            down.consume()
        }
    }

/** Horizontal drag on the time axis scales time around the right edge. */
fun Modifier.timeAxisGestures(viewport: ChartViewport, onReset: () -> Unit): Modifier =
    pointerInput(viewport) {
        var lastTap = 0L
        awaitEachGesture {
            awaitFirstDown()
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.pressed } ?: break
                val dx = ch.positionChange().x
                if (dx != 0f) {
                    moved = true
                    viewport.zoom(exp(dx * 0.006f), 1f, viewport.plotW, 0f)
                }
                ch.consume()
            }
            if (!moved) {
                val now = System.currentTimeMillis()
                if (now - lastTap < 320) { onReset(); lastTap = 0L } else lastTap = now
            }
        }
    }
