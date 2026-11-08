package cz.s74.vouchers.order;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.ApiException;
import cz.s74.vouchers.common.Masking;
import cz.s74.vouchers.config.RateLimits;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.email.QrCodeService;
import cz.s74.vouchers.email.EmailDelivery;
import cz.s74.vouchers.email.EmailDeliveryRepository;
import cz.s74.vouchers.stripe.FulfillmentService;
import cz.s74.vouchers.stripe.PaymentProviderException;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherCodes;
import cz.s74.vouchers.voucher.VoucherRepository;
import cz.s74.vouchers.voucher.VoucherStatus;
import cz.s74.vouchers.tenant.TenantSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Stav objednávky pro návratovou stránku (API i hostovaná stránka). Voucher (kód + QR) vrací jen krátce po zaplacení
 * ({@code show-on-return-minutes} podniku), dokud je aktivní; jinak jen e-mailem (R10, upraveno).
 */
@Service
public class OrderStatusService {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusService.class);

    private final OrderRepository orders;
    private final VoucherRepository vouchers;
    private final EmailDeliveryRepository deliveries;
    private final FulfillmentService fulfillment;
    private final RateLimits limits;
    private final QrCodeService qr;
    private final AuditService audit;
    private final S74Properties props;
    private final Clock clock;

    public OrderStatusService(
            OrderRepository orders,
            VoucherRepository vouchers,
            EmailDeliveryRepository deliveries,
            FulfillmentService fulfillment,
            RateLimits limits,
            QrCodeService qr,
            AuditService audit,
            S74Properties props,
            Clock clock) {
        this.orders = orders;
        this.vouchers = vouchers;
        this.deliveries = deliveries;
        this.fulfillment = fulfillment;
        this.limits = limits;
        this.qr = qr;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public record StatusResponse(
            String status,
            String orderRef,
            Long amountCzk,
            String emailMasked,
            String emailStatus,
            VoucherDto voucher) {
        static StatusResponse unknown() {
            return new StatusResponse("UNKNOWN", null, null, null, null, null);
        }
    }

    /** Voucher zobrazený zákazníkovi po platbě: formátovaný kód, platnost (ISO datum) a QR jako data URI. */
    public record VoucherDto(String code, String validUntil, long amountCzk, String qrDataUri) {}

    public StatusResponse status(TenantSettings tenant, String sessionId, String clientIp) {
        if (!limits.orderStatusPerIp.tryAcquire(clientIp)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Příliš mnoho dotazů. Zkuste to za chvíli.");
        }
        // Stripe se volá jen pro session, kterou známe z vlastní DB (žádné dotazy na libovolná ID).
        Optional<VoucherOrder> found = sessionId == null
                ? Optional.empty()
                : orders.findByStripeCheckoutSessionId(sessionId).filter(o -> o.getTenantId().equals(tenant.id()));
        if (found.isEmpty()) {
            return StatusResponse.unknown();
        }
        VoucherOrder order = found.get();
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT || order.getStatus() == OrderStatus.PAYMENT_PROCESSING) {
            try {
                fulfillment.fulfill(order.getStripeAccountId(), sessionId);
            } catch (PaymentProviderException e) {
                log.warn("Fulfillment from status endpoint failed for {}: {}", order.orderRef(), e.getMessage());
            }
            order = orders.findById(order.getId()).orElseThrow();
        }
        return toResponse(tenant, order);
    }

    private StatusResponse toResponse(TenantSettings tenant, VoucherOrder order) {
        String status = switch (order.getStatus()) {
            case PENDING_PAYMENT -> "PENDING";
            case PAYMENT_PROCESSING -> "PROCESSING";
            case PAID, REFUNDED, DISPUTED -> "PAID";
            case FAILED -> "FAILED";
            case EXPIRED -> "EXPIRED";
        };
        if (!"PAID".equals(status)) {
            return new StatusResponse(status, order.orderRef(), order.getAmountMinor() / 100, null, null, null);
        }
        return new StatusResponse(
                status,
                order.orderRef(),
                order.getAmountMinor() / 100,
                Masking.email(order.getCustomerEmail()),
                emailStatus(order),
                visibleVoucher(tenant, order));
    }

    /** Voucher jen pro zaplacenou objednávku, aktivní voucher a v časovém okně po zaplacení. */
    private VoucherDto visibleVoucher(TenantSettings tenant, VoucherOrder order) {
        int minutes = tenant.showOnReturnMinutes();
        Instant paidAt = order.getPaidAt();
        if (minutes <= 0 || order.getStatus() != OrderStatus.PAID || paidAt == null) {
            return null;
        }
        if (Instant.now(clock).isAfter(paidAt.plus(Duration.ofMinutes(minutes)))) {
            return null;
        }
        Optional<Voucher> found = vouchers.findByOrderId(order.getId());
        if (found.isEmpty() || found.get().getStatus() != VoucherStatus.ACTIVE) {
            return null;
        }
        Voucher v = found.get();
        audit.system("VOUCHER_DISPLAYED", v.getId(), order.getId(), Map.of("channel", "return-page"));
        String qrContent = props.publicBaseUrlNoSlash() + "/staff/v/" + v.getCode();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(qr.png(qrContent, 360));
        return new VoucherDto(VoucherCodes.format(v.getCode()), v.getValidUntil().toString(), v.getAmountMinor() / 100, dataUri);
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
