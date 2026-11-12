package cz.s74.vouchers.email;

import static org.assertj.core.api.Assertions.assertThat;

import cz.s74.vouchers.support.AbstractIT;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;

class EmailOutboxTest extends AbstractIT {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired EmailOutbox outbox;

    private String deliveryField(String col) {
        return jdbc.queryForObject("select cast(" + col + " as varchar) from email_delivery", String.class);
    }

    private static void collect(Part part, List<Part> out) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                collect(mp.getBodyPart(i), out);
            }
        } else {
            out.add(part);
        }
    }

    private static String qrText(byte[] png) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        return new MultiFormatReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))))
                .getText();
    }

    @Test
    void sendsEmailWithCodeQrValidityAndTerms() throws Exception {
        var voucher = issueVoucher("CZK_1000", "jana@example.com");
        outbox.processDue();

        assertThat(deliveryField("status")).isEqualTo("SENT");
        assertThat(deliveryField("provider_message_id")).isNotBlank();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage mail = received[0];
        assertThat(mail.getSubject()).isEqualTo("Váš dárkový voucher do S74 wine&café – 1 000 Kč");
        assertThat(mail.getFrom()[0].toString()).contains("vouchers@s74.test");
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo("jana@example.com");
        assertThat(mail.getReplyTo()[0].toString()).contains("s74winecafe@seznam.cz");

        List<Part> parts = new ArrayList<>();
        collect(mail, parts);
        String html = null;
        String text = null;
        byte[] png = null;
        for (Part p : parts) {
            if (p.isMimeType("text/html")) {
                html = (String) p.getContent();
            } else if (p.isMimeType("text/plain")) {
                text = (String) p.getContent();
            } else if (p.isMimeType("image/png")) {
                try (InputStream in = p.getInputStream()) {
                    png = in.readAllBytes();
                }
            }
        }
        String formatted = voucher.getCode().substring(0, 4) + "-" + voucher.getCode().substring(4, 8) + "-"
                + voucher.getCode().substring(8);
        String until = voucher.getValidUntil().getDayOfMonth() + ". " + voucher.getValidUntil().getMonthValue() + ". "
                + voucher.getValidUntil().getYear();
        for (String body : new String[] {html, text}) {
            assertThat(body).contains(formatted).contains("Platí do").contains(until);
            assertThat(body).contains("nevyužitý rozdíl propadá").contains("jednorázově");
            assertThat(body).contains("S74 wine&").contains("IČO 23035111");
            assertThat(body).contains("http://localhost:8000/obchodni-podminky-vouchery.html");
            assertThat(body).contains(voucher.getOrderId().toString().substring(0, 8).toUpperCase());
        }
        assertThat(html).contains("cid:voucher-qr").doesNotContain("http://localhost:8000/images");
        assertThat(png).isNotNull();
        // QR vede na stránku obsluhy; neobsahuje heslo ani token
        assertThat(qrText(png)).isEqualTo("http://localhost:8080/staff/v/" + voucher.getCode());
    }

    @Test
    void smtpFailureRetriesWithBackoffAndEndsFailedWithAttention() throws Exception {
        issueVoucher("CZK_500", "a@example.com");
        greenMail.stop(); // SMTP nedostupné

        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};
        for (int i = 0; i < 4; i++) {
            var before = clock.instant();
            outbox.processDue();
            assertThat(deliveryField("status")).isEqualTo("PENDING");
            assertThat(deliveryField("attempts")).isEqualTo(String.valueOf(i + 1));
            assertThat(jdbc.queryForObject("select next_attempt_at from email_delivery", java.sql.Timestamp.class)
                            .toInstant())
                    .isBetween(before.plus(expected[i]).minusSeconds(2), before.plus(expected[i]).plusSeconds(2));

            // dokud nenastane čas dalšího pokusu, job nic neposílá
            outbox.processDue();
            assertThat(deliveryField("attempts")).isEqualTo(String.valueOf(i + 1));
            clock.setInstant(clock.instant().plus(expected[i]).plusSeconds(1));
        }
        outbox.processDue();
        assertThat(deliveryField("status")).isEqualTo("FAILED");
        assertThat(deliveryField("attempts")).isEqualTo("5");
        assertThat(deliveryField("last_error")).isNotBlank();
        assertThat(jdbc.queryForObject("select attention_required from voucher_order", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'EMAIL_FAILED'", Integer.class))
                .isEqualTo(1);

        // opravený SMTP + znovuzaslání = nový řádek, který projde
        greenMail.start();
        var voucherId = vouchers.findAll().get(0).getId();
        outbox.queueResend(voucherId, "fixed@example.com", null);
        outbox.processDue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
        assertThat(greenMail.getReceivedMessages()[0].getAllRecipients()[0].toString()).isEqualTo("fixed@example.com");
        assertThat(count("email_delivery")).isEqualTo(2);
    }

    @Test
    void sendingStuckForMoreThanTenMinutesIsRetried() throws Exception {
        issueVoucher("CZK_500", "a@example.com");
        jdbc.update("update email_delivery set status = 'SENDING', next_attempt_at = ?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(11))));
        outbox.processDue();
        assertThat(deliveryField("status")).isEqualTo("SENT");
    }

    @Test
    void statusEndpointShowsEmailStatus() throws Exception {
        issueVoucher("CZK_500", "a@example.com");
        String sid = gateway.lastSessionId();
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/orders/status")
                .param("session_id", sid);
        mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.emailStatus")
                .value("PENDING"));
        outbox.processDue();
        mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.emailStatus")
                .value("SENT"));
    }
}
