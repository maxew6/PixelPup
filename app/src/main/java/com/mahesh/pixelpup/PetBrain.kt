package com.mahesh.pixelpup

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pure Kotlin brain for Pixel Pup. Deliberately has zero android.* imports
 * so it can never crash the UI thread and stays trivially testable. It works
 * entirely in caller-supplied pixel coordinates; the Service/View translate
 * to and from real screen/window space.
 */
class PetBrain {

    var x: Float = 100f
    var y: Float = 800f
    var facingRight: Boolean = true

    var state: PetState = PetState.IDLE
        private set

    var mood: Mood = Mood.CONTENT
        private set

    val needs = Needs()

    var batteryPercent: Int = 100
        private set
    var isCharging: Boolean = false
        private set

    var currentSpeedPxPerSec: Float = 0f
        private set

    var thoughtBubbleText: String? = null
        private set

    val events = mutableListOf<PetEvent>()

    private var screenWidthPx: Float = 1080f
    private var screenHeightPx: Float = 1920f
    private var petHeightPx: Float = 200f
    private var speedMultiplier: Float = 1f

    private var stateTimer: Float = 0f
    private var stateDuration: Float = 2f
    private var targetX: Float = 0f
    private var targetY: Float = 0f
    private var totalTripDistance: Float = 1f

    private var wobblePhase: Float = 0f
    private var headingJitterTimer: Float = 0f
    private var microPauseTimer: Float = 0f
    private var thoughtBubbleTimer: Float = 0f
    private var whimperTimer: Float = 0f
    private var fallVelocityY: Float = 0f

    private val random = Random.Default

    init {
        enterState(PetState.IDLE)
    }

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    fun setScreenSize(widthPx: Float, heightPx: Float) {
        screenWidthPx = widthPx
        screenHeightPx = heightPx
    }

    fun setPetHeightPx(heightPx: Float) {
        petHeightPx = heightPx
    }

    fun setSpeedMultiplier(multiplier: Float) {
        speedMultiplier = multiplier
    }

    // ---------------------------------------------------------------------
    // Main loop
    // ---------------------------------------------------------------------

    fun tick(dtSecondsRaw: Float, hourOfDay: Int) {
        events.clear()
        val dt = dtSecondsRaw.coerceIn(0f, 0.05f)

        if (state == PetState.DRAGGED) {
            return
        }

        updateNeeds(dt)
        updateMood(hourOfDay)
        updateThoughtBubble(dt)
        updateWhimper(dt)
        updateStateMachine(dt, hourOfDay)
        updateMovement(dt)
        clampPosition()
    }

    private fun updateNeeds(dt: Float) {
        val minutes = dt / 60f
        needs.hunger += 0.35f * minutes
        needs.bladder += 0.30f * minutes
        needs.affection -= 0.20f * minutes
        val energyRate = when (state) {
            PetState.SLEEP -> 2.0f
            PetState.RUN, PetState.ZOOMIES -> -0.90f
            else -> -0.25f
        }
        needs.energy += energyRate * minutes
        needs.clamp()
    }

    private fun updateMood(hourOfDay: Int) {
        mood = when {
            batteryPercent < 15 -> Mood.DESPERATE
            needs.energy < 15f || hourOfDay in 0..5 -> Mood.SLEEPY
            needs.affection < 25f -> Mood.SAD
            isCharging && batteryPercent >= 95 -> Mood.ECSTATIC
            needs.hunger > 75f || needs.bladder > 85f -> Mood.BORED
            needs.hunger < 40f && needs.energy > 50f && needs.affection > 60f -> Mood.HAPPY
            else -> Mood.CONTENT
        }
    }

    private fun updateThoughtBubble(dt: Float) {
        if (thoughtBubbleTimer > 0f) {
            thoughtBubbleTimer -= dt
            if (thoughtBubbleTimer <= 0f) {
                thoughtBubbleText = null
            }
        }
    }

    private fun updateWhimper(dt: Float) {
        if (mood == Mood.DESPERATE) {
            whimperTimer += dt
            if (whimperTimer >= 40f) {
                whimperTimer = 0f
                events.add(PetEvent.Sound(SoundType.WHINE))
                showBubble("...")
            }
        } else {
            whimperTimer = 0f
        }
    }

