package com.seyedjafari.analogplus.phone.accounts

/**
 * What a forgotten passphrase leads to, as found by the emulator spike (`docs/poc/key-reset-spike.md`, line
 * `RESET_RESULT:`). SELF_SERVICE: the old face can be removed on the watch itself, so the person can fix it alone.
 * NEEDS_ADB: removal needs a computer. The words for each are in `ui/account/KeyResetText.kt`.
 */
enum class ResetPath { SELF_SERVICE, NEEDS_ADB }

object KeyResetCopy {
    /**
     * The path the screen promises. `KeyResetCopyTest` fails when this and the finding in the spike document disagree,
     * so the screen can never promise more than the spike proved. The spike ran on a Wear OS emulator only: it was not
     * run on a Galaxy Watch (the open checklist item belongs to the accounts plan, Task 18, not to the screen).
     */
    val CURRENT: ResetPath = ResetPath.SELF_SERVICE
}
