package com.yavin.reachoutandtouchscreen

import androidx.lifecycle.ViewModel

/** Retains only immutable logical state; renderer and GPU resources remain Activity-owned. */
internal class RippleStateViewModel : ViewModel() {
    private var retainedSnapshot = RippleSnapshot.Empty

    fun snapshot(): RippleSnapshot = retainedSnapshot

    fun retain(snapshot: RippleSnapshot) {
        retainedSnapshot = snapshot
    }
}
