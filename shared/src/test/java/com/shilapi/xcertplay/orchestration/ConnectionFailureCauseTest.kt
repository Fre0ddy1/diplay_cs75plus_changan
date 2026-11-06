package com.shilapi.xcertplay.orchestration
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
class ConnectionFailureCauseTest {
    @Test fun wrappingPreservesTheTypedCauseAndIOExceptionContract() {
        val error = ConnectionFailureException(ConnectionFailureCause.CAR_HOTSPOT_OFF, "off")
        assertTrue(error is IOException)
        assertEquals(ConnectionFailureCause.CAR_HOTSPOT_OFF, connectionFailureCause(IOException("wrapped", error)))
    }
    @Test fun vendorExceptionCyclesWithoutAClassificationTerminate() {
        val a = IOException("a"); val b = IOException("b"); a.initCause(b); b.initCause(a)
        assertNull(connectionFailureCause(a))
    }
}
