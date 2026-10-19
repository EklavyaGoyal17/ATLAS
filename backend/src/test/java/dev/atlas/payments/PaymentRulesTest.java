package dev.atlas.payments;

import static org.assertj.core.api.Assertions.*;

import dev.atlas.platform.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentRulesTest {

    @ParameterizedTest
    @CsvSource({
        "AUTHORIZED,CAPTURE,true",
        "AUTHORIZED,REFUND,false",
        "AUTHORIZED,REVERSE,true",
        "DECLINED,CAPTURE,false",
        "DECLINED,REFUND,false",
        "DECLINED,REVERSE,false",
        "CAPTURED,CAPTURE,false",
        "CAPTURED,REFUND,true",
        "CAPTURED,REVERSE,false",
        "REFUNDED,CAPTURE,false",
        "REFUNDED,REFUND,false",
        "REFUNDED,REVERSE,false",
        "REVERSED,CAPTURE,false",
        "REVERSED,REFUND,false",
        "REVERSED,REVERSE,false",
    })
    void stateMachineHasOnlyThreeLegalEdges(
        String state,
        PaymentRules.Command command,
        boolean legal
    ) {
        if (legal) assertThatCode(() ->
            PaymentRules.checkTransition(state, command)
        ).doesNotThrowAnyException();
        else assertThatThrownBy(() ->
            PaymentRules.checkTransition(state, command)
        ).isInstanceOf(ApiException.class);
    }

    @Test
    void refundBoundariesUseRemainingAmountWithoutOverflow() {
        assertThat(
            PaymentRules.refundTotal(1_000_000_000_000L, 999_999_999_999L, 1)
        ).isEqualTo(1_000_000_000_000L);
        assertThatThrownBy(() ->
            PaymentRules.refundTotal(100, 90, 11)
        ).isInstanceOf(ApiException.class);
        assertThatThrownBy(() ->
            PaymentRules.refundTotal(100, 90, 0)
        ).isInstanceOf(ApiException.class);
        assertThatThrownBy(() ->
            PaymentRules.refundTotal(100, 90, -1)
        ).isInstanceOf(ApiException.class);
    }
}
