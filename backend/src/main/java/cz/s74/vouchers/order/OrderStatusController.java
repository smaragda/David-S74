package cz.s74.vouchers.order;

import cz.s74.vouchers.common.ApiException;
import cz.s74.vouchers.common.Masking;
import cz.s74.vouchers.config.RateLimits;
import cz.s74.vouchers.email.EmailDelivery;
import cz.s74.vouchers.email.EmailDeliveryRepository;
import cz.s74.vouchers.stripe.FulfillmentService;
import cz.s74.vouchers.stripe.PaymentProviderException;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stav objednávky pro návratovou stránku. Nikdy nevrací voucherový kód (R10). */
@RestController
@RequestMapping("/api/public")
public class OrderStatusController {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusController.class);

    private final OrderRepository orders;
    private final VoucherRepository vouchers;
    private final EmailDeliveryRepository deliveries;
    private final FulfillmentService fulfillment;
    private final RateLimits limits;

    public OrderStatusController(
            OrderRepository orders,
            VoucherRepository vouchers,
            EmailDeliveryRepository deliveries,
            FulfillmentService fulfillment,
            RateLimits limits) {
        this.orders = orders;
        this.vouchers = vouchers;
        this.deliveries = deliveries;
        this.fulfillment = fulfillment;
        this.limits = limits;
    }

    public record StatusResponse(
            String status, String orderRef, Long amountCzk, String emailMasked, String emailStatus) {
        static StatusResponse unknown() {
            return new StatusResponse("UNKNOWN", null, null, null, null);
        }
    }

    @GetMapping("/orders/status")
    public StatusResponse status(@RequestParam("session_id") String sessionId, HttpServletRequest http) {
        if (!limits.orderStatusPerIp.tryAcquire(http.getRemoteAddr())) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Příliš mnoho dotazů. Zkuste to za chvíli.");
        }
        // Stripe se volá jen pro session, kterou známe z vlastní DB (žádné dotazy na libovolná ID).
        Optional<VoucherOrder> found = orders.findByStripeCheckoutSessionId(sessionId);
        if (found.isEmpty()) {
            return StatusResponse.unknown();
        }
        VoucherOrder order = found.get();
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT || order.getStatus() == OrderStatus.PAYMENT_PROCESSING) {
            try {
                fulfillment.fulfill(sessionId);
            } catch (PaymentProviderException e) {
                log.warn("Fulfillment from status endpoint failed for {}: {}", order.orderRef(), e.getMessage());
            }
            order = orders.findById(order.getId()).orElseThrow();
        }
        return toResponse(order);
    }

    private StatusResponse toResponse(VoucherOrder order) {
        String status = switch (order.getStatus()) {
            case PENDING_PAYMENT -> "PENDING";
            case PAYMENT_PROCESSING -> "PROCESSING";
            case PAID, REFUNDED, DISPUTED -> "PAID";
            case FAILED -> "FAILED";
            case EXPIRED -> "EXPIRED";
        };
        if (!"PAID".equals(status)) {
            return new StatusResponse(status, order.orderRef(), order.getAmountMinor() / 100, null, null);
        }
        return new StatusResponse(
                status,
                order.orderRef(),
                order.getAmountMinor() / 100,
                Masking.email(order.getCustomerEmail()),
                emailStatus(order));
    }

    private String emailStatus(VoucherOrder order) {
        Optional<Voucher> voucher = vouchers.findByOrderId(order.getId());
        if (voucher.isEmpty()) {
            return "PENDING";
        }
        List<EmailDelivery> list = deliveries.findByVoucherIdOrderByCreatedAtAsc(voucher.get().getId());
        if (list.stream().anyMatch(d -> d.getStatus() == EmailDelivery.Status.SENT)) {
            return "SENT";
        }
        if (!list.isEmpty() && list.stream().allMatch(d -> d.getStatus() == EmailDelivery.Status.FAILED)) {
            return "FAILED";
        }
        return "PENDING";
    }
}
