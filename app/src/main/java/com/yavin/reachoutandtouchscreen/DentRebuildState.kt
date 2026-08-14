package com.yavin.reachoutandtouchscreen

internal data class DentRebuildRequest(
    val slotIndex: Int,
    val version: Long,
    val coalescedDentUpdates: Int,
    val waitedForFreeSlot: Boolean,
)

/** Pure single-threaded version and upload-slot ownership state for dent rebuild scheduling. */
internal class DentRebuildState(private val slotCount: Int = 2) {
    private val uploadsRemainingBySlot = IntArray(slotCount)
    private var lastRebuiltVersion = 0L
    private var rebuildTaskPending = false
    private var waitedForFreeSlot = false

    var authoritativeVersion: Long = 0L
        private set

    var submittedVersion: Long = 0L
        private set

    init {
        require(slotCount > 0)
    }

    fun reserveInitialUploadSlot(): Int {
        check(authoritativeVersion == 0L && submittedVersion == 0L)
        return reserveFreeSlot().also { check(it >= 0) }
    }

    fun markInitialUploadSubmitted(slotIndex: Int) {
        check(uploadsRemainingBySlot[slotIndex] == SLOT_RESERVED)
        uploadsRemainingBySlot[slotIndex] = UPLOAD_PARTS_PER_SLOT
    }

    /** Returns true only when the caller must enqueue the single rebuild task. */
    fun onDentUpdated(): Boolean {
        authoritativeVersion++
        return requestRebuildIfPossible()
    }

    fun beginPendingRebuild(): DentRebuildRequest? {
        if (!rebuildTaskPending) return null
        rebuildTaskPending = false
        if (authoritativeVersion <= submittedVersion) return null

        val slotIndex = reserveFreeSlot()
        if (slotIndex < 0) {
            waitedForFreeSlot = true
            return null
        }
        val version = authoritativeVersion
        val coalescedDentUpdates = (version - lastRebuiltVersion).coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        lastRebuiltVersion = version
        return DentRebuildRequest(
            slotIndex = slotIndex,
            version = version,
            coalescedDentUpdates = coalescedDentUpdates,
            waitedForFreeSlot = waitedForFreeSlot,
        ).also { waitedForFreeSlot = false }
    }

    /** Marks both asynchronous uploads as in flight and reports whether another task is needed. */
    fun markSubmitted(request: DentRebuildRequest): Boolean {
        check(uploadsRemainingBySlot[request.slotIndex] == SLOT_RESERVED)
        check(request.version >= submittedVersion)
        uploadsRemainingBySlot[request.slotIndex] = UPLOAD_PARTS_PER_SLOT
        submittedVersion = request.version
        return requestRebuildIfPossible()
    }

    /** Releases a slot only after its position and tangent callbacks have both arrived. */
    fun onUploadPartCompleted(slotIndex: Int): Boolean {
        val uploadsRemaining = uploadsRemainingBySlot[slotIndex]
        check(uploadsRemaining > 0)
        uploadsRemainingBySlot[slotIndex] = uploadsRemaining - 1
        return if (uploadsRemaining == 1) requestRebuildIfPossible() else false
    }

    fun inFlightSlotCount(): Int = uploadsRemainingBySlot.count { it != SLOT_FREE }

    fun isDirty(): Boolean = authoritativeVersion > submittedVersion

    fun isRebuildTaskPending(): Boolean = rebuildTaskPending

    fun cancelPendingTask() {
        rebuildTaskPending = false
    }

    private fun requestRebuildIfPossible(): Boolean {
        if (!isDirty() || rebuildTaskPending) return false
        if (uploadsRemainingBySlot.none { it == SLOT_FREE }) {
            waitedForFreeSlot = true
            return false
        }
        rebuildTaskPending = true
        return true
    }

    private fun reserveFreeSlot(): Int {
        val slotIndex = uploadsRemainingBySlot.indexOfFirst { it == SLOT_FREE }
        if (slotIndex >= 0) uploadsRemainingBySlot[slotIndex] = SLOT_RESERVED
        return slotIndex
    }

    private companion object {
        const val SLOT_FREE = 0
        const val SLOT_RESERVED = -1
        const val UPLOAD_PARTS_PER_SLOT = 2
    }
}
