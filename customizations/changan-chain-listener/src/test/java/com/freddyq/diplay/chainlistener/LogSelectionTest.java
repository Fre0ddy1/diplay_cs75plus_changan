package com.freddyq.diplay.chainlistener;

import org.junit.Test;
import static org.junit.Assert.*;

public class LogSelectionTest {
    @Test public void preservesBothMediaSourcesAndImageInputs() {
        assertTrue(LogSelection.keep("Double-InteractiveImpl0803: media source :{\"sourceType\":20}"));
        assertTrue(LogSelection.keep("Double-MediaProxy: send media {\"sourceType\":25}"));
        assertTrue(LogSelection.keep("city-registerMusicCall musicData:MusicData(mediaImage=https://x/a.jpg)"));
        assertTrue(LogSelection.keep("getImagesForMusic url:https://x/a.jpg isNetwork:true"));
        assertTrue(LogSelection.keep("BitmapChangeColor_onGenerated"));
    }
    @Test public void preservesThreeStagesOfActualColorOutput() {
        assertTrue(LogSelection.keep("C_VCarSetValue ID=0x314003a6 value=33"));
        assertTrue(LogSelection.keep("CarValue{PropId=0x21403408,Value=33}"));
        assertTrue(LogSelection.keep("W: commonWrite:0c14 21"));
        assertTrue(LogSelection.keep("commonWrite:3405 00 07"));
        assertFalse(LogSelection.keep("总里程 PropId=0x11600204 Value=26965.6"));
        assertFalse(LogSelection.keep("gps location=private"));
    }
    @Test public void masksPhoneAddressesAndUrlQueryButKeepsImagePath() {
        String result=LogSelection.redact("image=https://example.test/a.jpg?token=secret BT=F4:E8:C7:06:47:7C");
        assertFalse(result.contains("secret")); assertFalse(result.contains("F4:E8"));
        assertTrue(result.contains("https://example.test/a.jpg"));
    }
    @Test public void commandFollowsLogsWithoutClearingOrChangingBuffers() {
        var args=LogSelection.command("11-01 17:00:00.000");
        assertEquals("/system/bin/logcat",args.get(0)); assertTrue(args.contains("-T"));
        assertFalse(args.contains("-c")); assertFalse(args.contains("-G"));
        assertEquals(600000,LogSelection.DURATION_MILLIS);
    }
}
