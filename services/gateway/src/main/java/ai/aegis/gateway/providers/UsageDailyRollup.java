package ai.aegis.gateway.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps the V8 `usage_daily_rollups` table — a materialized per-org, per-day
 * summary of token usage (Req 6.4), refreshed incrementally rather than on
 * every `usage_records` write.
 */
@Entity
@Table(name = "usage_daily_rollups")
public class UsageDailyRollup {

    @EmbeddedId
    private Key id;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "call_count", nullable = false)
    private int callCount;

    protected UsageDailyRollup() {
    }

    public UsageDailyRollup(UUID orgId, LocalDate rollupDate, long inputTokens,
                             long outputTokens, int callCount) {
        this.id = new Key(orgId, rollupDate);
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.callCount = callCount;
    }

    public Key getId() {
        return id;
    }

    public UUID getOrgId() {
        return id.orgId;
    }

    public LocalDate getRollupDate() {
        return id.rollupDate;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public int getCallCount() {
        return callCount;
    }

    public void setCallCount(int callCount) {
        this.callCount = callCount;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "org_id")
        private UUID orgId;

        @Column(name = "rollup_date", nullable = false)
        private LocalDate rollupDate;

        protected Key() {
        }

        public Key(UUID orgId, LocalDate rollupDate) {
            this.orgId = orgId;
            this.rollupDate = rollupDate;
        }

        public UUID getOrgId() {
            return orgId;
        }

        public LocalDate getRollupDate() {
            return rollupDate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(orgId, key.orgId) && Objects.equals(rollupDate, key.rollupDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, rollupDate);
        }
    }
}