    // ---------------------------------------------------------------------
    // State machine
    // ---------------------------------------------------------------------

    private fun updateStateMachine(dt: Float, hourOfDay: Int) {
        if (state == PetState.FALLING) return

        stateTimer += dt

        if (state == PetState.IDLE && random.nextFloat() < 0.02f * dt) {
            val spice = listOf(PetState.SCRATCH, PetState.SHAKE, PetState.BARK, PetState.SNIFF).random(random)
            enterState(spice)
            return
        }

        // Priority-driven states (sleep / low-battery lie-down / full-charge sit)
        // end the moment their triggering condition clears, instead of waiting
        // out their (potentially very long) random timer.
        if (isPriorityDrivenState(state) && !priorityConditionHolds(state, hourOfDay)) {
            enterState(decideNextState(hourOfDay))
            return
        }

        if (stateTimer >= stateDuration) {
            enterState(decideNextState(hourOfDay))
            return
        }

        if (state.interruptible) {
            val priority = priorityState(hourOfDay)
            if (priority != null && priority != state) {
                enterState(priority)
            }
        }
    }

    private fun isPriorityDrivenState(s: PetState): Boolean {
        return s == PetState.SLEEP || s == PetState.LIE_DOWN || s == PetState.SIT
    }

    private fun priorityConditionHolds(s: PetState, hourOfDay: Int): Boolean {
        return when (s) {
            PetState.SLEEP -> needs.energy < 15f || hourOfDay in 0..5
            PetState.LIE_DOWN -> batteryPercent < 15
            PetState.SIT -> isCharging && batteryPercent >= 95
            else -> true
        }
    }

    private fun priorityState(hourOfDay: Int): PetState? {
        return when {
            needs.bladder > 85f -> if (isNearEdge()) PetState.PEE else PetState.WALK
            batteryPercent < 15 -> PetState.LIE_DOWN
            isCharging && batteryPercent >= 95 -> PetState.SIT
            needs.energy < 15f || hourOfDay in 0..5 -> PetState.SLEEP
            else -> null
        }
    }

    private fun decideNextState(hourOfDay: Int): PetState {
        val priority = priorityState(hourOfDay)
        if (priority != null) return priority

        if (needs.hunger > 75f && random.nextFloat() < 0.5f) {
            return if (random.nextBoolean()) PetState.SNIFF else PetState.BEG
        }

        val roll = random.nextFloat()
        return when {
            roll < 0.32f -> PetState.WALK
            roll < 0.42f -> PetState.RUN
            roll < 0.58f -> PetState.IDLE
            roll < 0.68f -> PetState.SIT
            roll < 0.75f -> PetState.SNIFF
            roll < 0.81f -> PetState.SCRATCH
            roll < 0.87f -> PetState.SHAKE
            roll < 0.93f -> PetState.BEG
            roll < 0.97f -> PetState.BARK
            else -> PetState.IDLE
        }
    }

    private fun isNearEdge(): Boolean {
        val distLeft = x
        val distRight = screenWidthPx - petHeightPx - x
        return min(distLeft, distRight) < screenWidthPx * 0.08f
    }

    private fun enterState(newState: PetState) {
        state = newState
        stateTimer = 0f
        stateDuration = randomInRange(newState.minDurationSec, newState.maxDurationSec)
        when (newState) {
            PetState.WALK -> {
                if (needs.bladder > 85f) {
                    targetToNearestEdge()
                } else {
                    pickNewTarget()
                }
            }
            PetState.RUN, PetState.ZOOMIES -> pickNewTarget()
            PetState.PEE -> {
                needs.bladder = 0f
                events.add(PetEvent.Particles(ParticleType.WATER_DROP))
            }
            PetState.BARK -> events.add(PetEvent.Sound(SoundType.BARK))
            PetState.SLEEP -> events.add(PetEvent.Particles(ParticleType.ZZZ))
            PetState.SIT -> if (isCharging && batteryPercent >= 95) showBubble("woof!")
            PetState.SCRATCH, PetState.SHAKE -> events.add(PetEvent.Particles(ParticleType.DUST))
            else -> {}
        }
    }

    private fun enterMovementStateWithTarget(newState: PetState, tx: Float, ty: Float) {
        state = newState
        stateTimer = 0f
        stateDuration = randomInRange(newState.minDurationSec, newState.maxDurationSec)
        targetX = tx
        targetY = ty
        totalTripDistance = hypot(targetX - x, targetY - y).coerceAtLeast(1f)
    }

