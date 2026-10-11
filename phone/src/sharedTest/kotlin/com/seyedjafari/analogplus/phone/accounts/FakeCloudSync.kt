package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A [CloudSync] that only records calls and lets tests set [status] and the offer. */
class FakeCloudSync : CloudSync {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    private val _offer = MutableStateFlow<CurrentOffer?>(null)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val currentOffer: StateFlow<CurrentOffer?> = _offer.asStateFlow()

    private val _removed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val accountRemoved: SharedFlow<String> = _removed

    /** The engine found out that [uid]'s account was deleted by another phone. */
    fun accountWasRemoved(uid: String) { _removed.tryEmit(uid) }

    val started = ArrayList<String>()
    var stops = 0
    var syncNowCalls = 0
    val choices = ArrayList<OfferChoice>()

    /** Every call of [setForeground], in order. */
    val foregroundCalls = ArrayList<Boolean>()

    override fun start(uid: String) { started += uid; _status.value = SyncStatus.Syncing }
    override fun stop() { stops++; _status.value = SyncStatus.Off }
    override suspend fun syncNow() { syncNowCalls++ }
    override fun resolveOffer(choice: OfferChoice) { choices += choice; _offer.value = null }
    override fun setForeground(foreground: Boolean) { foregroundCalls += foreground }
    var editorCompleteCalls = 0
    override fun editorComplete() { editorCompleteCalls++ }

    fun setStatus(s: SyncStatus) { _status.value = s }
    fun setOffer(o: CurrentOffer?) { _offer.value = o }
}
