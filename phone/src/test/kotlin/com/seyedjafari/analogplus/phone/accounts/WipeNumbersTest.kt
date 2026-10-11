package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wipe's page size and bounds are chosen together (see `FirestoreRemoteStore.deleteAllUserData`): the worst page has to
 * arrive within its read bound on a slow link, and the page count has to cover more than the app lets a user store.
 */
class WipeNumbersTest {
    private val pageSize = FirestoreRemoteStore.DEFAULT_WIPE_PAGE_SIZE
    private val worstPageBytes = pageSize * FirestoreRemoteStore.MAX_DESIGN_BYTES

    @Test fun theWorstPageArrivesWithinTheReadBoundOnAOneMegabitLinkWithRoomToSpare() {
        val secondsAtOneMbit = worstPageBytes * 8.0 / 1_000_000
        val boundSeconds = FirestoreRemoteStore.DEFAULT_WIPE_READ_TIMEOUT_MS / 1000.0
        assertTrue("worst page $secondsAtOneMbit s against a bound of $boundSeconds s", secondsAtOneMbit * 1.5 <= boundSeconds)
    }

    @Test fun theWorstFetchPageAlsoArrivesWithinItsBoundOnAOneMegabitLink() {
        val worst = FirestoreRemoteStore.DEFAULT_FETCH_PAGE_SIZE * FirestoreRemoteStore.MAX_DESIGN_BYTES
        val secondsAtOneMbit = worst * 8.0 / 1_000_000
        val boundSeconds = FirestoreRemoteStore.DEFAULT_FETCH_PAGE_TIMEOUT_MS / 1000.0
        assertTrue("worst fetch page $secondsAtOneMbit s against a bound of $boundSeconds s", secondsAtOneMbit * 1.5 <= boundSeconds)
        assertTrue(FirestoreRemoteStore.DEFAULT_FETCH_PAGE_SIZE * FirestoreRemoteStore.DEFAULT_MAX_FETCH_PAGES >= 5 * MAX_SYNCED_DESIGNS)
    }

    @Test fun thePageLimitCoversWellMoreThanTheDesignsTheAppSyncs() {
        assertTrue(pageSize * FirestoreRemoteStore.DEFAULT_MAX_WIPE_PAGES >= 5 * MAX_SYNCED_DESIGNS)
    }
}
