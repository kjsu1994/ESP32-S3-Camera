package io.github.kjsu1994.workbenchcam

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTerminationTest {
    @Test fun honorsBoardStateAndSeparatesUnexpectedTransportLoss() {
        assertEquals("limit", streamEndReason("limit", true, false))
        assertEquals("stopped", streamEndReason("stopped", true, false))
        assertEquals("limit", streamEndReason(null, true, true))
        assertEquals("stream_ended", streamEndReason(null, true, false))
        assertEquals("transport", streamEndReason("none", false, false))
    }
}
