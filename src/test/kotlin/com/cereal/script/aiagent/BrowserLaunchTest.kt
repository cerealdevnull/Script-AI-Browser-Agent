package com.cereal.script.aiagent

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class BrowserLaunchTest {
    private val site = FixtureServer()

    @AfterTest
    fun tearDown() = site.close()

    @Test
    fun `a Chrome left over on the profile is killed and the next launch succeeds`() =
        runBlocking {
            val profile = tempProfiles()
            val leftover = Browser.launch(profile) // never closed, like a crashed run
            try {
                val browser = Browser.launch(profile)
                try {
                    browser.navigate(site.url("product.html"))
                    assertTrue("Long Nose Pliers" in browser.snapshot())
                } finally {
                    browser.close()
                }
            } finally {
                runCatching { leftover.close() } // its Chrome is gone, so closing the connection may fail
            }
        }
}
