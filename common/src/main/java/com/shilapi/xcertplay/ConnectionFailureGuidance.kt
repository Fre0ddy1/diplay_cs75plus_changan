package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause

internal object ConnectionFailureGuidance {
    @StringRes fun stage(cause: ConnectionFailureCause): Int = when (cause) {
        ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED -> R.string.chosen_iphone_not_paired
        ConnectionFailureCause.NO_PAIRED_IPHONE -> R.string.no_paired_iphone
        ConnectionFailureCause.IPHONE_NOT_CHOSEN -> R.string.iphone_not_chosen
        ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE -> R.string.wifi_direct_channel_unavailable
        ConnectionFailureCause.CAR_HOTSPOT_OFF -> R.string.car_hotspot_is_off
        ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER -> R.string.car_hotspot_details_differ
        ConnectionFailureCause.CAR_HOTSPOT_START_TIMED_OUT -> R.string.car_hotspot_start_timed_out
        ConnectionFailureCause.CAR_HOTSPOT_START_FAILED -> R.string.car_hotspot_start_failed
        ConnectionFailureCause.HOTSPOT_NOT_READY -> R.string.hotspot_not_ready_diagnostic
        ConnectionFailureCause.HOTSPOT_NOT_CONFIGURED -> R.string.hotspot_not_configured
        ConnectionFailureCause.BLUETOOTH_OFF -> R.string.bluetooth_is_off_diagnostic
        ConnectionFailureCause.BLUETOOTH_UNAVAILABLE -> R.string.bluetooth_unavailable_diagnostic
    }
}
