package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncResolverTest {
    private val a = DesignSettings(name = "A")
    private val b = DesignSettings(name = "B", accent = "#FF1E6BFF")
    private fun remote(id: String, d: DesignSettings, at: Long, state: DesignState = DesignState.LIVE, name: String = "n") =
        RemoteDesign(id, name, if (state == DesignState.LIVE) DesignJson.toJson(d) else "", at, state, 1)
    private fun local(
        id: String, d: DesignSettings?, modified: Long, base: Long?, mark: SyncMark, state: EntryState = EntryState.LIVE, name: String = "n",
    ) = LocalDesignEntry(id, name, d, state, modified, base, mark)
    private fun plan(l: List<LocalDesignEntry>, r: List<RemoteDesign>) = planSync(l, r, l.map { it.name }.toSet())

    @Test fun nothingToDoWhenBothSidesAgree() {
        assertEquals(emptyList<SyncAction>(), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(remote("1", a, 500))))
    }

    @Test fun dirtyLocalOnlyPushes() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", b, 10, 500, SyncMark.DIRTY)), listOf(remote("1", a, 500))))
    }

    @Test fun newerRemoteOnlyIsApplied() {
        val r = remote("1", b, 900)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(r)))
    }

    @Test fun designOnlyInTheCloudIsApplied() {
        val r = remote("1", a, 900)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(emptyList(), listOf(r)))
    }

    @Test fun designOnlyOnThePhoneIsPushed() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", a, 10, null, SyncMark.DIRTY)), emptyList()))
    }

    @Test fun bothChangedLaterEditWins_localNewer() {
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(l), listOf(remote("1", a, 1_500))))
    }

    @Test fun bothChangedLaterEditWins_remoteNewer() {
        val r = remote("1", a, 3_000)
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun tieGoesToTheCloud() {
        val r = remote("1", a, 2_000)
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun localDeleteIsPushedAsATombstone() {
        val l = local("1", null, 2_000, 500, SyncMark.DIRTY, EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.PushTombstone("1")), plan(listOf(l), listOf(remote("1", a, 500))))
    }

    @Test fun remoteTombstoneDeletesACleanLocalDesign() {
        val r = remote("1", a, 900, DesignState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteTombstone("1", r)), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(r)))
    }

    @Test fun aNewerEditElsewhereRevivesALocallyDeletedDesign() {
        val r = remote("1", b, 5_000)
        val l = local("1", null, modified = 2_000, base = 500, mark = SyncMark.DIRTY, state = EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun anOlderEditElsewhereDoesNotReviveALocallyDeletedDesign() {
        val l = local("1", null, modified = 9_000, base = 500, mark = SyncMark.DIRTY, state = EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.PushTombstone("1")), plan(listOf(l), listOf(remote("1", b, 5_000))))
    }

    @Test fun aLocalEditNewerThanARemoteTombstoneRevivesIt() {
        val l = local("1", b, modified = 9_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(l), listOf(remote("1", a, 5_000, DesignState.TOMBSTONE))))
    }

    @Test fun deletedOnBothSidesOnlyRecordsTheBase() {
        val r = remote("1", a, 5_000, DesignState.TOMBSTONE)
        val l = local("1", null, 9_000, 500, SyncMark.DIRTY, EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.RecordBase("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun aSyncedUntouchedDesignThatVanishedFromTheCloudIsNotWrittenBack() {
        assertEquals(emptyList<SyncAction>(), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), emptyList()))
    }

    @Test fun aSyncedDesignEditedAfterTheCloudCopyVanishedIsPushedAgain() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", b, 10, 500, SyncMark.DIRTY)), emptyList()))
    }

    @Test fun aTombstoneForSomethingTheCloudNeverHadIsForgotten() {
        assertEquals(listOf(SyncAction.Drop("1")), plan(listOf(local("1", null, 9_000, null, SyncMark.DIRTY, EntryState.TOMBSTONE)), emptyList()))
    }

    @Test fun aRemoteTombstoneForAnUnknownDesignIsIgnored() {
        assertEquals(emptyList<SyncAction>(), plan(emptyList(), listOf(remote("1", a, 5_000, DesignState.TOMBSTONE))))
    }

    @Test fun identicalContentOnBothSidesWithoutHistoryOnlyRecordsTheBase() {
        val r = remote("1", a, 5_000)
        assertEquals(listOf(SyncAction.RecordBase("1", r)), plan(listOf(local("1", a, 9_000, null, SyncMark.DIRTY)), listOf(r)))
    }

    @Test fun differingContentWithoutHistoryKeepsBoth() {
        val r = remote("1", b, 5_000, name = "Night")
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        assertEquals(listOf(SyncAction.KeepBoth("1", r, "Night (this phone)")), plan(listOf(l), listOf(r)))
    }

    @Test fun keepBothPicksAFreeName() {
        val r = remote("1", b, 5_000, name = "Night")
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        val taken = setOf("Night", "Night (this phone)")
        assertEquals("Night (this phone) 2", (planSync(listOf(l), listOf(r), taken).single() as SyncAction.KeepBoth).copyName)
    }

    @Test fun unreadableRemoteJsonIsNeverTreatedAsEqual() {
        val bad = RemoteDesign("1", "n", "{not json", 5_000, DesignState.LIVE, 1)
        val actions = plan(listOf(local("1", a, 9_000, null, SyncMark.DIRTY)), listOf(bad))
        assertTrue(actions.single() is SyncAction.KeepBoth || actions.single() is SyncAction.PushLive)
    }

    // Plan Review Focus 1: a phone that is still signed in must not re-upload what another phone deleted.
    @Test fun aWholeLibraryOfSyncedUntouchedDesignsThatVanishedFromTheCloudWritesNothingBack() {
        val l = listOf(
            local("1", a, 10, 500, SyncMark.CLEAN, name = "One"),
            local("2", b, 20, 600, SyncMark.CLEAN, name = "Two"),
            local("3", a, 30, 700, SyncMark.CLEAN, name = "Three"),
        )
        assertEquals(emptyList<SyncAction>(), plan(l, emptyList()))
    }

    @Test fun afterTheCloudWasWipedOnlyTheDesignEditedSinceIsPushedAndDeletionsAreForgotten() {
        val l = listOf(
            local("1", a, 10, 500, SyncMark.CLEAN, name = "One"),
            local("2", b, 20, 600, SyncMark.DIRTY, name = "Two"),
            local("3", null, 30, 700, SyncMark.DIRTY, EntryState.TOMBSTONE, name = "Three"),
        )
        assertEquals(listOf(SyncAction.PushLive("2"), SyncAction.Drop("3")), plan(l, emptyList()))
    }

    @Test fun aNeverSyncedCleanDesignIsStillPushed() {
        // CLEAN with no base cannot normally happen, but it must never be mistaken for "removed in the cloud".
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", a, 10, null, SyncMark.CLEAN)), emptyList()))
    }

    @Test fun twoKeepBothCopiesGetDifferentNames() {
        val l = listOf(
            local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night"),
            local("2", a, 9_000, null, SyncMark.DIRTY, name = "Night (this phone)"),
        )
        val r = listOf(remote("1", b, 5_000, name = "Night"), remote("2", b, 5_000, name = "Night (this phone)"))
        val names = planSync(l, r, setOf("Night", "Night (this phone)")).map { (it as SyncAction.KeepBoth).copyName }
        assertEquals(2, names.toSet().size)
        assertTrue(names.none { it == "Night" || it == "Night (this phone)" })
    }

    @Test fun aRenameIsATombstoneForTheOldIdPlusANewDocument() {
        val l = listOf(
            local("old", null, 9_000, 500, SyncMark.DIRTY, EntryState.TOMBSTONE, name = "Old"),
            local("new", a, 9_000, null, SyncMark.DIRTY, name = "New"),
        )
        assertEquals(
            listOf(SyncAction.PushTombstone("old"), SyncAction.PushLive("new")),
            plan(l, listOf(remote("old", a, 500, name = "Old"))),
        )
    }

    @Test fun anEditElsewhereAfterOurTombstoneWasPlannedOnlyRevivesWhenNewer() {
        // Equal timestamps: ties go to the cloud, so the design comes back rather than silently disappearing.
        val r = remote("1", b, 9_000)
        val l = local("1", null, modified = 9_000, base = 500, mark = SyncMark.DIRTY, state = EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun uniqueNameCountsUpFromTwo() {
        assertEquals("A", uniqueName("A", emptySet()))
        assertEquals("A 2", uniqueName("A", setOf("A")))
        assertEquals("A 3", uniqueName("A", setOf("A", "A 2")))
    }

    @Test fun remoteErrorKindsMapFirestoreCodes() {
        assertEquals(RemoteErrorKind.OFFLINE, remoteErrorKind("UNAVAILABLE"))
        assertEquals(RemoteErrorKind.OFFLINE, remoteErrorKind("DEADLINE_EXCEEDED"))
        assertEquals(RemoteErrorKind.PERMISSION, remoteErrorKind("PERMISSION_DENIED"))
        assertEquals(RemoteErrorKind.PERMISSION, remoteErrorKind("UNAUTHENTICATED"))
        assertEquals(RemoteErrorKind.QUOTA, remoteErrorKind("RESOURCE_EXHAUSTED"))
        assertEquals(RemoteErrorKind.OTHER, remoteErrorKind("INTERNAL"))
    }

    @Test fun designIdIsStableTrimmedAndFirestoreSafe() {
        assertEquals(designId("Night"), designId("  Night "))
        assertEquals(designId("café"), designId("café"))
        assertTrue(designId("Night").matches(Regex("[0-9a-f]{32}")))
        assertTrue(designId("a/b") != designId("a"))
    }
}
