package com.seyedjafari.analogplus.phone

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The resources of the current configuration. Reading [LocalConfiguration] makes a composable that calls this recompose
 * when the language changes, the same way `stringResource` does.
 */
@Composable
internal fun currentResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/** What the user reads for [this] problem. In English it equals [SendProblem.english] (an instrumented test checks every case). */
fun SendProblem.text(r: Resources): String = when (this) {
    is SendProblem.Plain -> text
    SendProblem.NotInstalled -> r.getString(R.string.send_not_installed)
    SendProblem.NoWatch -> r.getString(R.string.send_no_watch)
    is SendProblem.CouldNotReach -> r.getString(R.string.send_error_could_not_reach, detail, r.getString(R.string.send_no_watch))
    is SendProblem.FileTimeout -> r.getString(R.string.send_error_file_timeout, seconds)
    is SendProblem.AnswerTimeout -> r.getString(R.string.send_error_answer_timeout, seconds)
    is SendProblem.SendFailed -> r.getString(R.string.send_error_send_failed, detail.text(r))
    is SendProblem.FixDesignFirst -> r.getString(R.string.send_error_fix_design, errors)
    // the store's exception always carries this one sentence; the resource is its translatable form
    is SendProblem.VersionCounter -> r.getString(R.string.send_error_version_counter)
    is SendProblem.WatchRefused -> r.getString(R.string.send_error_watch_refused, reason ?: r.getString(R.string.send_unknown_reason))
    is SendProblem.KeyMismatch -> r.getString(
        if (restoreBackupFirst) R.string.send_error_key_mismatch_restore_first else R.string.send_error_key_mismatch,
        reason ?: r.getString(R.string.send_unknown_reason),
    )
    is SendProblem.FaceChecksFailed -> r.getString(R.string.send_error_face_checks, failures.ifBlank { r.getString(R.string.send_no_detail) })
    is SendProblem.DesignNotBuildable -> r.getString(R.string.send_error_not_buildable, reason ?: r.getString(R.string.send_invalid_design))
    SendProblem.OutOfMemory -> r.getString(R.string.send_error_out_of_memory)
    is SendProblem.BuilderMissing -> r.getString(R.string.send_error_builder_missing, detail)
    is SendProblem.BuildFailed -> r.getString(R.string.send_error_build_failed, detail)
    SendProblem.NoAnswer -> r.getString(R.string.send_error_no_answer)
    is SendProblem.CouldNotSend -> r.getString(R.string.send_error_could_not_send, detail)
}

/** What the user reads for [this] link or scan problem. In English it equals [ShareProblem.english]. */
fun ShareProblem.text(r: Resources): String = when (this) {
    is ShareProblem.Plain -> text
    ShareProblem.EmptyLink -> r.getString(R.string.share_error_empty_link)
    ShareProblem.NotADesignLink -> r.getString(R.string.share_error_not_a_link)
    is ShareProblem.Unreadable -> r.getString(R.string.share_error_unreadable, detail)
    ShareProblem.NotOurs -> r.getString(R.string.qr_not_ours)
    ShareProblem.EmptyScan -> r.getString(R.string.qr_scan_empty)
    is ShareProblem.ScannerUnavailable -> r.getString(R.string.qr_scanner_unavailable, detail)
    is ShareProblem.ScannerFailedToStart -> r.getString(R.string.qr_scanner_failed, detail)
}

fun SendDetail.text(r: Resources): String = when (this) {
    is SendDetail.Raw -> text
    is SendDetail.EndedEarly -> r.getString(R.string.send_detail_ended_early, reason.text(r))
    is SendDetail.ClosedBeforeSent -> r.getString(R.string.send_detail_closed_before_sent, reason.text(r))
}

fun CloseReason.text(r: Resources): String = when (kind) {
    CloseKind.NORMAL -> r.getString(R.string.send_close_normal)
    CloseKind.DISCONNECTED -> r.getString(R.string.send_close_disconnected)
    CloseKind.CLOSED_BY_WATCH -> r.getString(R.string.send_close_by_watch)
    CloseKind.CLOSED_BY_PHONE -> r.getString(R.string.send_close_by_phone)
    CloseKind.OTHER -> r.getString(R.string.send_close_other, code)
}
