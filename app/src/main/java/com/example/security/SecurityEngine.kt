package com.example.security

import android.net.Uri
import android.net.http.SslCertificate
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real, dependency-free content blocker + URL risk assessment.
 *
 * Two rules learned the hard way while auditing this app:
 *  1. Match whole DNS labels, never substrings. The old `host.contains("analytics")`
 *     rule blocked innocent sites such as `myanalyticsblog.com`.
 *  2. Never present invented security facts to the user. The certificate line in the
 *     shield sheet now carries data read back from the live TLS connection.
 */
object SecurityEngine {

    /** Known ad / tracker / cryptominer domains (exact host or any subdomain). */
    private val TRACKER_DOMAINS = hashSetOf(
        "doubleclick.net",
        "google-analytics.com",
        "googlesyndication.com",
        "adservice.google.com",
        "scorecardresearch.com",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "hotjar.com",
        "adroll.com",
        "coinhive.com",
        "popads.net",
        "exoclick.com",
        "propellerads.com",
        "adcash.com",
        "juicyads.com",
        "trafficjunky.net",
        "clickadu.com",
        "ero-advertising.com",
        "adsterra.com",
        "yllix.com",
        "zergnet.com",
        "revcontent.com",
        "mgid.com",
        "quantserve.com",
        "chartbeat.com",
        "matomo.org",
        "mixpanel.com",
        "segment.io",
        "optimizely.com",
        "braze.com",
        "appsflyer.com",
        "amazon-adsystem.com",
        "moatads.com",
        "pubmatic.com",
        "rubiconproject.com",
        "smartadserver.com",
        "teads.tv",
        "bidswitch.net",
        "crwdcntrl.net",
        "demdex.net",
        "everesttech.net"
    )

    /** Whole DNS labels that only ever appear on tracking endpoints. */
    private val TRACKER_LABELS = hashSetOf(
        "analytics",
        "tracker",
        "trackers",
        "telemetry",
        "beacon",
        "adservice",
        "adserver",
        "adsystem",
        "pixel",
        "metrics"
    )

    /** Registrable domains we must never break, whatever their labels look like. */
    private val DOMAIN_ALLOWLIST = hashSetOf(
        "google.com", "github.com", "githubusercontent.com", "wikipedia.org",
        "mozilla.org", "duckduckgo.com", "brave.com", "microsoft.com",
        "apple.com", "cloudflare.com"
    )

    /** Bait words used by phishing / fake-login pages. */
    private val BAIT_KEYWORDS = listOf(
        "free-gift-card", "freegiftcard", "crypto-giveaway", "cryptogiveaway",
        "login-security-verify", "login-verify", "account-update-urgent",
        "account-suspended", "account-verify", "claim-prize-now", "claim-reward",
        "wallet-connect-verify", "seed-phrase", "recover-wallet",
        "secure-login-update", "confirm-your-identity", "verify-now",
        "gift-card-claim", "airdrop-claim"
    )

    /** Brand names phishers like to squeeze into a hostname. */
    private val IMPERSONATED_BRANDS = listOf(
        "google", "gmail", "apple", "icloud", "microsoft", "outlook", "office365",
        "paypal", "netflix", "instagram", "whatsapp", "telegram", "binance",
        "coinbase", "metamask", "trustwallet", "steam", "facebook", "amazon",
        "bank", "sberbank", "tinkoff"
    )

    private fun registrableDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    private fun isAllowlisted(host: String): Boolean {
        val reg = registrableDomain(host)
        return DOMAIN_ALLOWLIST.any { reg == it || host.endsWith(".$it") }
    }

