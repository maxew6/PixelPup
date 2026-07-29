package com.mahesh.pixelpup

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Single tap = pet. Double tap = treat. Drag = pick up, moves the WINDOW
 * (not just the view). Release = falls with gravity. Long press = radial
 * mini-menu (Treat / Fetch / Sleep / Home).
 */
class TouchController(
    context: Context,
    private val brain: PetBrain,
    private val windowManager: WindowManager
) : View.OnTouchListener {

    var onSendHome: (() -> Unit)? = null
    var onWindowMoved: ((Int, Int) -> Unit)? = null
    var layoutParamsProvider: (() -> WindowManager.LayoutParams)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dogView: DogView? = null

    private var initialWindowX = 0
    private var initialWindowY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var lastMoveTimeMs = 0L
    private var lastMoveY = 0f
    private var velocityY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (dogView?.menuVisible != true) {
                brain.onTap()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            brain.onDoubleTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val view = dogView ?: return
            view.menuVisible = true
            view.invalidate()
        }
    })

    fun attach(view: DogView) {
        dogView = view
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        val params = layoutParamsProvider?.invoke()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialWindowX = params?.x ?: 0
                initialWindowY = params?.y ?: 0
                isDragging = false
                lastMoveTimeMs = System.currentTimeMillis()
                lastMoveY = event.rawY
                velocityY = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                if (dogView?.menuVisible == true) return true
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                    brain.onDragStart()
                }
                if (isDragging) {
                    val newX = initialWindowX + dx
                    val newY = initialWindowY + dy
                    brain.onDragMove(newX, newY)
                    onWindowMoved?.invoke(newX.toInt(), newY.toInt())

                    val now = System.currentTimeMillis()
                    val elapsedMs = (now - lastMoveTimeMs).coerceAtLeast(1L)
                    velocityY = (event.rawY - lastMoveY) / (elapsedMs / 1000f)
                    lastMoveTimeMs = now
                    lastMoveY = event.rawY
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dogView?.menuVisible == true) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handleMenuTap(event.x, event.y)
                    } else {
                        dogView?.menuVisible = false
                        dogView?.invalidate()
                    }
                } else if (isDragging) {
                    brain.onDragEnd(velocityY)
                    isDragging = false
                }
            }
        }
        return true
    }

    private fun handleMenuTap(localX: Float, localY: Float) {
        val view = dogView ?: return
        val centerX = view.width / 2f
        val centerY = view.height / 2f
        val dx = localX - centerX
        val dy = localY - centerY

        if (hypot(dx, dy) < 20f) {
            view.menuVisible = false
            view.invalidate()
            return
        }

        val angleDeg = Math.toDegrees(atan2(dy, dx).toDouble())
        val option = when {
            angleDeg > -135 && angleDeg <= -45 -> MenuOption.TREAT   // up
            angleDeg > -45 && angleDeg <= 45 -> MenuOption.FETCH     // right
            angleDeg > 45 && angleDeg <= 135 -> MenuOption.SLEEP     // down
            else -> MenuOption.SEND_HOME                              // left
        }

        brain.onLongPressSelect(option)
        if (option == MenuOption.SEND_HOME) {
            onSendHome?.invoke()
        }
        view.menuVisible = false
        view.invalidate()
    }
}
