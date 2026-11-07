package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Presentation only: never infer a connection stage by matching translated text. */
internal object ConnectionStageText {
    @StringRes
    fun resource(status: CarPlayStatus, wireless: Boolean, mode: WirelessHotspotMode): Int = when (status) {
        CarPlayStatus.DiscoveringMfi -> R.string.preparing_mfi_authentication
        CarPlayStatus.WaitingForMfi -> R.string.waiting_for_mfi_coprocessor
        CarPlayStatus.RequestingMfiPermission -> R.string.requesting_mfi_usb_permission
        CarPlayStatus.MfiReady -> R.string.mfi_authentication_ready
        CarPlayStatus.StartingHotspot -> if (mode == WirelessHotspotMode.EXISTING_WIFI)
            R.string.existing_wifi_attaching else R.string.starting_wireless_hotspot
        is CarPlayStatus.HotspotReady -> R.string.connection_network_ready
        CarPlayStatus.WaitingForPairedIphone -> R.string.looking_for_your_paired_iphone
        CarPlayStatus.ConnectingBluetooth -> R.string.connecting_to_your_iphone
        CarPlayStatus.RunningWireless, CarPlayStatus.WirelessActive, CarPlayStatus.RunningControl -> R.string.opening_carplay
        CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone -> R.string.connect_your_iphone_with_a_usb_cable
        is CarPlayStatus.IphoneNotListed -> if (status.nonHubDeviceListed)
            R.string.car_sees_usb_device_not_iphone else R.string.car_sees_no_usb_device
        CarPlayStatus.RequestingIphonePermission -> R.string.requesting_iphone_usb_permission
        CarPlayStatus.WaitingForReenumeration -> R.string.status_waiting_reenumeration
        CarPlayStatus.SelectingConfiguration -> R.string.selecting_carplay_configuration
        CarPlayStatus.OpeningDataPaths -> R.string.opening_usb_data_paths
        CarPlayStatus.Pairing -> R.string.pairing_with_iphone
        CarPlayStatus.ConnectingControl -> R.string.connecting_iap2_control
        CarPlayStatus.AttachingNetwork -> if (wireless) R.string.starting_airplay_service else R.string.status_attaching_ncm
        CarPlayStatus.ControlEnded -> R.string.reconnecting_to_your_iphone
        is CarPlayStatus.Failed -> status.knownCause?.let(ConnectionFailureGuidance::stage)
            ?: R.string.connection_interrupted_retrying
    }
}
