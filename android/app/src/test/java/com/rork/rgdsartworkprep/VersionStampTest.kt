package com.rork.rgdsartworkprep

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the build-file properties that decide whether a new build installs *over*
 * the copy already on the device, or is refused by the installer.
 *
 * Three things have to hold for an update to land without the user losing saved
 * settings, credentials, remembered matches and granted folder permissions:
 *
 *  1. the packaged versionCode is higher than the installed one,
 *  2. the applicationId is byte-for-byte the installed one,
 *  3. the signing identity is unchanged.
 *
 * Only the first two live in this file; the third belongs to the signing key the
 * packaging step supplies. This test covers the two that source can break.
 *
 * It exists because (1) has already failed once in the field, and failed in the
 * worst possible way. The packaging step rewrites the `versionCode = <digits>`
 * literal with a build stamp before it builds the installable APK; that stamp is
 * what keeps version codes climbing across installs. The 1.3.0 attempt replaced the
 * literal with `versionCode = sourceVersionCode`, so the digit-matching rewrite
 * found nothing to change and silently did nothing. The APK shipped carrying 15
 * while the device already held the stamped 1789174724, and Android refuses a lower
 * version code over a higher one — so the install was rejected on the device, after
 * a build that compiled clean and a test suite that passed.
 *
 * That is the failure class worth guarding: it is invisible to the compiler and to
 * every other test, and only shows up on the one device that matters. These
 * assertions run against the build file as text, which is exactly how the rewrite
 * sees it, and turn a device-only rejection into a failing test here.
 */
class VersionStampTest {

    /**
     * Gradle runs unit tests from the module directory, so the plain name resolves.
     * The other candidates keep the test honest if it is ever run from the repo root.
     */
    private val buildFile: File = sequenceOf(
        "build.gradle.kts",
        "app/build.gradle.kts",
        "android/app/build.gradle.kts",
    ).map(::File).firstOrNull { it.isFile }
        ?: error("app/build.gradle.kts not found from ${File("").absolutePath}")

    private val source: String = buildFile.readText()

    /** Exactly the shape the packaging step searches for. */
    private val stampPattern = Regex("""versionCode\s*=\s*(\d+)""")

    /**
     * Values at or above this are a packaging stamp rather than a hand-written build
     * number. Mirrors `stampedVersionFloor` in the build file.
     */
    private val stampedVersionFloor = 1_000_000_000

    /**
     * The rewrite replaces the FIRST match, so a second one is not a harmless
     * duplicate — it decides which line gets the stamp. A helper named
     * `sourceVersionCode = 15` sitting above `defaultConfig` is a match too, because
     * the pattern is unanchored and the name ends in the word it looks for. That
     * helper would absorb the stamp and leave the real declaration untouched.
     */
    @Test
    fun `the build file offers the packaging step exactly one version code to stamp`() {
        val matches = stampPattern.findAll(source).toList()

        assertEquals(
            "Expected exactly one `versionCode = <digits>` for the packaging rewrite " +
                "to find, got ${matches.size}: ${matches.map { it.value }}",
            1,
            matches.size,
        )
    }

    /**
     * The match must be the declaration itself, not the tail of a longer identifier.
     * `sourceVersionCode = 15` contains `versionCode = 15`, so counting matches alone
     * would call that file correct while the stamp landed on a value Gradle only
     * reads as a helper.
     */
    @Test
    fun `the version code is a declaration and not the tail of a longer name`() {
        val match = stampPattern.find(source) ?: error("no versionCode literal at all")
        val charBefore = source.getOrNull(match.range.first - 1)

        assertTrue(
            "`versionCode` is preceded by '$charBefore', so it is part of a longer " +
                "identifier. The rewrite would stamp that instead of the declaration.",
            charBefore == null || !(charBefore.isLetterOrDigit() || charBefore == '_'),
        )
    }

    /**
     * The stamp can only find a plain integer. A reference to a constant, however
     * readable, is what broke 1.3.0 — Gradle resolves it fine and the rewrite cannot
     * see it, which is the combination that reaches a device before it is noticed.
     */
    @Test
    fun `the version code is a plain integer literal`() {
        val declaration = Regex("""\n\s*versionCode\s*=\s*([^\n]+)""").find(source)
            ?: error("no versionCode declaration found")
        val assigned = declaration.groupValues[1].trim()

        assertTrue(
            "versionCode is assigned `$assigned`. It must be a bare integer: the " +
                "packaging rewrite matches digits, and silently changes nothing when " +
                "it finds an expression.",
            assigned.all { it.isDigit() },
        )
    }

    /**
     * Source must describe itself accurately: `declaredBuildNumber` is what a device
     * report names as its origin, so a build whose literal and declared number drift
     * apart produces reports that point at the wrong source.
     *
     * Checked only below the stamp floor. Above it the literal was rewritten by
     * packaging, which is allowed to disagree — that is the whole point of keeping
     * the declared number separately.
     */
    @Test
    fun `the declared build number agrees with the literal it ships as`() {
        val literal = stampPattern.find(source)?.groupValues?.get(1)?.toInt()
            ?: error("no versionCode literal")
        val declared = Regex("""val declaredBuildNumber\s*=\s*(\d+)""")
            .find(source)?.groupValues?.get(1)?.toInt()
            ?: error("no declaredBuildNumber")

        if (literal < stampedVersionFloor) {
            assertEquals(
                "versionCode ($literal) and declaredBuildNumber ($declared) disagree. " +
                    "A report from this build would name a source it did not come from.",
                declared,
                literal,
            )
        }
    }

    /**
     * Android identifies an installed app by this string. Changing it does not
     * upgrade the app — it installs a second copy alongside the first and strands
     * every setting, credential, remembered match and granted folder permission in
     * the original. There is no recovery once a user has updated.
     */
    @Test
    fun `the application id is the one already installed on devices`() {
        val applicationId = Regex("applicationId\\s*=\\s*\"([^\"]+)\"")
            .find(source)?.groupValues?.get(1)

        assertEquals(
            "applicationId changed. An update would install beside the existing app " +
                "instead of over it, stranding the user's settings and folder access.",
            "com.rork.rgdsartworkprep",
            applicationId,
        )
    }
}
