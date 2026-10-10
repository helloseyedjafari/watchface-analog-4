package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorIdTokenSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorIdTokenSourceTest {
    @Test fun theTokenIsTheJsonTheAuthEmulatorAcceptsAsAGoogleIdToken() = runTest {
        val token = EmulatorIdTokenSource { "tester@example.com" }.fetchIdToken()!!
        val json = Json.parseToJsonElement(token).jsonObject
        assertEquals("tester", json.getValue("sub").jsonPrimitive.content)
        assertEquals("tester@example.com", json.getValue("email").jsonPrimitive.content)
        assertTrue(json.getValue("email_verified").jsonPrimitive.boolean)
    }

    @Test fun anAddressWithQuotesStillMakesValidJson() = runTest {
        val token = EmulatorIdTokenSource { "we\"ird@example.com" }.fetchIdToken()!!
        assertEquals("we\"ird@example.com", Json.parseToJsonElement(token).jsonObject.getValue("email").jsonPrimitive.content)
    }

    @Test fun theAddressIsAskedForEveryTime() = runTest {
        var address = "a@example.com"
        val source = EmulatorIdTokenSource { address }
        source.fetchIdToken()
        address = "b@example.com"
        assertTrue(source.fetchIdToken()!!.contains("b@example.com"))
    }
}
