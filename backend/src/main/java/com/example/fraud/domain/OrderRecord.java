package com.example.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public class OrderRecord {
    private final String orderId;
    private final String orderNo;
    private final String customerRef;
    private final String customerEmailMasked;
    private final OrderStatus status;
    @JsonSerialize(using = ToStringSerializer.class)
    private final BigDecimal amount;
    private final String currency;
    private final String dataOrigin;
    private final Instant orderedAt;

    public OrderRecord(String orderId, String orderNo, String customerRef,
                       String customerEmailMasked, OrderStatus status, BigDecimal amount,
                       String currency, String dataOrigin, Instant orderedAt) {
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.customerRef = customerRef;
        this.customerEmailMasked = customerEmailMasked;
        this.status = status;
        this.amount = amount;
        this.currency = currency;
        this.dataOrigin = dataOrigin;
        this.orderedAt = orderedAt;
    }

    public String getOrderId() { return orderId; }
    public String getOrderNo() { return orderNo; }
    public String getCustomerRef() { return customerRef; }
    public String getCustomerEmailMasked() { return customerEmailMasked; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDataOrigin() { return dataOrigin; }
    public Instant getOrderedAt() { return orderedAt; }
}
