package com.yavin.reachoutandtouchscreen

/** Valid touch-reaction combinations exposed by the scene controls. */
internal enum class TouchReactionMode(
    val createsRipple: Boolean,
    val createsDent: Boolean,
) {
    NONE(createsRipple = false, createsDent = false),
    RIPPLE_ONLY(createsRipple = true, createsDent = false),
    RIPPLE_AND_DENT(createsRipple = true, createsDent = true),
    ;

    companion object {
        fun from(touchReactionEnabled: Boolean, dentsEnabled: Boolean): TouchReactionMode = when {
            !touchReactionEnabled -> NONE
            dentsEnabled -> RIPPLE_AND_DENT
            else -> RIPPLE_ONLY
        }
    }
}
