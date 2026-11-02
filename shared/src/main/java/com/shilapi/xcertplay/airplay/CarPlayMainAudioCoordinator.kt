package com.shilapi.xcertplay.airplay

/** AirPlay mode-operation ABI; unrelated to Android audio streams or Bluetooth profiles. */
enum class MainAudioTransfer(val wire: Int) { TAKE(1), UNTAKE(2), BORROW(3), UNBORROW(4) }

fun mainAudioModeCommand(transfer: MainAudioTransfer): Map<String, Any?> {
    val resource = linkedMapOf<String, Any?>("resourceID" to 2, "transferType" to transfer.wire)
    if (transfer == MainAudioTransfer.TAKE || transfer == MainAudioTransfer.BORROW) {
        // Same conservative priority/constraints as this receiver's existing /info modes.
        resource["transferPriority"] = 100
        resource["takeConstraint"] = 100
        resource["borrowConstraint"] = 100
        resource["unborrowConstraint"] = 100
    }
    return linkedMapOf("type" to "changeModes", "params" to linkedMapOf("resources" to listOf(resource)))
}

data class MainAudioResourceState(
    val owner: CarPlayAudioOwner,
    val permanentEntity: Int?,
    val revision: Long,
    val nativeBorrowActive: Boolean,
    val mediaOutputAllowed: Boolean,
    val awaitingConfirmation: Boolean,
)

/**
 * One phone-session resource state, called on its owner's looper. A write/RTSP reply is not a grant:
 * only a valid modesChanged assigns ownership. No media key waits for this state machine.
 */
class CarPlayMainAudioCoordinator(
    private val send: (MainAudioTransfer, (Boolean) -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val changed: (MainAudioResourceState) -> Unit,
    private val log: (String) -> Unit,
) {
    private val resources = CarPlayAudioResources()
    private var nativeTake = false
    private var nativeBorrow = false
    private var localSuppression = false
    private var phoneManagedAudio = false
    private var serial = 0L
    private var closed = false
    private data class Pending(val serial: Long, val transfer: MainAudioTransfer, var cancel: (() -> Unit)? = null)
    private var pending: Pending? = null

    val state: MainAudioResourceState get() = MainAudioResourceState(resources.owner,
        resources.permanentEntity, resources.revision,
        nativeBorrow || pending?.transfer == MainAudioTransfer.UNBORROW,
        resources.owner == CarPlayAudioOwner.PHONE && !localSuppression, pending != null)

    fun start() { if (!closed) publish() }
    fun phoneManagedAudio(active: Boolean) { if (!closed) phoneManagedAudio = active }

    /** A phone call/Siri stream inside this receiver is not a native resource request. */
    fun nativeFocusLost(temporary: Boolean) {
        if (closed || phoneManagedAudio) return
        localSuppression = true
        if (temporary) {
            if (!nativeBorrow) { nativeBorrow = true; issue(MainAudioTransfer.BORROW) }
        } else if (!nativeTake) {
            if (nativeBorrow) { nativeBorrow = false; issue(MainAudioTransfer.UNBORROW) }
            nativeTake = true
            issue(MainAudioTransfer.TAKE)
        }
        publish()
    }

    /** Return a temporary native loan; do not auto-play music or surrender a permanent source. */
    fun nativeFocusGained() {
        if (closed || !nativeBorrow) return
        nativeBorrow = false
        issue(MainAudioTransfer.UNBORROW)
        publish()
    }

    /** An explicit PLAY/NEXT/PREV may release our claims, but the original HID goes immediately. */
    fun userPlaybackRequested() {
        if (closed) return
        if (nativeBorrow) { nativeBorrow = false; issue(MainAudioTransfer.UNBORROW) }
        if (nativeTake || resources.owner != CarPlayAudioOwner.PHONE || localSuppression) {
            nativeTake = false
            issue(MainAudioTransfer.UNTAKE)
        }
    }

    fun modesChanged(params: Map<String, Any?>): Boolean {
        if (closed || !resources.update(params)) return false
        // The phone may grant audio for its own app/Siri without a local request. Respect that.
        if (resources.owner == CarPlayAudioOwner.PHONE) {
            localSuppression = false
            nativeTake = false
        }
        val request = pending
        if (request != null && confirms(request.transfer)) {
            request.cancel?.invoke(); pending = null
            log("CarPlay mainAudio confirmed ${request.transfer} owner=${resources.owner} permanent=${resources.permanentEntity}")
        }
        publish()
        return true
    }

    private fun confirms(transfer: MainAudioTransfer) = when (transfer) {
        MainAudioTransfer.TAKE -> resources.owner == CarPlayAudioOwner.ACCESSORY && resources.permanentEntity == 2
        MainAudioTransfer.BORROW -> resources.owner == CarPlayAudioOwner.ACCESSORY
        MainAudioTransfer.UNTAKE -> resources.permanentEntity in setOf(0, 1) && resources.owner != CarPlayAudioOwner.ACCESSORY
        MainAudioTransfer.UNBORROW -> when (resources.permanentEntity) {
            0 -> resources.owner == CarPlayAudioOwner.UNASSIGNED
            1 -> resources.owner == CarPlayAudioOwner.PHONE
            2 -> resources.owner == CarPlayAudioOwner.ACCESSORY
            else -> false
        }
    }

    private fun issue(transfer: MainAudioTransfer) {
        pending?.cancel?.invoke()
        val request = Pending(++serial, transfer)
        pending = request
        publish()
        send(transfer) { written ->
            if (closed || pending !== request) return@send
            if (!written) {
                pending = null
                log("CarPlay mainAudio $transfer not written; ownership unchanged")
                publish()
            } else {
                log("CarPlay mainAudio $transfer written; awaiting modesChanged")
                request.cancel = schedule(2_500L) {
                    if (!closed && pending === request) {
                        pending = null
                        log("CarPlay mainAudio $transfer confirmation timed out; ownership unchanged")
                        publish()
                    }
                }
            }
        }
    }

    private fun publish() { if (!closed) changed(state) }
    fun close() {
        if (closed) return
        closed = true; serial++
        pending?.cancel?.invoke(); pending = null
        nativeTake = false; nativeBorrow = false; phoneManagedAudio = false
        resources.reset()
    }
}
