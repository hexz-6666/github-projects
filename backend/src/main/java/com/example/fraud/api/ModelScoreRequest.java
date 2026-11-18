package com.example.fraud.api;

import com.example.fraud.domain.FeatureRecord;
import java.math.BigDecimal;

public class ModelScoreRequest {
    private final String orderId;
    private final BigDecimal amount;
    private final String currency;
    private final int paymentCount;
    private final int accountAgeDays;
    private final int transactionCount24h;

    public ModelScoreRequest(FeatureRecord feature) {
        this.orderId = feature.getOrderId();
        this.amount = feature.getAmount();
        this.currency = feature.getCurrency();
        this.paymentCount = feature.getPaymentCount();
        this.accountAgeDays = feature.getAccountAgeDays();
        this.transactionCount24h = feature.getTransactionCount24h();
    }

    public String getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public int getPaymentCount() { return paymentCount; }
    public int getAccountAgeDays() { return accountAgeDays; }
    public int getTransactionCount24h() { return transactionCount24h; }
}
