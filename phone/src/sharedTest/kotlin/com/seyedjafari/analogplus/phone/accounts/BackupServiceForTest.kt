package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A [BackupService] for tests. The service has no default for its user check (a construction site that forgot it would silently
 * skip the question whether the user still exists); the tests that are not about that question get an answer of "still there"
 * here, and the ones that are pass their own.
 */
fun backupServiceForTest(
    remote: RemoteStore,
    vault: KeyVault,
    keys: KeySource,
    limiter: AttemptLimiter,
    cpu: CoroutineDispatcher = Dispatchers.Default,
    markerSeenLive: (String) -> Boolean = { false },
    userVerifier: UserVerifier = UserVerifier { UserCheck.STILL_THERE },
): BackupService = BackupService(remote, vault, keys, limiter, cpu, markerSeenLive, userVerifier)
