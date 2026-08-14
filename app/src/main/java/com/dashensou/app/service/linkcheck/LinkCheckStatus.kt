package com.dashensou.app.service.linkcheck

/**
 * Share-link probe outcome. Aligned with PanHub / pansou states.
 */
enum class LinkCheckStatus {
    /** Not probed yet. */
    UNCHECKED,
    /** Probe in flight (UI may show a soft badge). */
    CHECKING,
    /** Anonymous share API says the link is alive. */
    OK,
    /** Deleted / expired / forbidden. */
    BAD,
    /** Alive but needs an extraction code the caller did not supply. */
    LOCKED,
    /** Magnet / ed2k / unknown host — cannot probe server-side. */
    UNSUPPORTED,
    /** Network/parse failure; short TTL before retry. */
    UNCERTAIN
}
