package cz.s74.vouchers.admin;

import java.util.Map;
import org.springframework.stereotype.Component;

/** České popisky auditních akcí pro historii v rozhraní vedoucího ({@code @auditLabels}). */
@Component("auditLabels")
public class AuditLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("ORDER_CREATED", "Objednávka založena"),
            Map.entry("PAYMENT_CONFIRMED", "Platba potvrzena"),
            Map.entry("VOUCHER_ISSUED", "Voucher vystaven"),
            Map.entry("EMAIL_SENT", "E-mail odeslán"),
            Map.entry("EMAIL_FAILED", "E-mail se nepodařilo odeslat"),
            Map.entry("EMAIL_RESEND_REQUESTED", "Vyžádáno opětovné zaslání"),
            Map.entry("VOUCHER_VIEWED", "Voucher zobrazen"),
            Map.entry("VOUCHER_DISPLAYED", "Voucher zobrazen zákazníkovi po platbě"),
            Map.entry("VOUCHER_REDEEMED", "Voucher uplatněn"),
            Map.entry("REDEMPTION_REJECTED", "Uplatnění zamítnuto"),
            Map.entry("REDEMPTION_REVERTED", "Uplatnění vráceno"),
            Map.entry("VOUCHER_CANCELLED", "Voucher zrušen"),
            Map.entry("VOUCHER_REACTIVATED", "Voucher reaktivován"),
            Map.entry("VALIDITY_EXTENDED", "Platnost prodloužena"),
            Map.entry("ORDER_REFUNDED", "Platba vrácena"),
            Map.entry("ORDER_DISPUTED", "Spor o platbu"),
            Map.entry("ORDER_DISPUTE_CLOSED", "Spor uzavřen"),
            Map.entry("ORDER_MISMATCH", "Nesoulad platby"),
            Map.entry("ASYNC_PAYMENT_FAILED", "Odložená platba selhala"),
            Map.entry("ATTENTION_RESOLVED", "Označeno jako vyřešené"),
            Map.entry("LOGIN_SUCCESS", "Přihlášení"),
            Map.entry("LOGIN_FAILED", "Neúspěšné přihlášení"),
            Map.entry("LOGIN_LOCKED", "Přihlášení zablokováno"),
            Map.entry("LOOKUP_NOT_FOUND", "Kód nenalezen"),
            Map.entry("RATE_LIMITED", "Překročen limit pokusů"),
            Map.entry("TENANT_CREATED", "Podnik založen"),
            Map.entry("TENANT_PROFILE_UPDATED", "Profil podniku upraven"),
            Map.entry("TENANT_SALES_UPDATED", "Nastavení prodeje upraveno"),
            Map.entry("TENANT_OFFERS_UPDATED", "Ceník upraven"),
            Map.entry("TENANT_FEES_UPDATED", "Provize platformy upravena"),
            Map.entry("TENANT_SUSPENDED", "Prodej pozastaven"),
            Map.entry("TENANT_ACTIVATED", "Prodej obnoven"),
            Map.entry("TENANT_PAYMENT_MODE_CHANGED", "Změna režimu plateb"),
            Map.entry("STRIPE_ACCOUNT_CREATED", "Stripe účet založen"),
            Map.entry("STRIPE_ONBOARDING_STARTED", "Otevřen onboarding Stripe"),
            Map.entry("STRIPE_STATUS_CHANGED", "Změna stavu Stripe účtu"));

    public String label(String action) {
        return LABELS.getOrDefault(action, action);
    }
}
