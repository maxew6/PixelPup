package com.mahesh.pixelpup

enum class PetState(
    val minDurationSec: Float,
    val maxDurationSec: Float,
    val interruptible: Boolean
) {
    IDLE(1.5f, 4f, true),
    WALK(2f, 6f, true),
    RUN(1.5f, 4f, true),
    SIT(2f, 5f, true),
    SLEEP(20f, 999f, true),
    PEE(2f, 3f, false),
    SCRATCH(1f, 2f, false),
    SHAKE(0.8f, 1.2f, false),
    SNIFF(1.5f, 3f, true),
    BEG(2f, 4f, true),
    BARK(0.5f, 0.8f, false),
    DRAGGED(0f, 999f, true),
    FALLING(0f, 999f, false),
    ZOOMIES(6f, 10f, false),
    LIE_DOWN(10f, 999f, true)
}
