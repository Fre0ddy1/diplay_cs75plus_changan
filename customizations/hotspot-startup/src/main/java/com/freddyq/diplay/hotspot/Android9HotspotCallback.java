package com.freddyq.diplay.hotspot;

import android.os.ResultReceiver;

/** Loaded only after the system's matching callback API is found. No radio settings are written here. */
public final class Android9HotspotCallback extends android.net.ConnectivityManager$OnStartTetheringCallback {
    private final ResultReceiver receiver;
    public Android9HotspotCallback(ResultReceiver receiver) { this.receiver = receiver; }
    @Override public void onTetheringStarted() { receiver.send(0, null); }
    @Override public void onTetheringFailed() { receiver.send(1, null); }
}
