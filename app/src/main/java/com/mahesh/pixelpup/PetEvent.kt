package com.mahesh.pixelpup

/**
 * One-shot outputs the brain produces during a tick. The service/view drain
 * these each frame to trigger sounds and particles; they are cleared at the
 * start of every tick() call.
 */
sealed class PetEvent {
    data class Particles(val type: ParticleType) : PetEvent()
    data class Sound(val type: SoundType) : PetEvent()
    data class Bubble(val text: String) : PetEvent()
}

enum class ParticleType { HEART, ZZZ, DUST, WATER_DROP }
enum class SoundType { BARK, WHINE, YIP, DOUBLE_BARK }

enum class MenuOption { TREAT, FETCH, SLEEP, SEND_HOME }

data class PetSaveState(
    val x: Float,
    val y: Float,
    val hunger: Float,
    val energy: Float,
    val bladder: Float,
    val affection: Float,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val lastSeenTimestampMillis: Long
)
