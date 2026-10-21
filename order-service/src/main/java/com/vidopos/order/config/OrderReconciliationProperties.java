package com.vidopos.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "order.reconciliation")
public class OrderReconciliationProperties {

    private long fixedDelayMs = 5000;
    private Duration pendingAge = Duration.ofSeconds(3);
    private int batchSize = 50;

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public Duration getPendingAge() {
        return pendingAge;
    }

    public void setPendingAge(Duration pendingAge) {
        this.pendingAge = pendingAge;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
