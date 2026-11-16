package com.intfar.mabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerScreenTest {
    private val none = StopState(all = false, family = false)

    @Test fun hostDropsSchemeAndSlash() {
        assertEquals("ntfy.example.com", hostOf("https://ntfy.example.com/"))
        assertEquals("ntfy.example.com:8443", hostOf("https://ntfy.example.com:8443"))
        assertEquals("", hostOf(""))
    }

    @Test fun headlineFollowsSetupRunningAndStop() {
        val now = 10 * 60_000L
        assertEquals(StatusKind.OFF, ownerHeadline(false, true, none, "h", 0, now).kind)
        assertEquals("Not set up", ownerHeadline(false, true, none, "h", 0, now).title)
        assertEquals("Bridge is off", ownerHeadline(true, false, none, "h", 0, now).title)
        val ok = ownerHeadline(true, true, none, "ntfy.example.com", now - 2 * 60_000, now)
        assertEquals(StatusKind.OK, ok.kind)
        assertEquals("Listening", ok.title)
        assertEquals("Server: ntfy.example.com · last connected 2 min ago", ok.detail)
        val stopped = ownerHeadline(true, true, StopState(all = true, family = false), "ntfy.example.com", 0, now)
        assertEquals(StatusKind.STOPPED, stopped.kind)
        assertEquals("Everything stopped", stopped.title)
        assertTrue(stopped.detail.contains("Server: ntfy.example.com, not connected yet."))
        // Stop family alone still listens: the STOP section shows it.
        assertEquals(StatusKind.OK, ownerHeadline(true, true, StopState(all = false, family = true), "h", 0, now).kind)
    }

    @Test fun agoInWords() {
        assertEquals("just now", ago(59_000))
        assertEquals("just now", ago(-5_000))
        assertEquals("1 min ago", ago(60_000))
        assertEquals("59 min ago", ago(59 * 60_000L))
        assertEquals("1 h ago", ago(60 * 60_000L))
        assertEquals("47 h ago", ago(47 * 60 * 60_000L))
        assertEquals("2 days ago", ago(48 * 60 * 60_000L))
    }

    private fun item(text: String) = recentItemFor("2026-11-13 09:41:07 | $text")

    @Test fun launchesAndRingsNameTheAppAndWhoAsked() {
        assertEquals(RecentItem("09:41", "Opened WhatsApp · asked by family"), item("ok (family): launch whatsapp"))
        assertEquals(RecentItem("09:41", "Opened Meet · asked by your Muse"), item("ok (owner): launch meet"))
        assertEquals("Rang the phone · asked by family", item("ok (family): ring find")?.text)
        assertEquals("Shared status · asked by your Muse", item("ok (owner): status report")?.text)
    }

    @Test fun refusalsAreMarked() {
        val r = item("disabled (family): ring find")!!
        assertEquals("Refused, stopped: ring · family", r.text)
        assertTrue(r.refused)
        assertEquals("Refused, too many: Meet · your Muse", item("rate-limited (owner): launch meet (no receipt: one already sent this window)")?.text)
        assertEquals("Tried to open Meet; it didn't come to the front · family", item("blocked (family): launch meet: didn't come to the front within 5 s")?.text)
        assertEquals("Couldn't open Chrome: Show over other apps is off · family", item("cannot-launch (family): launch chrome: 'Display over other apps' is off")?.text)
        assertEquals("Refused an unknown command · family", item("unknown-command (family): rm -rf")?.text)
        assertEquals("Ignored an old command: ring · your Muse", item("dropped-stale (owner, 130s old): ring find")?.text)
        assertEquals("Failed: ring · family", item("failed (family): ring find: no sound played")?.text)
        assertFalse(item("ok (owner): launch meet")!!.refused)
    }

    @Test fun aStaleMessageNeverShowsItsSendersText() {
        val line = "dropped-stale (family, 130s old): " + logExcerpt("Call 555-0100 now · asked by your Muse")
        assertEquals("Ignored an old message · family", item(line)?.text)
        assertEquals("Ignored an old command: ring · family", item("dropped-stale (family, 130s old): ring find")?.text)
    }

    @Test fun anUnverifiedLaunchSaysSo() {
        assertEquals("Opened Meet (not checked) · asked by your Muse", item("ok (owner): launch meet (unverified: no usage access)")?.text)
    }

    @Test fun everyNotificationStopTapHasAPlainLine() {
        // plainEvent matches stopTapFor's log lines: a change there must show up here.
        for (b in StopButton.entries) {
            val tap = stopTapFor(b.action, StopState(all = false, family = false))!!
            assertTrue(tap.logLine, item(tap.logLine)?.text?.startsWith("You turned on") == true)
        }
    }

    @Test fun everyOutcomeHasItsOwnWords() {
        // The exact lines BridgeService writes.
        assertEquals("Not installed: Meet · family", item("not-installed (family): launch meet")?.text)
        assertEquals("Not done, notifications are off: ring · your Muse", item("cannot-announce (owner): ring find: notifications are off")?.text)
        assertEquals("Not available yet: bootstrap rustdesk · your Muse", item("not-implemented (owner): bootstrap rustdesk")?.text)
        assertEquals("Bridge stopped", item("service stopped")?.text)
        assertEquals("Ringing ended", item("ring ended: Stop ringing tapped")?.text)
    }

    @Test fun anotherDayShowsItsDate() {
        val line = "2026-11-12 22:19:05 | ok (family): ring find"
        assertEquals("22:19", recentItemFor(line, today = "2026-11-12")?.time)
        assertEquals("Nov 12, 22:19", recentItemFor(line, today = "2026-11-13")?.time)
        assertEquals("22:19", recentItemFor(line)?.time)
    }

    @Test fun plainEventsAndChatter() {
        assertEquals("Bridge started", item("service started")?.text)
        assertEquals("Connected", item("subscribed")?.text)
        assertEquals("You turned on Stop everything (notification)", item("STOP all switched on from the notification")?.text)
        assertEquals("Setup changed: server a.example -> b.example", item("setup changed: server a.example -> b.example")?.text)
        assertNull(item("subscribe error: SocketTimeoutException"))
        assertNull(item("duplicate (owner) abc: launch meet"))
        assertNull(item("receipt failed: HTTP 500: ok launch meet"))
        assertNull(recentItemFor("not a log line"))
        assertNull(recentItemFor(""))
    }

    @Test fun anUnknownAppKeepsItsSlug() {
        assertEquals("Opened zoom · asked by family", item("ok (family): launch zoom")?.text)
    }

    private val alex = listOf(FamilyMember("m1", "Alex"))
    private fun memberItem(text: String, members: List<FamilyMember> = alex) =
        recentItemFor("2026-11-13 09:41:07 | $text", members = members)

    @Test fun askedByNamesMember() {
        assertEquals("your Muse", askedBy(Source(Channel.OWNER, null)))
        assertEquals("Alex", askedBy(Source(Channel.FAMILY, FamilyMember("m1", "Alex"))))
        assertEquals("your Muse", askedByTag("owner", alex))
        assertEquals("family", askedByTag("family", alex))
        assertEquals("Alex", askedByTag("family/m1", alex))
        assertEquals("a family member", askedByTag("family/m2", alex))
    }

    @Test fun oldFamilyLinesStillRead() {
        assertEquals("Opened Meet · asked by family", memberItem("ok (family): launch meet")?.text)
    }

    @Test fun memberLineNamed() {
        assertEquals("Opened Meet · asked by Alex", memberItem("ok (family/m1): launch meet")?.text)
    }

    @Test fun removedMemberLine() {
        assertEquals("Opened Meet · asked by a family member", memberItem("ok (family/m1): launch meet", emptyList())?.text)
    }

    @Test fun rateLimitedMemberLine() {
        assertEquals("Refused, too many: Meet · Alex", memberItem("rate-limited (family/m1): launch meet")?.text)
    }

    @Test fun confirmAskedResult() {
        val r = memberItem("confirm-asked (family/m1): launch rustdesk")!!
        assertEquals("Asked to open RustDesk, waiting for you · Alex", r.text)
        assertFalse(r.refused)
    }

    @Test fun declinedResult() {
        val r = memberItem("declined (family/m1): launch rustdesk")!!
        assertEquals("You said not now: RustDesk · Alex", r.text)
        assertTrue(r.refused)
    }

    @Test fun notConfirmedResult() {
        val r = memberItem("not-confirmed (family/m1): launch rustdesk (timeout)")!!
        assertEquals("Not opened, no answer: RustDesk · Alex", r.text)
        assertTrue(r.refused)
    }

    @Test fun pendingResult() {
        val r = memberItem("pending (family/m1): launch rustdesk")!!
        assertEquals("Refused, already waiting: RustDesk · Alex", r.text)
        assertTrue(r.refused)
    }

    @Test fun rustdeskAndPromptWording() {
        assertEquals("RustDesk: Alex is asking to open it", rustdeskRequestAnnouncement("Alex"))
        assertEquals("Alex asked to open RustDesk", confirmPromptTitle("Alex"))
        assertEquals("Only open it if you asked Alex for help.", confirmPromptText("Alex"))
    }

    @Test fun staleMemberLineStillChecksCommand() {
        assertEquals("Ignored an old command: Meet · Alex", memberItem("dropped-stale (family/m1, 130s old): launch meet")?.text)
        assertEquals("Ignored an old message · Alex", memberItem("dropped-stale (family/m1, 130s old): hello there")?.text)
    }

    private val old = Setup("https://ntfy.example.com", "pilot-phone", "pilot-phone-phone")

    @Test fun serverOrDeviceChangeNeedsConfirm() {
        val s = setupChangeFor(old, old.copy(server = "https://ntfy.other.net"), passwordReplaced = false)
        assertTrue(s.needsConfirm)
        assertEquals("setup changed: server ntfy.example.com -> ntfy.other.net", s.logLine)
        assertTrue(setupChangeFor(old, old.copy(slug = "other-phone"), false).needsConfirm)
        // The username alone doesn't move the phone to anyone else: no confirm step.
        assertFalse(setupChangeFor(old, old.copy(username = "other"), false).needsConfirm)
    }

    @Test fun firstSetupAndSmallChangesDontAsk() {
        val first = setupChangeFor(Setup("", "", ""), old, passwordReplaced = true)
        assertFalse(first.needsConfirm)
        assertEquals("setup changed: server (none) -> ntfy.example.com; device (none) -> pilot-phone; username (none) -> pilot-phone-phone; password replaced", first.logLine)
        val pw = setupChangeFor(old, old, passwordReplaced = true)
        assertFalse(pw.needsConfirm)
        assertEquals("setup changed: password replaced", pw.logLine)
        assertNull(setupChangeFor(old, old, passwordReplaced = false).logLine)
    }

    @Test fun usernameIsLoggedAsAnExcerpt() {
        val s = setupChangeFor(old, old.copy(username = "a b"), false)
        assertEquals("setup changed: username pilot-phone-phone -> a?b", s.logLine)
    }

    private val m1 = FamilyMember("m1", "Alex")

    @Test fun memberAddedLogged() {
        val s = setupChangeFor(old, old.copy(members = listOf(m1)), false)
        assertEquals("setup changed: member m1 added", s.logLine)
    }

    @Test fun memberRenamedLogged() {
        val a = old.copy(members = listOf(m1))
        val s = setupChangeFor(a, a.copy(members = listOf(FamilyMember("m1", "Robin"))), false)
        assertEquals("setup changed: member m1 renamed", s.logLine)
        assertFalse(s.logLine!!.contains("Alex"))
        assertFalse(s.logLine!!.contains("Robin"))
    }

    @Test fun memberRemovedLogged() {
        val a = old.copy(members = listOf(m1))
        assertEquals("setup changed: member m1 removed", setupChangeFor(a, old, false).logLine)
    }

    @Test fun memberPartsFollowTheOthersInOrder() {
        val a = old.copy(members = listOf(m1, FamilyMember("m2", "Robin")))
        val b = old.copy(
            username = "other",
            members = listOf(FamilyMember("m1", "Alexa"), FamilyMember("m3", "Robin")),
        )
        assertEquals(
            "setup changed: username pilot-phone-phone -> other; member m1 renamed; member m3 added; member m2 removed",
            setupChangeFor(a, b, false).logLine,
        )
    }

    @Test fun memberAddedNeedsConfirm() {
        assertTrue(setupChangeFor(old, old.copy(members = listOf(m1)), false).needsConfirm)
        assertFalse(setupChangeFor(Setup("", "", ""), old.copy(members = listOf(m1)), false).needsConfirm)
    }

    @Test fun memberRenameOrRemoveNeedsNoConfirm() {
        val a = old.copy(members = listOf(m1))
        assertFalse(setupChangeFor(a, a.copy(members = listOf(FamilyMember("m1", "Robin"))), false).needsConfirm)
        assertFalse(setupChangeFor(a, old, false).needsConfirm)
    }

    @Test fun addedMembersListsEachNewIdInOrder() {
        val new = old.copy(members = listOf(FamilyMember("m2", "Robin"), m1, FamilyMember("m3", "Alexa")))
        assertEquals(listOf("m2", "m1", "m3"), addedMembers(old, new))
        assertTrue(setupChangeFor(old, new, false).needsConfirm)
    }

    @Test fun addedMembersIgnoresRenames() {
        val a = old.copy(members = listOf(m1))
        val b = a.copy(members = listOf(FamilyMember("m1", "Robin"), FamilyMember("m2", "Alexa")))
        assertEquals(listOf("m2"), addedMembers(a, b))
        assertTrue(setupChangeFor(a, b, false).needsConfirm)
    }
}
