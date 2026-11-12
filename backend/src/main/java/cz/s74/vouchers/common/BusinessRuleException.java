package cz.s74.vouchers.common;

/** Porušení obchodního pravidla v rozhraní obsluhy / vedoucího; zpráva je česky a zobrazí se uživateli. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
