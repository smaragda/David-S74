package cz.s74.vouchers.email;

import cz.s74.vouchers.common.Money;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.order.VoucherOrder;
import cz.s74.vouchers.tenant.TenantService;
import cz.s74.vouchers.tenant.TenantSettings;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherCodes;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/** Sestaví a odešle e-mail s voucherem (HTML + textová alternativa, QR jako inline obrázek). */
@Service
public class VoucherMailer {

    static final DateTimeFormatter CZ_DATE = DateTimeFormatter.ofPattern("d. M. yyyy", Locale.forLanguageTag("cs"));
    static final String QR_CID = "voucher-qr";

    private final JavaMailSender sender;
    private final TemplateEngine templates;
    private final QrCodeService qr;
    private final S74Properties props;
    private final TenantService tenants;

    public VoucherMailer(
            JavaMailSender sender,
            TemplateEngine templates,
            QrCodeService qr,
            S74Properties props,
            TenantService tenants) {
        this.sender = sender;
        this.templates = templates;
        this.qr = qr;
        this.props = props;
        this.tenants = tenants;
    }

    /** Odešle e-mail a vrátí hlavičku Message-ID (pokud ji poskytovatel/knihovna nastavila). */
    public String send(Voucher voucher, VoucherOrder order, String recipient) throws Exception {
        MimeMessage message = build(voucher, order, recipient);
        sender.send(message);
        return message.getMessageID();
    }

    MimeMessage build(Voucher voucher, VoucherOrder order, String recipient) throws Exception {
        TenantSettings tenant = tenants.settings(voucher.getTenantId());
        String value = Money.czkFromMinor(voucher.getAmountMinor());
        Context ctx = new Context(Locale.forLanguageTag("cs"));
        ctx.setVariable("tenantName", tenant.displayName());
        ctx.setVariable("legalLine", tenant.legalLine());
        ctx.setVariable("address", tenant.address());
        ctx.setVariable("accent", tenant.brandColor() != null ? tenant.brandColor() : "#b1442f");
        ctx.setVariable("value", value);
        ctx.setVariable("code", VoucherCodes.format(voucher.getCode()));
        ctx.setVariable("validUntil", CZ_DATE.format(voucher.getValidUntil()));
        ctx.setVariable("orderRef", order.orderRef());
        ctx.setVariable("siteUrl", tenant.siteUrl());
        ctx.setVariable("termsUrl", tenant.termsUrl());
        ctx.setVariable("replyTo", tenant.replyTo());
        ctx.setVariable("qrCid", QR_CID);
        String html = templates.process("mail/voucher", ctx);
        String text = templates.process("mail/voucher.txt", ctx);

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from(tenant));
        if (tenant.replyTo() != null) {
            helper.setReplyTo(new InternetAddress(tenant.replyTo(), false));
        }
        helper.setTo(recipient);
        helper.setSubject("Váš dárkový voucher do " + tenant.displayName() + " – " + value);
        helper.setText(text, html);
        String qrContent = props.publicBaseUrlNoSlash() + "/staff/v/" + voucher.getCode();
        helper.addInline(QR_CID, new ByteArrayResource(qr.png(qrContent, 600)), "image/png");
        return message;
    }

    /**
     * Odesílá se ze společné ověřené domény platformy ({@code s74.mail.from}); jméno odesílatele je název podniku,
     * odpovědi jdou na adresu podniku (Reply-To). Výchozí podnik má odesílatele přesně podle konfigurace.
     */
    private InternetAddress from(TenantSettings tenant) throws Exception {
        InternetAddress configured = new InternetAddress(props.mail().from(), false);
        if (tenant.id().equals(cz.s74.vouchers.tenant.Tenants.DEFAULT_ID)) {
            return configured;
        }
        return new InternetAddress(configured.getAddress(), tenant.displayName(), "UTF-8");
    }
}
