package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Android hides other apps unless the manifest's <queries> names them, so a
 * registry package missing there would always launch as "not-installed".
 */
class ManifestQueriesTest {
    @Test fun manifestQueriesMatchPilotRegistry() {
        // Unit tests run with the module directory as the working directory.
        // Comments removed first, so a commented-out <package> doesn't count.
        val manifest = File("src/main/AndroidManifest.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val queried = Regex("""<package android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(PILOT_REGISTRY.apps.values.map { it.pkg }.toSet(), queried)
    }
}
