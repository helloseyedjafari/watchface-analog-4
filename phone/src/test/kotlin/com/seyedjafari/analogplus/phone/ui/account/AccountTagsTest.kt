package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.Tags
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTagsTest {
    private fun tagsOf(type: Class<*>): Map<String, String> = type.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .associate { it.name to (it.apply { isAccessible = true }.get(null) as String) }

    private val account = tagsOf(AccountTags::class.java)

    @Test fun everyTagIsUniqueAndShaped() {
        assertTrue("reflection found the tags", account.size > 30)
        assertEquals("two tags with one value: ${account.values.groupBy { it }.filter { it.value.size > 1 }.keys}", account.size, account.values.toSet().size)
        for ((name, value) in account) assertTrue("$name = $value", Regex("[a-z]+(_[a-z]+)+").matches(value))
    }

    @Test fun accountTagsDoNotCollideWithTheAppsOtherTags() {
        val others = tagsOf(Tags::class.java).values.toSet()
        assertEquals(emptySet<String>(), account.values.toSet() intersect others)
    }

    @Test fun theTagsThePlanAndTheTestsNameExist() {
        val needed = listOf(
            "SIGN_IN", "EMAIL", "SYNC_STATUS", "BACKUP_STATUS", "BACK_UP", "RESTORE", "SYNC_NOW", "SIGN_OUT", "DELETE", "NOTICE", "PASSPHRASE",
            "PASSPHRASE_CONFIRM", "PASSPHRASE_STRENGTH", "PASSPHRASE_SUBMIT", "PASSPHRASE_ERROR", "PASSPHRASE_CANCEL", "FORGOT", "REPLACE_CONFIRM",
            "REPLACE_CANCEL", "RESET_CONFIRM", "RESET_CANCEL", "RESET_NEW_PASSPHRASE", "DELETE_CONFIRM", "DELETE_CANCEL", "OFFER_USE", "OFFER_KEEP",
            "NOT_CONFIGURED", "SIGNING_IN", "SIGN_IN_ERROR", "PAGE_SIGN_IN", "PAGE_SKIP", "PAGE_DONE", "PAGE_RESTORE",
            "DELETION_PENDING", "FINISH_DELETION", "KEEP_ACCOUNT", "NOTICE_DISMISS", "BUSY", "REPLACE_BACKUP_CONFIRM", "REPLACE_BACKUP_CANCEL",
        )
        assertEquals(emptyList<String>(), needed.filter { it !in account })
    }
}
