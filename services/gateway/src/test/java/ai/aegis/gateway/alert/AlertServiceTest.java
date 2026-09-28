package ai.aegis.gateway.alert;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceTest {

    /** A test channel that counts sends. */
    static class CountingChannel implements AlertChannel {
        final AtomicInteger count = new AtomicInteger();
        final boolean configured;

        CountingChannel(boolean configured) {
            this.configured = configured;
        }

        public String name() { return "counting"; }
        public boolean isConfigured() { return configured; }
        public boolean send(String s, String b, String sev) { count.incrementAndGet(); return true; }
    }

    @Test
    void dispatchesToConfiguredChannelsAtOrAboveMinSeverity() {
        CountingChannel ch = new CountingChannel(true);
        AlertService service = new AlertService(List.of(ch));

        service.dispatch("s", "b", "critical", "high"); // fires
        service.dispatch("s", "b", "low", "high");      // below threshold, skipped
        service.dispatch("s", "b", "high", "high");     // at threshold, fires

        assertThat(ch.count.get()).isEqualTo(2);
    }

    @Test
    void skipsUnconfiguredChannels() {
        CountingChannel ch = new CountingChannel(false);
        AlertService service = new AlertService(List.of(ch));
        service.dispatch("s", "b", "critical", "low");
        assertThat(ch.count.get()).isZero();
    }

    @Test
    void dispatchIncidentFiresOnlyForHighAndAbove() {
        CountingChannel ch = new CountingChannel(true);
        AlertService service = new AlertService(List.of(ch));
        service.dispatchIncident("t", "medium"); // below high -> skip
        service.dispatchIncident("t", "critical"); // fire
        assertThat(ch.count.get()).isEqualTo(1);
    }
}
