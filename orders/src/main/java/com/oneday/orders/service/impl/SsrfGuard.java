package com.oneday.orders.service.impl;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF protection for user-supplied outbound URLs (B2B webhook delivery). A merchant registers a
 * webhook URL that the platform then fetches server-side; without validation that URL can point at
 * internal infrastructure — loopback, RFC1918/site-local, link-local (incl. the cloud metadata
 * endpoint 169.254.169.254), or IPv6 unique-local — turning the app into an SSRF proxy.
 *
 * <p>{@link #reasonIfUnsafe(String)} returns null when the URL is safe to fetch, or a short human
 * reason otherwise. Callers reject at registration time and skip (mark FAILED) at delivery time.
 * Note: this resolves DNS at check time; pinning the resolved IP through to the request to fully
 * defeat DNS-rebinding is a further hardening tracked separately.
 */
final class SsrfGuard {

    private SsrfGuard() {}

    static String reasonIfUnsafe(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "URL is blank";
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            return "URL is malformed";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "URL scheme must be http or https";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "URL has no host";

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "host does not resolve";
        }
        for (InetAddress ip : addresses) {
            if (ip.isLoopbackAddress() || ip.isAnyLocalAddress() || ip.isLinkLocalAddress()
                    || ip.isSiteLocalAddress() || ip.isMulticastAddress()) {
                return "URL resolves to a non-public (internal) address";
            }
            byte[] b = ip.getAddress();
            // IPv6 unique-local fc00::/7 (site-local check above does not cover it).
            if (b.length == 16 && (b[0] & 0xFE) == 0xFC) {
                return "URL resolves to a non-public (internal) address";
            }
            // IPv4 100.64.0.0/10 carrier-grade NAT — not internet-routable.
            if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40) {
                return "URL resolves to a non-public (internal) address";
            }
        }
        return null;
    }

    static boolean isSafe(String rawUrl) {
        return reasonIfUnsafe(rawUrl) == null;
    }
}
