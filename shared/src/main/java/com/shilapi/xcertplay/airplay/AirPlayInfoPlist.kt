package com.shilapi.xcertplay.airplay

/**
 * Builds the /info response the phone reads before it requests media streams.
 *
 * The declaration is complete on purpose: it must carry the display, audio formats/latencies,
 * CarPlay resource modes, and the HID input devices, otherwise the phone aborts the session.
 */
object AirPlayInfoPlist {
    const val MAIN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"
    const val ALT_UUID = "b7e6c5a0-2222-4000-8000-000000000002"
    /** The second alt screen (instruction card); distinct UUID so SETUP/showUI can tell the two apart. */
    const val CARD_UUID = "b7e6c5a0-3333-4000-8000-000000000003"

    private const val STREAM_TYPE_MAIN_SCREEN = 110
    private const val STREAM_TYPE_ALT_SCREEN = 111
    private const val DISPLAY_FEATURE_KNOBS = 0x02
    private const val DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH = 0x08
    private const val CARPLAY_FEATURES = 0x615653aee2L

    private const val RESOURCE_SCREEN = 1
    private const val RESOURCE_AUDIO = 2
    private const val TRANSFER_TAKE = 1
    private const val PRIORITY_NICE_TO_HAVE = 100
    private const val CONSTRAINT_ANYTIME = 100

    /**
     * viewAreaStatusBarEdge values, as CarPlay Simulator's StatusBarEdge (automatic, bottom,
     * driver) and as seen on a tested head unit with iOS 27: 1 puts the dock at the bottom, 2 on
     * the driver's side. A view area without the key leaves the edge to the iPhone.
     */
    const val DOCK_EDGE_BOTTOM = 1
    const val DOCK_EDGE_DRIVER_SIDE = 2
    private const val VIEW_AREA_ANIMATION_MILLIS = 300

    /**
     * updateViewArea for the main screen. Seen on a tested head unit with iOS 27: the iPhone acts
     * on it only with an animation duration and the adjacent areas, the arguments CarPlaySDK's
     * ViewAreaUpdate takes.
     */
    fun viewAreaCommand(index: Int, areaCount: Int): Map<String, Any?> = linkedMapOf(
        "type" to "updateViewArea",
        "params" to linkedMapOf(
            "uuid" to MAIN_UUID,
            "viewAreaIndex" to index,
            "animationDurationMillis" to VIEW_AREA_ANIMATION_MILLIS,
            "adjacentViewAreas" to (0 until areaCount).filter { it != index },
        ),
    )

    /** Discovery and /info must describe the same receiver capabilities. */
    fun features(@Suppress("UNUSED_PARAMETER") config: AirPlayConfig): Long = CARPLAY_FEATURES

    fun build(config: AirPlayConfig): Map<String, Any?> {
        val displays = arrayListOf<Any?>(
            displayEntry(config.main, STREAM_TYPE_MAIN_SCREEN, MAIN_UUID),
        )
        config.cluster?.let { displays.add(displayEntry(it, STREAM_TYPE_ALT_SCREEN, ALT_UUID)) }
        // The instruction-card stream (WWDC19: "the second stream could be a map view and the
        // third stream an instruction card"). Also type 111 - the phone opens one alt-screen
        // stream per display, each against its display's UUID.
        config.clusterCard?.let { displays.add(displayEntry(it, STREAM_TYPE_ALT_SCREEN, CARD_UUID)) }

        val info = linkedMapOf<String, Any?>(
            "sourceVersion" to config.sourceVersion,
            "features" to features(config),
            "statusFlags" to 4L,
            "model" to config.model,
            "manufacturer" to config.manufacturer,
            "deviceID" to config.deviceId,
            "bluetoothIDs" to listOf(config.btMac),
            "name" to config.deviceName,
            "rightHandDrive" to config.rightHandDrive,
            "keepAliveLowPower" to false,
            "keepAliveSendStatsAsBody" to false,
            "modes" to modes(),
        )
        info["audioLatencies"] = audioLatencies(config.navAudioOnly)
        info["audioFormats"] = audioFormats(config.entertainmentSampleRate, config.microphone, config.navAudioOnly)
        info["extendedFeatures"] = listOf("vocoderInfo", "enhancedRequestCarUI")
        info["displays"] = displays
        info["hidDevices"] = listOf(
            AirPlayHid.touchHidDevice(config.main.widthPixels, config.main.heightPixels, MAIN_UUID),
            AirPlayHid.knobHidDevice(MAIN_UUID),
            AirPlayHid.mediaHidDevice(MAIN_UUID),
            AirPlayHid.telephonyHidDevice(MAIN_UUID),
        )
        if (config.icons.isNotEmpty()) {
            info["oemIconVisible"] = true
            info["oemIconLabel"] = config.oemLabel
            info["oemIcons"] = config.icons.map { icon ->
                linkedMapOf(
                    "imageData" to icon.data,
                    "widthPixels" to icon.widthPixels,
                    "heightPixels" to icon.heightPixels,
                    "prerendered" to true,
                )
            }
        }
        if (config.hevc) info["hevcInfo"] = emptyMap<String, Any?>()
        if (config.videoInCar) {
            // The iPhone tears down a session that enables videoPlayback without this key.
            val legacy = features(config)
            info["videoPlaybackInfo"] = VideoInCar.info(legacy, VideoInCar.allowed)
        }
        return info
    }

