package com.cereal.script.aiagent

import java.net.URI

/**
 * The registrable domain (eTLD+1) of [host]: `www.shop.example.co.uk` → `example.co.uk`. IPs and single-label hosts
 * (localhost) are returned as is.
 */
// ponytail: a small embedded public-suffix subset (common two-label ccTLD suffixes and a few hosting platforms), not
// the full PSL; a missing suffix only makes the allowed domain too wide (e.g. `co.xx`), so add suffixes as they show up.
fun registrableDomain(host: String): String {
    val h = host.lowercase().trimEnd('.')
    if (h.startsWith("[") || h.all { it.isDigit() || it == '.' } || '.' !in h) return h
    val labels = h.split('.')
    val take = if (labels.size > 2 && labels.takeLast(2).joinToString(".") in MULTI_LABEL_SUFFIXES) 3 else 2
    return labels.takeLast(take).joinToString(".")
}

/** True when [host] is one of [allowed] or a subdomain of one. */
fun isAllowedHost(host: String?, allowed: Set<String>): Boolean {
    val h = host?.lowercase()?.trimEnd('.') ?: return false
    return allowed.any { h == it || h.endsWith(".$it") }
}

/** A user's "Allowed domains" entry as a bare domain: scheme, port, path and a leading `*.` are dropped. */
fun normalizeDomain(entry: String): String? {
    val e = entry.trim()
    val host = if ("://" in e) runCatching { URI(e).host }.getOrNull() else e.substringBefore('/').substringBefore(':')
    return host?.lowercase()?.removePrefix("*.")?.trim('.')?.ifEmpty { null }
}

fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

private val MULTI_LABEL_SUFFIXES =
    setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk",
        "com.au", "net.au", "org.au", "co.nz", "org.nz", "co.jp", "ne.jp", "or.jp", "co.kr", "co.in", "co.id", "co.il",
        "co.za", "co.th", "com.br", "com.cn", "com.hk", "com.mx", "com.ar", "com.tr", "com.sg", "com.my", "com.tw",
        "com.pl", "com.ua", "com.co", "com.pe", "com.ph", "com.vn", "com.eg", "com.sa",
        "github.io", "gitlab.io", "herokuapp.com", "vercel.app", "netlify.app", "pages.dev", "web.app", "firebaseapp.com",
        "blogspot.com", "myshopify.com", "azurewebsites.net", "cloudfront.net", "appspot.com",
    )
