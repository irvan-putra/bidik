package com.vanipy.mata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlGuardTest {

    @Test
    fun allowsPublicHttpPages() {
        assertTrue(UrlGuard.isAllowed("https://example.com/vana/"))
        assertTrue(UrlGuard.isAllowed("http://example.com/"))
        assertTrue(UrlGuard.isAllowed("https://sub.example.co.id:8443/p?q=1"))
    }

    @Test
    fun rejectsNonWebSchemes() {
        assertFalse(UrlGuard.isAllowed("file:///etc/passwd"))
        assertFalse(UrlGuard.isAllowed("content://com.example/x"))
        assertFalse(UrlGuard.isAllowed("ftp://example.com/x"))
        assertFalse(UrlGuard.isAllowed("javascript:alert(1)"))
        assertFalse(UrlGuard.isAllowed("not a url"))
    }

    @Test
    fun rejectsPrivateAndLoopback() {
        assertFalse(UrlGuard.isAllowed("http://127.0.0.1:8080/admin"))
        assertFalse(UrlGuard.isAllowed("http://localhost/"))
        assertFalse(UrlGuard.isAllowed("https://router.local/"))
        assertFalse(UrlGuard.isAllowed("http://10.0.0.1/"))
        assertFalse(UrlGuard.isAllowed("http://172.16.0.5/"))
        assertFalse(UrlGuard.isAllowed("http://192.168.1.1/"))
        assertFalse(UrlGuard.isAllowed("http://169.254.169.254/latest/meta-data"))
        assertFalse(UrlGuard.isAllowed("http://100.64.0.1/"))
        assertFalse(UrlGuard.isAllowed("http://[::1]/"))
    }

    @Test
    fun allowsPublicIpLiterals() {
        assertTrue(UrlGuard.isAllowed("https://93.184.216.34/"))
        assertTrue(UrlGuard.isAllowed("http://172.15.9.9/")) // outside 172.16-31
        assertTrue(UrlGuard.isAllowed("http://192.169.1.1/")) // outside 192.168
    }
}
