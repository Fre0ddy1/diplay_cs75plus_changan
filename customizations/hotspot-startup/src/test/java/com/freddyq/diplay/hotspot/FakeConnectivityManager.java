package com.freddyq.diplay.hotspot;

import android.os.Handler;

public class FakeConnectivityManager {
    public int calls;
    public int type = -1;
    public boolean provisioning;
    public Handler handler;
    public boolean reject;
    public android.net.ConnectivityManager$OnStartTetheringCallback callback;
    public void startTethering(int type, boolean showUi,
            android.net.ConnectivityManager$OnStartTetheringCallback callback, Handler handler) {
        if (reject) throw new SecurityException("android.permission.TETHER_PRIVILEGED");
        calls++;
        this.type = type;
        this.provisioning = showUi;
        this.callback = callback;
        this.handler = handler;
    }
}