    private fun pickNewTarget() {
        val minDist = screenWidthPx * 0.15f
        val maxX = (screenWidthPx - petHeightPx).coerceAtLeast(1f)
        var newTargetX = x
        var attempts = 0
        do {
            newTargetX = random.nextFloat() * maxX
            attempts++
        } while (kotlin.math.abs(newTargetX - x) < minDist && attempts < 8)
        targetX = newTargetX

        val bandTop = screenHeightPx * 0.75f
        val bandBottom = (screenHeightPx - petHeightPx).coerceAtLeast(bandTop)
        targetY = bandTop + random.nextFloat() * (bandBottom - bandTop).coerceAtLeast(1f)

        totalTripDistance = hypot(targetX - x, targetY - y).coerceAtLeast(1f)
    }

    private fun targetToNearestEdge() {
        val distLeft = x
        val distRight = screenWidthPx - petHeightPx - x
        targetX = if (distLeft < distRight) 0f else (screenWidthPx - petHeightPx).coerceAtLeast(0f)
        val bandTop = screenHeightPx * 0.75f
        val bandBottom = (screenHeightPx - petHeightPx).coerceAtLeast(bandTop)
        targetY = y.coerceIn(bandTop, bandBottom)
        totalTripDistance = hypot(targetX - x, targetY - y).coerceAtLeast(1f)
    }

    // ---------------------------------------------------------------------
    // Movement
    // ---------------------------------------------------------------------

    private fun updateMovement(dt: Float) {
        when (state) {
            PetState.WALK, PetState.RUN, PetState.ZOOMIES -> moveTowardTarget(dt)
            PetState.FALLING -> applyGravity(dt)
            else -> currentSpeedPxPerSec = 0f
        }
    }

    private fun moveTowardTarget(dt: Float) {
        if (microPauseTimer > 0f) {
            microPauseTimer -= dt
            currentSpeedPxPerSec = 0f
            return
        }

        val dx = targetX - x
        val dy = targetY - y
        val dist = hypot(dx, dy)
        if (dist < 4f) {
            currentSpeedPxPerSec = 0f
            return
        }

        val baseSpeed = when (state) {
            PetState.RUN -> 260f
            PetState.ZOOMIES -> 420f
            else -> 110f
        } * speedMultiplier * (if (needs.hunger > 75f) 0.7f else 1f)

        val progress = (1f - (dist / totalTripDistance)).coerceIn(0f, 1f)
        val speed = when {
            progress < 0.2f -> baseSpeed * (progress / 0.2f).coerceIn(0.15f, 1f)
            progress > 0.75f -> baseSpeed * ((1f - progress) / 0.25f).coerceIn(0.15f, 1f)
            else -> baseSpeed
        }
        currentSpeedPxPerSec = speed

        wobblePhase += dt
        val wobble = sin(wobblePhase * 6f) * 3f + sin(wobblePhase * 2.3f) * 1.5f

        headingJitterTimer += dt
        var jitterRad = 0f
        if (headingJitterTimer > 1.5f) {
            headingJitterTimer = 0f
            jitterRad = ((random.nextFloat() * 24f) - 12f) * (PI.toFloat() / 180f)
        }

        val angle = atan2(dy, dx) + jitterRad
        val moveX = cos(angle) * speed * dt
        val moveY = sin(angle) * speed * dt + wobble * dt

        x += moveX
        y += moveY
        facingRight = moveX >= 0f

        if (random.nextFloat() < 0.4f * dt) {
            microPauseTimer = 0.2f + random.nextFloat() * 0.7f
        }
    }

    private fun applyGravity(dt: Float) {
        fallVelocityY += 2200f * dt
        y += fallVelocityY * dt
        val groundY = screenHeightPx - petHeightPx
        if (y >= groundY) {
            y = groundY
            val hardLanding = fallVelocityY > 1800f
            fallVelocityY = 0f
            events.add(PetEvent.Sound(SoundType.YIP))
            if (hardLanding) {
                showBubble("hmph.")
                enterState(PetState.SHAKE)
            } else {
                enterState(PetState.SIT)
            }
        }
    }

