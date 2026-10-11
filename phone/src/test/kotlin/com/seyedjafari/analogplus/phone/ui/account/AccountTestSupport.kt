package com.seyedjafari.analogplus.phone.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * Fails when a sealed type has a case that [samples] does not hold one of, so a case added to a result type cannot slip
 * past the tests of the text that is made for every case (the `when` in main already refuses to compile without it).
 */
fun assertSealedCovered(sealed: Class<*>, samples: Collection<Any>) {
    val permitted = sealed.permittedSubclasses
    assertNotNull("${sealed.simpleName} is not sealed in the bytecode", permitted)
    assertEquals(
        "cases of ${sealed.simpleName} without a sample (or samples of another type)",
        permitted!!.map { it.simpleName }.toSortedSet(), samples.map { it.javaClass.simpleName }.toSortedSet(),
    )
}
