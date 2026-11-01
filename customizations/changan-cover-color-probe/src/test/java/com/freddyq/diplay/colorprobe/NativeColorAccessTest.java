package com.freddyq.diplay.colorprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeColorAccessTest {
    public static class Manager {
        int calls, property, area; Object value = 33; boolean denied;
        public boolean isConnected() { return true; }
        public Object getValue(int property, int area) { return value; }
        public int setValue(int property, int area, Object value) {
            calls++; this.property=property; this.area=area;
            if (denied) throw new SecurityException("test caller not permitted");
            this.value=value; return 7; // Deliberately not interpreted as physical success.
        }
    }
    private final NativeColorAccess.Listener listener = new NativeColorAccess.Listener() {
        public void log(String message) {} public void ready(boolean ready) {}
    };
    @Test public void unreadOriginalAndArbitraryColorsAreRejected() {
        try { ColorWritePolicy.validate(4,null); fail(); } catch (IllegalStateException expected) {}
        try { ColorWritePolicy.validate(12,33); fail(); } catch (IllegalArgumentException expected) {}
        assertEquals(4,ColorWritePolicy.validate(4,33)); assertEquals(48,ColorWritePolicy.validate(48,33));
        assertEquals(33,ColorWritePolicy.validate(33,33));
    }
    @Test public void onlyTheVerifiedColorPropertyAndAreaAreWritten() throws Exception {
        Manager manager = new Manager(); NativeColorAccess client = new NativeColorAccess(listener,manager,33);
        client.write(4);
        assertEquals(1,manager.calls); assertEquals(0x314003a6,manager.property); assertEquals(0,manager.area);
        assertEquals(4,client.read()); client.write(client.original()); assertEquals(33,client.read());
    }
    @Test public void closingNeverRestoresOrWritesAutomatically() throws Exception {
        Manager manager = new Manager(); NativeColorAccess client = new NativeColorAccess(listener,manager,33);
        client.close(); assertEquals(0,manager.calls);
        try { client.write(48); fail(); } catch (IllegalStateException expected) {}
        assertEquals(0,manager.calls);
    }
    @Test public void permissionFailureIsReportedAndNeverRetried() throws Exception {
        Manager manager = new Manager(); manager.denied=true;
        NativeColorAccess client = new NativeColorAccess(listener,manager,33);
        try { client.write(4); fail(); } catch (Exception expected) {
            assertTrue(NativeColorAccess.describe(expected).contains("SecurityException"));
        }
        assertEquals(1,manager.calls);
    }
}
