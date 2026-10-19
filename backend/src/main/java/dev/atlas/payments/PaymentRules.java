package dev.atlas.payments;

import dev.atlas.platform.ApiException;
import org.springframework.http.HttpStatus;

/** Pure state-machine rules; persistence and HTTP delivery do not belong here. */
public final class PaymentRules {

    private PaymentRules() {}

    public enum Command {
        CAPTURE,
        REFUND,
        REVERSE,
    }

    public static void checkTransition(String status, Command command) {
        boolean valid = switch (command) {
            case CAPTURE, REVERSE -> status.equals("AUTHORIZED");
            case REFUND -> status.equals("CAPTURED");
        };
        if (!valid) throw new ApiException(
            HttpStatus.CONFLICT,
            "INVALID_TRANSITION",
            "Cannot " +
                command.name().toLowerCase(java.util.Locale.ROOT) +
                " a payment in state " +
                status +
                "."
        );
    }

    public static long refundTotal(
        long captured,
        long alreadyRefunded,
        long amount
    ) {
        if (
            amount <= 0 || amount > captured - alreadyRefunded
        ) throw new ApiException(
            HttpStatus.CONFLICT,
            "INVALID_TRANSITION",
            "Refund exceeds the remaining captured amount or is not positive."
        );
        return alreadyRefunded + amount;
    }
}
