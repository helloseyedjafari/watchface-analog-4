package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page sizes and bounds of the design list and of the wipe are chosen together (see `FirestoreRemoteStore`). A saved
 * design never carries a photo (`Store.saveNamed` strips it), so a design document is a few kilobytes of JSON; the only
 * document with a photo is `state/current`, one of at most 950 kB. The rules' 950 kB cap per design document is a limit
 * against other writers; this app does not produce such documents, and the numbers are not sized for them.
 */
class WipeNumbersTest {
    private fun secondsAtOneMegabit(bytes: Long) = bytes * 8.0 / 1_000_000

    @Test fun aTypicalPageOfDesignsArrivesWithinTheListBoundOnAOneMegabitLinkWithRoomToSpare() {
        val page = FirestoreRemoteStore.DEFAULT_FETCH_PAGE_SIZE * FirestoreRemoteStore.TYPICAL_DESIGN_BYTES
        val bound = FirestoreRemoteStore.DEFAULT_FETCH_PAGE_TIMEOUT_MS / 1000.0
        assertTrue("page $page bytes: ${secondsAtOneMegabit(page)} s against $bound s", secondsAtOneMegabit(page) * 10 <= bound)
    }

    @Test fun aTypicalWipePageAndTheCurrentDesignArriveWithinTheWipeReadBoundWithRoomToSpare() {
        val bound = FirestoreRemoteStore.DEFAULT_WIPE_READ_TIMEOUT_MS / 1000.0
        val page = FirestoreRemoteStore.DEFAULT_WIPE_PAGE_SIZE * FirestoreRemoteStore.TYPICAL_DESIGN_BYTES
        assertTrue("typical wipe page ${secondsAtOneMegabit(page)} s against $bound s", secondsAtOneMegabit(page) * 10 <= bound)
        // the `state` collection holds the one document that can carry a photo, up to the rules' cap
        val current = FirestoreRemoteStore.MAX_DESIGN_BYTES
        assertTrue("current design ${secondsAtOneMegabit(current)} s against $bound s", secondsAtOneMegabit(current) * 1.5 <= bound)
    }

    @Test fun aPageIsNotTinyWhenTheAppSyncsTwoHundredDesigns() {
        // 200 designs must not take dozens of round trips: at most a handful of pages
        assertTrue(MAX_SYNCED_DESIGNS <= 4 * FirestoreRemoteStore.DEFAULT_FETCH_PAGE_SIZE)
        assertTrue(MAX_SYNCED_DESIGNS <= 4 * FirestoreRemoteStore.DEFAULT_WIPE_PAGE_SIZE)
    }

    @Test fun thePageLimitOfTheWipeCoversWellMoreThanTheDocumentsAnAccountCanHold() {
        // live designs are capped at MAX_SYNCED_DESIGNS; tombstones are not (a deleted name stays), so the margin is large
        assertTrue(FirestoreRemoteStore.DEFAULT_WIPE_PAGE_SIZE * FirestoreRemoteStore.DEFAULT_MAX_WIPE_PAGES >= 50 * MAX_SYNCED_DESIGNS)
    }
}