    private fun clampPosition() {
        val maxX = (screenWidthPx - petHeightPx).coerceAtLeast(0f)
        val maxY = (screenHeightPx - petHeightPx).coerceAtLeast(0f)
        x = x.coerceIn(0f, maxX)
        y = y.coerceIn(0f, maxY)
    }

    private fun showBubble(text: String) {
        thoughtBubbleText = text
        thoughtBubbleTimer = 2.5f
        events.add(PetEvent.Bubble(text))
    }

    private fun randomInRange(minValue: Float, maxValue: Float): Float {
        if (maxValue <= minValue) return minValue
        // Cap absurdly long "effectively infinite" durations (used by
        // SLEEP/LIE_DOWN/DRAGGED/FALLING) to a short re-check cycle; the
        // priority-condition check above re-enters the same state as long
        // as it's still warranted.
        val cappedMax = if (maxValue > 500f) minValue + 6f else maxValue
        return minValue + random.nextFloat() * (cappedMax - minValue)
    }

    // ---------------------------------------------------------------------
    // Phone / battery events (called by the Service on broadcast receipt)
    // ---------------------------------------------------------------------

    fun onPowerConnected() {
        isCharging = true
        events.add(PetEvent.Sound(SoundType.DOUBLE_BARK))
        events.add(PetEvent.Particles(ParticleType.HEART))
        enterState(PetState.ZOOMIES)
    }

    fun onPowerDisconnected() {
        isCharging = false
    }

    fun onBatteryChanged(percent: Int) {
        batteryPercent = percent
    }

    fun onScreenOn() {
        // Reserved for future reactions; no behaviour change required today.
    }

    fun onUserPresent() {
        val tx = screenWidthPx / 2f - petHeightPx / 2f
        val ty = screenHeightPx - petHeightPx
        enterMovementStateWithTarget(PetState.RUN, tx, ty)
        events.add(PetEvent.Sound(SoundType.BARK))
        showBubble("hi!")
    }

    // ---------------------------------------------------------------------
    // Touch events (called by TouchController)
    // ---------------------------------------------------------------------

    fun onTap() {
        needs.affection = (needs.affection + 25f).coerceAtMost(100f)
        events.add(PetEvent.Particles(ParticleType.HEART))
        events.add(PetEvent.Sound(SoundType.BARK))
    }

    fun onDoubleTap() {
        needs.hunger = (needs.hunger - 35f).coerceAtLeast(0f)
        showBubble("nom nom")
    }

    fun onLongPressSelect(option: MenuOption) {
        when (option) {
            MenuOption.TREAT -> onDoubleTap()
            MenuOption.FETCH -> enterState(PetState.RUN)
            MenuOption.SLEEP -> enterState(PetState.SLEEP)
            MenuOption.SEND_HOME -> { /* service stops itself */ }
        }
    }

    fun onDragStart() {
        state = PetState.DRAGGED
        stateTimer = 0f
        stateDuration = 999f
        fallVelocityY = 0f
    }

    fun onDragMove(newX: Float, newY: Float) {
        x = newX
        y = newY
    }

    fun onDragEnd(velocityYPxPerSec: Float) {
        fallVelocityY = velocityYPxPerSec.coerceAtLeast(0f)
        state = PetState.FALLING
        stateTimer = 0f
        stateDuration = 999f
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    fun applyOfflineDecay(elapsedSeconds: Float) {
        val minutes = (elapsedSeconds / 60f).coerceAtMost(60f * 24f * 3f)
        needs.hunger += 0.35f * minutes
        needs.bladder += 0.30f * minutes
        needs.affection -= 0.20f * minutes
        needs.energy += 0.60f * minutes
        needs.clamp()
    }

    fun snapshotForSave(nowMillis: Long): PetSaveState = PetSaveState(
        x = x,
        y = y,
        hunger = needs.hunger,
        energy = needs.energy,
        bladder = needs.bladder,
        affection = needs.affection,
        batteryPercent = batteryPercent,
        isCharging = isCharging,
        lastSeenTimestampMillis = nowMillis
    )

    fun restore(save: PetSaveState) {
        x = save.x
        y = save.y
        needs.hunger = save.hunger
        needs.energy = save.energy
        needs.bladder = save.bladder
        needs.affection = save.affection
        batteryPercent = save.batteryPercent
        isCharging = save.isCharging
    }
}
