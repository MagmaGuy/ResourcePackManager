package com.magmaguy.resourcepackmanager.autohost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelfHostPublicUrlTest {
    @Test
    void absentOverridePreservesExistingAddressSelection() {
        assertNull(SelfHostPublicUrl.parse(null));
        assertNull(SelfHostPublicUrl.parse(""));
        assertNull(SelfHostPublicUrl.parse(" \t "));
    }

    @Test
    void preservesExternalSchemePortPathAndQuery() {
        var uri = SelfHostPublicUrl.parse(" https://packs.example.com:8443/custom/pack.zip?v=2 ");
        assertEquals("packs.example.com", uri.getHost());
        assertEquals(8443, uri.getPort());
        assertEquals("https://packs.example.com:8443/custom/pack.zip?v=2", uri.toASCIIString());
    }

    @Test
    void supportsDefaultHttpsPortHttpAndIpv6() {
        assertEquals(-1, SelfHostPublicUrl.parse("https://packs.example.com/rspm.zip").getPort());
        assertEquals("http", SelfHostPublicUrl.parse("http://packs.example.com:8080/rspm.zip").getScheme());
        assertEquals(8443, SelfHostPublicUrl.parse("https://[2001:db8::1]:8443/rspm.zip").getPort());
    }

    @Test
    void rejectsInvalidOrAmbiguousPackUrls() {
        for (String invalid : new String[] {
                "packs.example.com/rspm.zip", "/rspm.zip", "file:///rspm.zip", "ftp://packs.example.com/a.zip",
                "https:///rspm.zip", "https://packs.example.com:", "https://packs.example.com:0/rspm.zip",
                "https://packs.example.com:65536/rspm.zip", "https://packs.example.com:-2/rspm.zip",
                "https://user:pass@packs.example.com/rspm.zip", "https://packs.example.com/rspm.zip#fragment",
                "https://packs.example.com/pack with spaces.zip"
        }) {
            assertThrows(IllegalArgumentException.class, () -> SelfHostPublicUrl.parse(invalid), invalid);
        }
    }
}
