package com.magmaguy.resourcepackmanager.autohost;

import java.net.URI;
import java.net.URISyntaxException;

/** Optional complete client-facing pack URL, independent of the local HTTP listener. */
final class SelfHostPublicUrl {
    private SelfHostPublicUrl() { }

    static URI parse(String configured) {
        if (configured == null || configured.isBlank()) return null;
        URI uri;
        try {
            uri = new URI(configured.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("selfHostExternalUrl must be a valid absolute HTTP or HTTPS URL.");
        }
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("selfHostExternalUrl must include http:// or https:// and a hostname.");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("selfHostExternalUrl must not contain embedded credentials or a fragment.");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) {
            throw new IllegalArgumentException("selfHostExternalUrl must use a port between 1 and 65535, or omit it.");
        }
        return uri;
    }
}
