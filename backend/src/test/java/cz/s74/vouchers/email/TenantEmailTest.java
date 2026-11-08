package cz.s74.vouchers.email;

import static org.assertj.core.api.Assertions.assertThat;

import cz.s74.vouchers.connect.ConnectGateway;
import cz.s74.vouchers.connect.ConnectService;
import cz.s74.vouchers.order.OrderRepository;
import cz.s74.vouchers.platform.TenantAdminService;
import cz.s74.vouchers.staff.StaffRole;
import cz.s74.vouchers.staff.StaffUser;
import cz.s74.vouchers.support.AbstractIT;
import cz.s74.vouchers.tenant.Tenant;
import cz.s74.vouchers.tenant.TenantRepository;
import cz.s74.vouchers.voucher.Voucher;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** E-mail s voucherem jiného podniku: jeho název, adresa, podmínky a Reply-To; odesílatel = doména platformy. */
class TenantEmailTest extends AbstractIT {

    @Autowired VoucherMailer mailer;
    @Autowired TenantAdminService tenantAdmin;
    @Autowired ConnectService connect;
    @Autowired TenantRepository tenants;
    @Autowired OrderRepository orders;

    @Test
    void voucherEmailIsBrandedForTenant() throws Exception {
        StaffUser admin = createStaff("platform", "platform-password-1", StaffRole.PLATFORM_ADMIN);
        Tenant t = tenantAdmin.create(admin.getId(), new TenantAdminService.NewTenant(
                "bistro", "Bistro Zahrada", "Zahrada s.r.o.", "99887766", "Zahradní 5, Olomouc", "info@bistro.test",
                "700"), "127.0.0.1");
        connect.onboardingLink(t.getId(), admin.getId(), "127.0.0.1", "https://x/r", "https://x/s");
        String account = tenants.findById(t.getId()).orElseThrow().getStripeAccountId();
        connectGateway.setStatus(account, new ConnectGateway.AccountStatus("active", 0, false));
        connect.refresh(t.getId());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/public/t/bistro/checkout-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":\"CZK_700\",\"email\":\"eva@example.com\",\"termsAccepted\":true,"
                        + "\"termsVersion\":\"2026-11\"}"));
        String sid = gateway.lastSessionId();
        gateway.markPaid(sid);
        sendConnectWebhook(account, "checkout.session.completed", "{\"id\":\"" + sid + "\"}");
        Voucher v = vouchers.findAll().get(0);

        MimeMessage message = mailer.build(v, orders.findById(v.getOrderId()).orElseThrow(), "eva@example.com");
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Váš dárkový voucher do Bistro Zahrada – 700\u00a0Kč");
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("vouchers@s74.test");
        assertThat(from.getPersonal()).isEqualTo("Bistro Zahrada");
        assertThat(message.getReplyTo()[0].toString()).isEqualTo("info@bistro.test");

        String body = decodedText(message);
        assertThat(body).contains("Bistro Zahrada", "Zahradní 5, Olomouc", "Zahrada s.r.o., IČO 99887766",
                "http://localhost:8080/v/bistro/podminky");
        assertThat(body).doesNotContain("S74", "Slezská");
    }

    private static String decodedText(MimeMessage message) throws Exception {
        StringBuilder sb = new StringBuilder();
        collect(message, sb);
        return sb.toString();
    }

    private static void collect(jakarta.mail.Part part, StringBuilder sb) throws Exception {
        if (part.isMimeType("multipart/*")) {
            MimeMultipart mp = (MimeMultipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                collect(mp.getBodyPart(i), sb);
            }
        } else if (part.isMimeType("text/*")) {
            sb.append(part.getContent()).append('\n');
        }
    }
}
