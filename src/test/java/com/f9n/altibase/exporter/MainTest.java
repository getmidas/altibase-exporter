package com.f9n.altibase.exporter;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void parseDisabledMetrics_null_returnsEmpty() {
        Set<String> result = Main.parseDisabledMetrics(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseDisabledMetrics_blank_returnsEmpty() {
        assertTrue(Main.parseDisabledMetrics("").isEmpty());
        assertTrue(Main.parseDisabledMetrics("   ").isEmpty());
        assertTrue(Main.parseDisabledMetrics("\t").isEmpty());
    }

    @Test
    void parseDisabledMetrics_singleKey() {
        assertEquals(Set.of("sysstat"), Main.parseDisabledMetrics("sysstat"));
        assertEquals(Set.of("sysstat"), Main.parseDisabledMetrics("  sysstat  "));
    }

    @Test
    void parseDisabledMetrics_multipleKeys() {
        assertEquals(Set.of("sysstat", "replication_gap"), Main.parseDisabledMetrics("sysstat,replication_gap"));
        assertEquals(Set.of("a", "b"), Main.parseDisabledMetrics(" a , b "));
    }

    @Test
    void parseDisabledMetrics_emptySegmentsFiltered() {
        assertEquals(Set.of("a", "b"), Main.parseDisabledMetrics("a,,b"));
        assertEquals(Set.of("x"), Main.parseDisabledMetrics("  ,  x  ,  "));
    }

    @Test
    void isDisabled_wildcardDisablesEveryKey() {
        AltibaseCollector all = new AltibaseCollector(null, Set.of("*"), "0");
        assertTrue(all.isDisabled("sysstat"));
        assertTrue(all.isDisabled("anything"));
    }

    @Test
    void isDisabled_respectsSpecificKeysAndDefaults() {
        AltibaseCollector some = new AltibaseCollector(null, Set.of("sysstat"), "0");
        assertTrue(some.isDisabled("sysstat"));
        assertFalse(some.isDisabled("property"));

        AltibaseCollector none = new AltibaseCollector(null, Set.of(), "0");
        assertFalse(none.isDisabled("sysstat"));
    }
}
