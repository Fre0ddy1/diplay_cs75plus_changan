package com.shilapi.xcertplay.airplay

/** Wire identities used by this receiver; these are unrelated to Android stream/focus numbers. */
enum class CarPlayAudioOwner(val allowsMedia: Boolean) { UNKNOWN(true), PHONE(true), ACCESSORY(false), UNASSIGNED(false) }

/** Incremental modesChanged state. Missing, malformed and unknown rows never invent ownership. */
class CarPlayAudioResources {
    var owner: CarPlayAudioOwner = CarPlayAudioOwner.UNKNOWN
        private set
    var entity: Int? = null
        private set
    var permanentEntity: Int? = null
        private set
    var revision: Long = 0
        private set
    val mediaOutputAllowed get() = owner.allowsMedia

    fun update(params: Map<String, Any?>): Boolean {
        val resources = params["resources"] as? List<*> ?: return false
        val row = resources.filterIsInstance<Map<*, *>>().singleOrNull {
            wireInt(it["resourceID"]) == 2
        } ?: return false
        val next = wireInt(row["entity"]) ?: return false
        val parsed = when (next) {
            1 -> CarPlayAudioOwner.PHONE
            2 -> CarPlayAudioOwner.ACCESSORY
            0 -> CarPlayAudioOwner.UNASSIGNED
            else -> return false
        }
        owner = parsed
        entity = next
        permanentEntity = wireInt(row["permanentEntity"])
        revision++
        return true
    }

    fun reset() {
        owner = CarPlayAudioOwner.UNKNOWN
        entity = null
        permanentEntity = null
        revision++
    }

    private fun wireInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        is Short -> value.toInt()
        is Byte -> value.toInt()
        else -> null
    }
}
