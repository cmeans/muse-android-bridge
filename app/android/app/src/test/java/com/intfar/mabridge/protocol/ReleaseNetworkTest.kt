package com.intfar.mabridge.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release builds must stay HTTPS only. Plain HTTP to the emulator host is
 * allowed in debug builds alone, by `src/debug`; no source set a release build
 * can use (`src/main`, `src/release`, any other non-debug, non-test set) may
 * allow cleartext. app/build.gradle declares these files as test inputs, so
 * adding one re-runs this test.
 */
class ReleaseNetworkTest {
    // Unit tests run with the module directory as the working directory.
    private fun withoutComments(f: File) =
        f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private val releaseSourceSets: List<File> =
        File("src").listFiles { f ->
            f.isDirectory && f.name != "debug" && !f.name.startsWith("test") && !f.name.startsWith("androidTest")
        }.orEmpty().toList()

    @Test fun findsMainSourceSet() {
        assertTrue("src/main not found from ${File(".").absolutePath}", releaseSourceSets.any { it.name == "main" })
    }

    @Test fun releaseManifestsAllowNoCleartext() {
        for (set in releaseSourceSets) {
            val f = File(set, "AndroidManifest.xml")
            if (!f.exists()) continue
            val manifest = withoutComments(f)
            assertFalse("usesCleartextTraffic in ${f.path}", manifest.contains("usesCleartextTraffic"))
            assertFalse("networkSecurityConfig in ${f.path}", manifest.contains("networkSecurityConfig"))
        }
    }

    @Test fun releaseResourcesPermitNoCleartext() {
        for (set in releaseSourceSets) {
            val files = File(set, "res/xml").listFiles { f -> f.name.endsWith(".xml") }.orEmpty()
            for (f in files) {
                assertFalse(
                    "cleartextTrafficPermitted in ${f.path}",
                    Regex("""cleartextTrafficPermitted\s*=\s*"true"""").containsMatchIn(withoutComments(f)),
                )
            }
        }
    }
}
