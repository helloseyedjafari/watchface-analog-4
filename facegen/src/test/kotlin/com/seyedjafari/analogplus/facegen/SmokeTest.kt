package com.seyedjafari.analogplus.facegen

import org.junit.Assert.assertNotNull
import org.junit.Test

class SmokeTest {
    @Test fun moduleLoads() = assertNotNull(Facegen)
}
