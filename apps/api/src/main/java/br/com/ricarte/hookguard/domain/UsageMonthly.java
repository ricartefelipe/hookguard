package br.com.ricarte.hookguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "usage_monthly")
@IdClass(UsageMonthly.UsageMonthlyId.class)
public class UsageMonthly {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Id
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    protected UsageMonthly() {
    }

    public UsageMonthly(UUID accountId, String yearMonth, long eventCount) {
        this.accountId = accountId;
        this.yearMonth = yearMonth;
        this.eventCount = eventCount;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getYearMonth() {
        return yearMonth;
    }

    public long getEventCount() {
        return eventCount;
    }

    public void setEventCount(long eventCount) {
        this.eventCount = eventCount;
    }

    public static final class UsageMonthlyId implements Serializable {
        private UUID accountId;
        private String yearMonth;

        public UsageMonthlyId() {
        }

        public UsageMonthlyId(UUID accountId, String yearMonth) {
            this.accountId = accountId;
            this.yearMonth = yearMonth;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof UsageMonthlyId that)) {
                return false;
            }
            return Objects.equals(accountId, that.accountId) && Objects.equals(yearMonth, that.yearMonth);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, yearMonth);
        }
    }
}