    /**
     * Returns true when a subresource (ad, tracker, beacon) must be blocked.
     */
    fun shouldBlockUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            Uri.parse(url.lowercase(Locale.ROOT))
        } catch (_: Exception) {
            return false
        }
        val host = uri.host ?: return false
        if (host.isBlank()) return false
        if (isAllowlisted(host)) return false

        if (TRACKER_DOMAINS.any { host == it || host.endsWith(".$it") }) return true

        val labels = host.split('.')
        return labels.any { it in TRACKER_LABELS }
    }

    /**
     * Real risk signal for a top-level navigation: bait word in the URL, or a
     * look-alike host that stuffs a well-known brand into its sub/registered name.
     */
    fun isPotentiallyUnsafe(url: String?): Boolean = unsafeReason(url) != null

    fun unsafeReason(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("about:") || lower.startsWith("data:")) return null

        if (BAIT_KEYWORDS.any { lower.contains(it) }) return "bait-keyword"

        val host = try {
            Uri.parse(lower).host ?: return null
        } catch (_: Exception) {
            return null
        }
        if (host.isBlank() || isAllowlisted(host)) return null

        // Punycode host claiming to be a brand is the classic IDN homograph trick.
        val brandHit = IMPERSONATED_BRANDS.firstOrNull { host.contains(it) }
        if (brandHit != null) {
            val legit = "$brandHit.com"
            if (registrableDomain(host) != legit && !host.endsWith(".$legit")) {
                if (host.startsWith("xn--") || host.contains("xn--") ||
                    host.count { it == '-' } >= 2
                ) {
                    return "lookalike-host:$brandHit"
                }
            }
        }
        return null
    }

    /**
     * Empty body used to swallow a blocked request without alarming the page.
     */
    fun createEmptyBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Human-readable description of the *live* TLS certificate of the current page.
     * Returns a localised "unknown" string when the connection carries no certificate.
     */
    fun describeCertificate(cert: SslCertificate?, persian: Boolean = true): String {
        if (cert == null) {
            return if (persian) "گواهی امنیتی در دسترس نیست" else "No certificate information"
        }
        // SslCertificate.getIssuedBy() returns a DName, which parses the distinguished
        // name for us: cName is the CN, dName the full DN. (Neither DName nor the
        // X509Certificate behind it exposes `.name`, and subjectX500Principal is not in
        // the platform stub, so read the parsed fields directly.)
        val issuer = try {
            val dName = cert.issuedBy
            listOfNotNull(dName?.cName, dName?.oName, dName?.dName)
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        } catch (_: Exception) {
            null
        } ?: if (persian) "صادرکننده ناشناس" else "Unknown issuer"

        val until = try {
            cert.validNotAfterDate?.let {
                SimpleDateFormat("yyyy/MM/dd", Locale.US).format(it)
            }
        } catch (_: Exception) {
            null
        }

        return if (until != null) {
            if (persian) "گواهی $issuer • اعتبار تا $until" else "Cert: $issuer • valid until $until"
        } else {
            if (persian) "گواهی $issuer" else "Cert: $issuer"
        }
    }

    /**
     * Rewrites an http:// URL to https:// for the navigations we control.
     * Returns the input unchanged when there is nothing to upgrade.
     */
    fun upgradeToHttps(url: String): String {
        if (url.isBlank()) return url
        val trimmed = url.trim()
        if (trimmed.startsWith("http://")) {
            return "https://" + trimmed.removePrefix("http://")
        }
        return trimmed
    }

    /**
     * True when an address bar value should be treated as a URL rather than a query.
     * Uses the host shape instead of a bare "contains a dot" test, which sent values
     * such as `3.5` or `my file.txt` to the search engine by accident.
     */
    fun looksLikeUrl(input: String): Boolean {
        val value = input.trim()
        if (value.isEmpty() || value.contains(' ')) return false
        if (value.startsWith("http://") || value.startsWith("https://") ||
            value.startsWith("about:") || value.startsWith("file:")
        ) {
            return true
        }
        if (value.contains("://")) return true
        val hostPart = value.substringBefore('/').substringBefore('?').substringBefore('#')
        if (!hostPart.contains('.')) return false
        val tld = hostPart.substringAfterLast('.')
        if (tld.length < 2 || !tld.all { it.isLetter() }) return false
        val label = hostPart.substringBeforeLast('.')
        return label.isNotBlank() && !label.startsWith('.') && !label.endsWith('.')
    }

    /** Formats URL for clean display in address bar */
    fun formatDisplayUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: url
            val scheme = uri.scheme ?: "https"
            if (scheme == "https") host else "$scheme://$host"
        } catch (_: Exception) {
            url
        }
    }
}
