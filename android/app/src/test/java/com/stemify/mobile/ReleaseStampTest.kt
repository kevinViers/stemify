package com.stemify.mobile

import com.stemify.mobile.ui.screens.releaseStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseStampTest {
    @Test fun formats() {
        assertEquals("JAN.01.70", releaseStamp("1970-01-01"))
        assertEquals("MAR.23.04", releaseStamp("2004-03-23"))
        assertEquals("DEC.31.99", releaseStamp("1999-12-31T00:00:00Z"))
        assertEquals("NOV.69", releaseStamp("1969-11"))
        assertEquals("1973", releaseStamp("1973"))
        assertNull(releaseStamp(""))
        assertNull(releaseStamp(null))
        assertNull(releaseStamp("unknown"))
    }
}
