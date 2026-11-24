package ru.hahgps.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevChannelProxyTest {

    @Test
    void socksProxyIsRecognised() {
        var spec = DevChannel.parseProxy("socks5h://127.0.0.1:2080");
        assertEquals("127.0.0.1", spec.host());
        assertEquals(2080, spec.port());
        assertTrue(spec.socks());
    }

    @Test
    void httpProxyIsNotSocks() {
        var spec = DevChannel.parseProxy("http://proxy.local:8080/");
        assertEquals("proxy.local", spec.host());
        assertEquals(8080, spec.port());
        assertFalse(spec.socks());
    }

    @Test
    void brokenProxyIsIgnored() {
        assertNull(DevChannel.parseProxy(""));
        assertNull(DevChannel.parseProxy("socks5h://127.0.0.1"));
        assertNull(DevChannel.parseProxy("socks5h://:2080"));
        assertNull(DevChannel.parseProxy("socks5h://host:0"));
        assertNull(DevChannel.parseProxy("socks5h://host:77777"));
        assertNull(DevChannel.parseProxy("socks5h://host:notaport"));
    }
}
