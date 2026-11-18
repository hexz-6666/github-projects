package com.example.fraud.store;

/** 同一 alert 已存在工单；由服务层转换为幂等或配置冲突响应。 */
public class DuplicateWorkOrderException extends RuntimeException {
    public DuplicateWorkOrderException() {
        super("alert already has a work order");
    }
}
