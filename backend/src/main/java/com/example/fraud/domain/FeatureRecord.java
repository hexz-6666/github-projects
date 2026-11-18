package com.example.fraud.domain;

import java.math.BigDecimal;

public class FeatureRecord {
    private final long featureId;
    private final String orderId;
    private final String featureVersion;
    private final BigDecimal amount;
    private final String currency;
    private final int paymentCount;
    private final int accountAgeDays;
    private final int transactionCount24h;

    public FeatureRecord(long featureId, String orderId, String featureVersion,
                         BigDecimal amount, String currency, int paymentCount,
                         int accountAgeDays, int transactionCount24h) {
        this.featureId = featureId;
        this.orderId = orderId;
        this.featureVersion = featureVersion;
        this.amount = amount;
        this.currency = currency;
        this.paymentCount = paymentCount;
        this.accountAgeDays = accountAgeDays;
        this.transactionCount24h = transactionCount24h;
    }

    public long getFeatureId() { return featureId; }
    public String getOrderId() { return orderId; }
    public String getFeatureVersion() { return featureVersion; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public int getPaymentCount() { return paymentCount; }
    public int getAccountAgeDays() { return accountAgeDays; }
    public int getTransactionCount24h() { return transactionCount24h; }
}
