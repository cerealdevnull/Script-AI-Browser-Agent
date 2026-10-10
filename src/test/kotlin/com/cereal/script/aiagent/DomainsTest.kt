package com.cereal.script.aiagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** eTLD+1 parsing: pure, because the main seam would need real DNS names to reach it. */
class DomainsTest {
    @Test
    fun `registrable domain is the eTLD+1`() {
        assertEquals("example.com", registrableDomain("example.com"))
        assertEquals("example.com", registrableDomain("www.shop.Example.COM."))
        assertEquals("example.co.uk", registrableDomain("www.example.co.uk"))
        assertEquals("example.com.au", registrableDomain("a.b.example.com.au"))
        assertEquals("alice.github.io", registrableDomain("alice.github.io"))
        assertEquals("localhost", registrableDomain("localhost"))
        assertEquals("127.0.0.1", registrableDomain("127.0.0.1"))
        assertEquals("[::1]", registrableDomain("[::1]"))
    }

    @Test
    fun `a host is allowed on an allowed domain or any subdomain of it`() {
        val allowed = setOf("example.com", "pay.example.net")
        assertTrue(isAllowedHost("example.com", allowed))
        assertTrue(isAllowedHost("www.EXAMPLE.com", allowed))
        assertTrue(isAllowedHost("checkout.pay.example.net", allowed))
        assertFalse(isAllowedHost("example.net", allowed))
        assertFalse(isAllowedHost("badexample.com", allowed))
        assertFalse(isAllowedHost("example.com.evil.io", allowed))
        assertFalse(isAllowedHost(null, allowed))
    }

    @Test
    fun `user entries are normalised to a domain`() {
        assertEquals("pay.example.net", normalizeDomain(" https://Pay.Example.net:8443/checkout "))
        assertEquals("example.com", normalizeDomain("*.example.com"))
        assertEquals("example.com", normalizeDomain("https://*.example.com/"))
        assertEquals("example.com", normalizeDomain(".example.com."))
        assertEquals(null, normalizeDomain("  "))
    }
}
