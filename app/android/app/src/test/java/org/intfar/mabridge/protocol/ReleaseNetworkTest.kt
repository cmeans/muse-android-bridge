package org.intfar.mabridge.protocol

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Release builds must stay HTTPS only. Plain HTTP to the emulator host is
 * allowed in debug builds alone, by `src/debug`; nothing in `src/main` (which
 * release builds use) may allow cleartext.
 */
class ReleaseNetworkTest {
    // Unit tests run with the module directory as the working directory.
    private fun withoutComments(f: File) =
        f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test fun mainManifestAllowsNoCleartext() {
        val manifest = withoutComments(File("src/main/AndroidManifest.xml"))
        assertFalse("usesCleartextTraffic in src/main", manifest.contains("usesCleartextTraffic"))
        assertFalse("networkSecurityConfig in src/main", manifest.contains("networkSecurityConfig"))
    }

    @Test fun mainResourcesPermitNoCleartext() {
        val xmlDir = File("src/main/res/xml")
        val files = xmlDir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty()
        for (f in files) {
            assertFalse(
                "cleartextTrafficPermitted in ${f.path}",
                Regex("""cleartextTrafficPermitted\s*=\s*"true"""").containsMatchIn(withoutComments(f)),
            )
        }
    }
}
