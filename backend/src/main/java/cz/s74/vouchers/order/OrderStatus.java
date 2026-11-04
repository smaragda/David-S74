package cz.s74.vouchers.order;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAYMENT_PROCESSING,
    PAID,
    FAILED,
    EXPIRED,
    REFUNDED,
    DISPUTED
}