    private fun resource(resourceId: Int): Map<String, Any?> = linkedMapOf(
        "resourceID" to resourceId,
        "transferType" to TRANSFER_TAKE,
        "transferPriority" to PRIORITY_NICE_TO_HAVE,
        "takeConstraint" to CONSTRAINT_ANYTIME,
        "borrowConstraint" to CONSTRAINT_ANYTIME,
        "unborrowConstraint" to CONSTRAINT_ANYTIME,
    )

    private fun modes(): Map<String, Any?> = linkedMapOf(
        "resources" to listOf(resource(RESOURCE_SCREEN), resource(RESOURCE_AUDIO)),
        // Declared again after the A/B test of 2026-10-19: removing appStates did not shorten the
        // two-second blank before an app appears (the phone simply sends no frames while it
        // launches the app), so this stays as upstream declares it.
        "appStates" to listOf(
            linkedMapOf("appStateID" to 2, "state" to false),
            linkedMapOf("appStateID" to 1, "speechMode" to -1),
            linkedMapOf("appStateID" to 3, "state" to false),
        ),
    )

    private fun audioLatencies(navAudioOnly: Boolean): List<Map<String, Any?>> {
        fun base(type: Int, audioType: String? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "inputLatencyMicros" to 0L,
                "outputLatencyMicros" to 0L,
            )
            if (audioType != null) entry["audioType"] = audioType
            return entry
        }
        val entries = arrayListOf(
            base(100), base(100, "default"), base(100, "media"), base(100, "telephony"),
            base(100, "speechRecognition"), base(100, "alert"), base(101), base(101, "default"),
            base(102, "default"),
        )
        // navAudioOnly: the two media latencies go with the media formats below, so the plist
        // never describes a path it does not also declare a format for.
        if (navAudioOnly) entries.removeAll { it["type"] == 102 || it["audioType"] == "media" }
        return entries
    }

    private fun audioFormats(
        entertainmentRate: Int,
        microphone: Boolean,
        navAudioOnly: Boolean,
    ): List<Map<String, Any?>> {
        fun format(type: Int, audioType: String, outputFormats: Int, inputFormats: Int? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "audioType" to audioType,
                "audioOutputFormats" to outputFormats,
            )
            if (inputFormats != null) entry["audioInputFormats"] = inputFormats
            return entry
        }

        val is48 = entertainmentRate == 48000
        val pcmVoice = 0x3fc
        val pcm = pcmVoice or (if (is48) 0xc000 else 0xc00)
        val pcmMono = 0x154 or (if (is48) 0x4000 else 0x400)
        val opus = 0x70000000
        val aacLc = if (is48) 0x800000 else 0x400000
        val pcmInput = if (microphone) pcmMono else null
        val wirelessInput = if (microphone) pcmMono or opus else null

        val formats = arrayListOf(
            format(100, "compatibility", pcm, pcmInput),
            format(101, "compatibility", pcm),
            format(100, "default", pcm or opus, wirelessInput),
            format(100, "alert", pcm or opus),
            format(100, "media", pcm),
            format(100, "telephony", pcmMono or opus, wirelessInput),
            format(100, "speechRecognition", pcmMono or opus, wirelessInput),
            format(101, "default", pcm or opus),
            format(102, "media", aacLc),
        )
        // Experimental "guidance audio only": with no media format advertised the phone has no
        // CarPlay stream to put music on and keeps it on the car's Bluetooth player, while the
        // prompt keeps the 101/default entry above - the one the wheel volume keys point at.
        // Everything else (alert, telephony, speechRecognition) stays, so calls and Siri do not
        // change route.
        if (navAudioOnly) formats.removeAll { it["audioType"] == "media" }
        return formats
    }

    private fun displayEntry(display: AirPlayDisplayConfig, type: Int, uuid: String): Map<String, Any?> {
        val widthPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.widthPhysicalMm ?: AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
        )
        val heightPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.heightPhysicalMm
                ?: Math.round(
                    widthPhysical * display.heightPixels.toDouble() / display.widthPixels,
                ).toInt(),
        )
        val fps = AirPlayDisplaySettings.sanitizeFps(display.fps)

        val entry = linkedMapOf<String, Any?>(
            "uuid" to uuid,
            "type" to type,
            "maxFPS" to fps,
            "widthPixels" to display.widthPixels,
            "heightPixels" to display.heightPixels,
            "widthPhysical" to widthPhysical,
            "heightPhysical" to heightPhysical,
            "features" to (display.features ?: (DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH or DISPLAY_FEATURE_KNOBS)),
            "primaryInputDevice" to display.primaryInputDevice,
            // Upstream 0.2.22 declares these four on every display (0 = the iPhone decides).
            // Without them the phone may not apply the car's day/night state to this screen.
            "uiAppearanceMode" to 0,
            "uiAppearanceSetting" to 0,
            "mapAppearanceMode" to 0,
            "mapAppearanceSetting" to 0,
        )

        entry["viewAreas"] = display.viewAreas?.takeIf { it.isNotEmpty() }
            ?.map { areaDict(display, it) }
            ?: listOf(areaDict(display))
        entry["initialViewArea"] = if (display.viewAreas.isNullOrEmpty()) 0
            else display.initialViewArea.coerceIn(0, display.viewAreas!!.lastIndex)
        // Several areas let the car move CarPlay between them (another dock edge) with
        // updateViewArea, without reconnecting.
        if (!display.viewAreas.isNullOrEmpty() && display.viewAreas!!.size > 1) {
            entry["viewAreaTransitionControl"] = true
        }
        if (display.initialUrl != null) entry["initialURL"] = display.initialUrl
        return entry
    }

    private fun areaDict(display: AirPlayDisplayConfig, area: AirPlayViewArea? = null): Map<String, Any?> {
        // The session SETUP response enables "viewAreas", so /info must always describe one.
        // A display without custom insets uses the full panel for both the view and safe areas; an
        // explicit area replaces the display's view insets and clips its safe area.
        val view = area?.let {
            AirPlayInsets(top = it.originY, bottom = display.heightPixels - it.originY - it.height,
                left = it.originX, right = display.widthPixels - it.originX - it.width)
        } ?: display.viewArea ?: AirPlayInsets()
        val width = display.widthPixels
        val height = display.heightPixels
        val result = linkedMapOf<String, Any?>(
            "widthPixels" to (width - view.left - view.right),
            "heightPixels" to (height - view.top - view.bottom),
            "originXPixels" to view.left,
            "originYPixels" to view.top,
        )
        area?.dockEdge?.let { result["viewAreaStatusBarEdge"] = it }
        val displaySafe = display.safeArea ?: AirPlayInsets()
        val safe = if (area == null) displaySafe else AirPlayInsets(
            top = maxOf(displaySafe.top, view.top),
            bottom = maxOf(displaySafe.bottom, view.bottom),
            left = maxOf(displaySafe.left, view.left),
            right = maxOf(displaySafe.right, view.right),
        )
        val safeArea = linkedMapOf<String, Any?>(
            "widthPixels" to (width - safe.left - safe.right),
            "heightPixels" to (height - safe.top - safe.bottom),
            "originXPixels" to safe.left,
            "originYPixels" to safe.top,
            "drawUIOutsideSafeArea" to (display.safeAreaDrawOutside ?: true),
        )
        result["safeArea"] = safeArea
        return result
    }
}