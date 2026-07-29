package com.mahesh.pixelpup

class Needs(
    var hunger: Float = 20f,
    var energy: Float = 80f,
    var bladder: Float = 10f,
    var affection: Float = 70f
) {
    fun clamp() {
        hunger = hunger.coerceIn(0f, 100f)
        energy = energy.coerceIn(0f, 100f)
        bladder = bladder.coerceIn(0f, 100f)
        affection = affection.coerceIn(0f, 100f)
    }
}
