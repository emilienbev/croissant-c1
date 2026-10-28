package com.bleural.croissant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of an update (`update/Releases.kt`): sources, versions, GitHub's answer, the battery. */
class UpdateRulesTest {
    @Test fun aSourceIsOwnerSlashRepoOrAGithubLink() {
        val want = Source("someone", "croissant-fork")
        for (s in listOf(
            "someone/croissant-fork",
            " someone/croissant-fork ",
            "https://github.com/someone/croissant-fork",
            "github.com/someone/croissant-fork.git",
            "https://www.github.com/someone/croissant-fork/",
            "https://github.com/someone/croissant-fork/releases/tag/v0.1.0",
        )) assertEquals(s, want, Source.parse(s))
    }

    @Test fun anythingElseIsNoSource() {
        for (s in listOf("", "croissant", "a/b/c", "-a/b", "a-/b", "a--b/c", "a/..", "a/b c", "https://gitlab.com/a/b", "a/b.git.git/x")) {
            assertNull(s, Source.parse(s))
        }
    }

    @Test fun sourcesAreTheSameWhateverTheCase() {
        assertTrue(Source("Someone", "Croissant-Fork").same(Source("someone", "croissant-fork")))
    }

    @Test fun versionsReadAsTagsAndCompare() {
        assertEquals(Version(0, 1, 1), Version.parse("v0.1.1"))
        assertEquals(Version(0, 1, 1), Version.parse("0.1.1"))
        assertNull(Version.parse("0.1"))
        assertNull(Version.parse("v1.2.3-rc1"))
        assertTrue(Version(0, 2, 0) > Version(0, 1, 9))
        assertTrue(Version(1, 0, 0) > Version(0, 99, 99))
        // Gradle's versionCode (android/app/build.gradle.kts).
        assertEquals(101, Version(0, 1, 1).code)
    }

    private val digest = "0123456789abcdef".repeat(4)

    private val release = """
        {"tag_name": "v0.1.1", "published_at": "2026-10-12T10:00:00Z",
         "body": "- beam's own keyboard\n- the wheel moves the caret\n\n## more\n* pull the keys down to close\n- a fourth line",
         "assets": [
           {"name": "croissant-0.1.1.apk", "size": 84856186, "digest": "sha256:${digest.uppercase()}",
            "browser_download_url": "https://github.com/o/r/releases/download/v0.1.1/croissant-0.1.1.apk"},
           {"name": "croissant-0.1.1.apk.sha256", "size": 86, "digest": null,
            "browser_download_url": "https://github.com/o/r/releases/download/v0.1.1/croissant-0.1.1.apk.sha256"},
           {"name": "ortload-arm64", "size": 10, "browser_download_url": "https://example/ortload"}
         ]}
    """.trimIndent()

    @Test fun githubsAnswerBecomesARelease() {
        val r = parseRelease(release)!!
        assertEquals(Version(0, 1, 1), r.version)
        assertEquals("croissant-0.1.1.apk", r.apk.name)
        assertEquals(84856186L, r.apk.size)
        assertEquals(digest, r.apk.sha256)
        assertEquals("https://github.com/o/r/releases/download/v0.1.1/croissant-0.1.1.apk.sha256", r.shaUrl)
        assertEquals(listOf("beam's own keyboard", "the wheel moves the caret", "pull the keys down to close"), r.notes)
    }

    @Test fun aReleaseWithoutTheApkOrAVersionTagIsNotOne() {
        assertNull(parseRelease(release.replace("croissant-0.1.1.apk\"", "other.apk\"")))
        assertNull(parseRelease(release.replace("\"v0.1.1\"", "\"latest\"")))
        assertNull(parseRelease("not json"))
    }

    @Test fun aReleaseKeepsAcrossARestart() {
        val r = parseRelease(release)!!
        assertEquals(r, Release.fromJson(r.toJson()))
        val noDigest = r.copy(apk = r.apk.copy(sha256 = null), shaUrl = null)
        assertEquals(noDigest, Release.fromJson(noDigest.toJson()))
    }

    @Test fun aSha256FileGivesItsDigest() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, shaOf("$hex  croissant-0.1.1.apk\n"))
        assertEquals(hex, shaOf(hex.uppercase()))
        assertNull(shaOf("nope"))
    }

    @Test fun installingNeedsHalfTheBatteryOrAQuarterCharging() {
        assertTrue(installAllowed(50, false))
        assertFalse(installAllowed(49, false))
        assertTrue(installAllowed(25, true))
        assertFalse(installAllowed(24, true))
        assertFalse(installAllowed(-1, false))
    }

    @Test fun timeLeftIsTheStepsRemainderAndEveryStepAfter() {
        val t = InstallTimes(checkingMs = 2_000, installingMs = 9_000, compilingMs = 35_000, restartingMs = 3_000)
        assertEquals(49_000L, t.totalMs)
        assertEquals(49_000L, t.leftMs(InstallStep.Checking, 0))
        assertEquals(30_000L, t.leftMs(InstallStep.Compiling, 8_000))
        // An overrun step still has a little left, not none.
        assertEquals(6_000L, t.leftMs(InstallStep.Compiling, 60_000))
        assertEquals(3_000L, t.leftMs(InstallStep.Restarting, 2_000))
    }

    @Test fun aTimedMeterIsSteadyThenNeverFull() {
        assertEquals(0f, timedLevel(0, 35_000), 0.001f)
        assertEquals(0.45f, timedLevel(17_500, 35_000), 0.001f)
        assertEquals(0.9f, timedLevel(35_000, 35_000), 0.001f)
        val late = (1..10).map { timedLevel(35_000L + it * 10_000L, 35_000) }
        assertEquals(late.sorted(), late)
        assertTrue(late.last() < 0.98f)
    }

    @Test fun aPaceNeedsTwoSecondsThenGivesTheTimeLeft() {
        val p = Pace()
        p.add(0, 0)
        p.add(1_000, 1_000_000)
        assertNull(p.leftMs(10_000_000))
        p.add(2_000, 2_000_000)
        // 1 MB a second, 8 MB to go.
        assertEquals(8_000L, p.leftMs(10_000_000))
        p.reset()
        assertNull(p.leftMs(10_000_000))
    }

    @Test fun aPaceForgetsWhatIsOlderThanItsWindow() {
        val p = Pace(windowMs = 8_000)
        p.add(0, 0)
        p.add(10_000, 1_000_000)  // slow at first
        p.add(20_000, 21_000_000) // then 2 MB a second
        assertEquals(2_000L, p.leftMs(25_000_000))
    }

    @Test fun retriesWaitLongerEachTimeUpToAQuarterHour() {
        val waits = (1..8).map(::retryAfterMs)
        assertEquals(waits.sorted(), waits)
        assertEquals(900_000L, waits.last())
    }
}
