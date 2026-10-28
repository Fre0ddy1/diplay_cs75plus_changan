package com.freddyq.diplay.customization

import android.os.ResultReceiver

/** Starts the saved platform hotspot; a callback result alone does not confirm the radio is ON. */
fun interface PlatformHotspotStarter { fun start(receiver: ResultReceiver) }
