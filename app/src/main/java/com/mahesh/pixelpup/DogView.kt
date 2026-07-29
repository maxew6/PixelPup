package com.mahesh.pixelpup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the whole pet procedurally with Canvas primitives -- no bitmaps,
 * no vector assets for the dog itself. The Service's Choreographer loop
 * calls invalidate() every frame; this view just reads the current brain
 * state and renders it.
 */
class DogView(context: Context) : View(context) {

    var brain: PetBrain? = null
    var sizeDp: Float = 84f
    var menuVisible: Boolean = false

    private val density = context.resources.displayMetrics.density
    private val particles = mutableListOf<ViewParticle>()

    private var blinkTimer = 0f
    private var blinkDuration = 0f
    private var isBlinking = false
    private var wagPhase = 0f
    private var walkPhase = 0f
    private var lastDrawNanos = 0L

    private val furPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C98A4B") }
    private val furShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A96F38") }
    private val bellyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2DCC0") }
    private val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A2A21") }
    private val collarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E4572E") }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B2B2B") }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFFFFEE") }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2B")
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }
    private val menuBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DD2B2B2B") }
    private val menuTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val boxPx = ((sizeDp + 140f) * density).toInt()
        setMeasuredDimension(boxPx, boxPx)
    }

    fun spawnParticles(type: ParticleType) {
        val cx = width / 2f
        val cy = height / 2f
        val count = when (type) {
            ParticleType.HEART -> 4
            ParticleType.ZZZ -> 1
            ParticleType.DUST -> 6
            ParticleType.WATER_DROP -> 3
        }
        repeat(count) {
            particles.add(
                ViewParticle(
                    type = type,
                    x = cx + Random.nextFloat() * 20f - 10f,
                    y = cy,
                    vx = (Random.nextFloat() - 0.5f) * 20f,
                    vy = -30f - Random.nextFloat() * 20f,
                    life = 1.2f + Random.nextFloat() * 0.6f,
                    age = 0f
                )
            )
        }
        while (particles.size > 40) {
            particles.removeAt(0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = brain
        val nowNanos = System.nanoTime()
        val dt = if (lastDrawNanos == 0L) {
            0.016f
        } else {
            ((nowNanos - lastDrawNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        }
        lastDrawNanos = nowNanos

        updateAnimationClocks(dt, b)
        updateParticles(dt)

        val cx = width / 2f
        val cy = height / 2f
        val sizePx = sizeDp * density
        val facingRight = b?.facingRight ?: true

        canvas.save()
        canvas.translate(cx, cy)
        if (!facingRight) canvas.scale(-1f, 1f)
        drawDog(canvas, sizePx, b)
        canvas.restore()

        drawParticles(canvas)

        val bubbleText = b?.thoughtBubbleText
        if (bubbleText != null) {
            drawBubble(canvas, cx, cy - sizePx * 0.9f, bubbleText)
        }

        if (menuVisible) {
            drawRadialMenu(canvas, cx, cy)
        }
    }

    private fun updateAnimationClocks(dt: Float, b: PetBrain?) {
        blinkTimer += dt
        if (!isBlinking && blinkTimer > 3f + Random.nextFloat() * 3f) {
            isBlinking = true
            blinkDuration = 0f
        }
        if (isBlinking) {
            blinkDuration += dt
            if (blinkDuration > 0.12f) {
                isBlinking = false
                blinkTimer = 0f
            }
        }

        val speed = b?.currentSpeedPxPerSec ?: 0f
        walkPhase += dt * (2f + speed / 60f)

        val wagSpeed = when (b?.mood) {
            Mood.ECSTATIC -> 14f
            Mood.HAPPY -> 9f
            Mood.SAD, Mood.DESPERATE -> 1.5f
            else -> 5f
        }
        wagPhase += dt * wagSpeed
    }

    private fun drawDog(canvas: Canvas, sizePx: Float, b: PetBrain?) {
        val mood = b?.mood ?: Mood.CONTENT
        val state = b?.state ?: PetState.IDLE
        val sad = mood == Mood.SAD || mood == Mood.DESPERATE
        val happy = mood == Mood.HAPPY || mood == Mood.ECSTATIC
        val moving = state == PetState.WALK || state == PetState.RUN || state == PetState.ZOOMIES

        val bodyW = sizePx * 1.1f
        val bodyH = sizePx * 0.62f

        // legs
        val legW = sizePx * 0.14f
        val legH = sizePx * 0.28f
        for (i in 0 until 4) {
            val lx = -bodyW * 0.32f + i * (bodyW * 0.64f / 3f)
            val phase = if (i % 2 == 0) walkPhase else walkPhase + Math.PI.toFloat()
            val lift = if (moving) (sin(phase * 3f).coerceAtLeast(0f)) * sizePx * 0.10f else 0f
            val rect = RectF(lx - legW / 2f, bodyH * 0.35f - lift, lx + legW / 2f, bodyH * 0.35f + legH - lift)
            canvas.drawRoundRect(rect, legW / 3f, legW / 3f, furShadowPaint)
        }

        // tail
        val tailPath = Path()
        val tailBaseX = -bodyW * 0.48f
        val tailBaseY = -bodyH * 0.1f
        val wagOffset = sin(wagPhase) * sizePx * 0.22f
        tailPath.moveTo(tailBaseX, tailBaseY)
        tailPath.quadTo(
            tailBaseX - sizePx * 0.28f, tailBaseY - sizePx * 0.15f + wagOffset,
            tailBaseX - sizePx * 0.4f, tailBaseY - sizePx * 0.35f + wagOffset
        )
        val tailPaint = Paint(furPaint)
        tailPaint.style = Paint.Style.STROKE
        tailPaint.strokeWidth = sizePx * 0.12f
        tailPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(tailPath, tailPaint)

        // body
        val bodyRect = RectF(-bodyW / 2f, -bodyH / 2f, bodyW / 2f, bodyH / 2f)
        canvas.drawOval(bodyRect, furPaint)
        canvas.drawOval(bodyRect, outlinePaint)

        val bellyRect = RectF(-bodyW * 0.32f, -bodyH * 0.05f, bodyW * 0.28f, bodyH * 0.45f)
        canvas.drawOval(bellyRect, bellyPaint)

        // collar
        canvas.drawRect(-bodyW * 0.1f, bodyH * 0.32f, bodyW * 0.12f, bodyH * 0.4f, collarPaint)

        // head
        val headCx = bodyW * 0.42f
        val headCy = -bodyH * 0.32f
        val headR = sizePx * 0.32f

        // ears (droop when sad, perk when happy)
        val earDroop = if (sad) headR * 0.9f else if (happy) headR * 0.05f else headR * 0.2f
        val earPathLeft = Path().apply {
            moveTo(headCx - headR * 0.6f, headCy - headR * 0.3f)
            quadTo(headCx - headR * 1.3f, headCy - headR * 0.1f + earDroop * 0.3f, headCx - headR * 1.1f, headCy + earDroop)
            quadTo(headCx - headR * 0.7f, headCy + earDroop * 0.6f, headCx - headR * 0.4f, headCy + headR * 0.1f)
            close()
        }
        canvas.drawPath(earPathLeft, furShadowPaint)
        val earPathRight = Path().apply {
            moveTo(headCx + headR * 0.5f, headCy - headR * 0.5f)
            quadTo(headCx + headR * 1.2f, headCy - headR * 0.3f + earDroop * 0.3f, headCx + headR * 1.0f, headCy + earDroop * 0.8f)
            quadTo(headCx + headR * 0.7f, headCy + earDroop * 0.5f, headCx + headR * 0.3f, headCy - headR * 0.1f)
            close()
        }
        canvas.drawPath(earPathRight, furShadowPaint)

        canvas.drawCircle(headCx, headCy, headR, furPaint)
        canvas.drawCircle(headCx, headCy, headR, outlinePaint)

        // snout
        val snoutRect = RectF(headCx + headR * 0.3f, headCy + headR * 0.1f, headCx + headR * 1.15f, headCy + headR * 0.65f)
        canvas.drawOval(snoutRect, bellyPaint)
        canvas.drawCircle(headCx + headR * 1.05f, headCy + headR * 0.35f, headR * 0.12f, nosePaint)

        // eyes (blink)
        val eyeY = headCy - headR * 0.1f
        val eyeOpen = !isBlinking
        val eyeH = if (eyeOpen) headR * 0.16f else headR * 0.02f
        canvas.drawOval(RectF(headCx - headR * 0.05f, eyeY - eyeH, headCx + headR * 0.2f, eyeY + eyeH), eyePaint)
        canvas.drawOval(RectF(headCx + headR * 0.4f, eyeY - eyeH, headCx + headR * 0.65f, eyeY + eyeH), eyePaint)

        // tongue when panting
        if (happy || state == PetState.RUN || state == PetState.ZOOMIES) {
            val tongueRect = RectF(
                headCx + headR * 0.55f, headCy + headR * 0.55f,
                headCx + headR * 0.75f, headCy + headR * 0.95f
            )
            canvas.drawOval(tongueRect, collarPaint)
        }
    }

    private fun drawParticles(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (p in particles) {
            val alpha = (255 * (1f - p.age / p.life)).toInt().coerceIn(0, 255)
            paint.alpha = alpha
            when (p.type) {
                ParticleType.HEART -> {
                    paint.color = Color.parseColor("#E4572E")
                    canvas.drawCircle(p.x, p.y, 6f * density, paint)
                }
                ParticleType.ZZZ -> {
                    paint.color = Color.parseColor("#5B5B5B")
                    paint.textSize = 14f * density
                    canvas.drawText("Z", p.x, p.y, paint)
                }
                ParticleType.DUST -> {
                    paint.color = Color.parseColor("#C9B89A")
                    canvas.drawCircle(p.x, p.y, 3f * density, paint)
                }
                ParticleType.WATER_DROP -> {
                    paint.color = Color.parseColor("#7FB3D5")
                    canvas.drawCircle(p.x, p.y, 4f * density, paint)
                }
            }
        }
    }

    private fun updateParticles(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.age += dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 20f * dt
            if (p.age >= p.life) iterator.remove()
        }
    }

    private fun drawBubble(canvas: Canvas, x: Float, y: Float, text: String) {
        val padding = 8f * density
        val textWidth = bubbleTextPaint.measureText(text)
        val rect = RectF(x - textWidth / 2f - padding, y - 16f * density, x + textWidth / 2f + padding, y + 16f * density)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, bubblePaint)
        canvas.drawText(text, x, y + 5f * density, bubbleTextPaint)
    }

    private fun drawRadialMenu(canvas: Canvas, cx: Float, cy: Float) {
        val radius = 70f * density
        val options = listOf(
            Triple("Treat", 0f, -radius),
            Triple("Fetch", radius, 0f),
            Triple("Sleep", 0f, radius),
            Triple("Home", -radius, 0f)
        )
        for ((label, ox, oy) in options) {
            canvas.drawCircle(cx + ox, cy + oy, 26f * density, menuBgPaint)
            canvas.drawText(label, cx + ox, cy + oy + 4f * density, menuTextPaint)
        }
    }

    private data class ViewParticle(
        val type: ParticleType,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val life: Float,
        var age: Float
    )
}
