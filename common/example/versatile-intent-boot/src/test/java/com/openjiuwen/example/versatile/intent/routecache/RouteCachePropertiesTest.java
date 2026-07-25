package com.openjiuwen.example.versatile.intent.routecache;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RouteCachePropertiesTest {
    @Test
    void defaultsAreDisabledAndThirtyMinutes() {
        RouteCacheProperties props = new RouteCacheProperties();
        assertFalse(props.isEnabled());
        assertEquals(Duration.ofMinutes(30), props.getTtl());
    }

    @Test
    void settersMutateFields() {
        RouteCacheProperties props = new RouteCacheProperties();
        props.setEnabled(true);
        props.setTtl(Duration.ofMinutes(5));
        assertTrue(props.isEnabled());
        assertEquals(Duration.ofMinutes(5), props.getTtl());
    }
}