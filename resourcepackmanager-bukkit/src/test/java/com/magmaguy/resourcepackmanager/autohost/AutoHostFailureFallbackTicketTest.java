package com.magmaguy.resourcepackmanager.autohost;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Covers the deterministic decision made when remote autoHost probing fails. */
final class AutoHostFailureFallbackTicketTest {
    @Test void failedPreferredSelfHostFallsBackToRemoteWhenDisabled() {
        assertEquals(AutoHost.JavaHostingRoute.REMOTE,
                AutoHost.resolveJavaHostingRoute(false, true, false));
    }
    @Test void forcedSelfHostRemainsAvailableDuringRemoteFailure() {
        assertEquals(AutoHost.JavaHostingRoute.FORCED_SELF_HOST,
                AutoHost.resolveJavaHostingRoute(true, false, false));
    }
}
