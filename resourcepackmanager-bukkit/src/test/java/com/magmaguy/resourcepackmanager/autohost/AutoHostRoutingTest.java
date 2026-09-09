package com.magmaguy.resourcepackmanager.autohost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java hosting preferences and the separate network-owned HTTP lifetime. */
class AutoHostRoutingTest {
    private static final boolean[] BOOLS = {false, true};

    @Test
    void forceWinsRegardlessOfPreferenceOrEnablement() {
        for (boolean prefer : BOOLS)
            for (boolean enabled : BOOLS)
                assertEquals(AutoHost.JavaHostingRoute.FORCED_SELF_HOST,
                        AutoHost.resolveJavaHostingRoute(true, prefer, enabled));
    }

    @Test
    void preferenceRequiresSelfHostingToBeEnabled() {
        assertEquals(AutoHost.JavaHostingRoute.SELF_HOST_FIRST,
                AutoHost.resolveJavaHostingRoute(false, true, true));
        assertEquals(AutoHost.JavaHostingRoute.REMOTE,
                AutoHost.resolveJavaHostingRoute(false, false, true));
        assertEquals(AutoHost.JavaHostingRoute.REMOTE,
                AutoHost.resolveJavaHostingRoute(false, true, false));
        assertEquals(AutoHost.JavaHostingRoute.REMOTE,
                AutoHost.resolveJavaHostingRoute(false, false, false));
    }

    @Test
    void teardownClosesTheServerOnlyOutsideNetworkMode() {
        assertTrue(AutoHost.shouldCloseServerOnTeardown(false),
                "Standalone teardown must release the speculative HTTP listener");
        assertFalse(AutoHost.shouldCloseServerOnTeardown(true),
                "The proxy-facing bedrock.zip and mappings.json endpoints must survive a failed Java probe");
    }
}
