package io.github.doctor277.launchguard.demo.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentHealthControllerTest {

    @Test
    void togglesFailureModeAndRecovers() {
        PaymentHealthController controller = new PaymentHealthController();

        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
        controller.fail();
        assertThat(controller.health().getStatusCode().value()).isEqualTo(500);
        controller.recover();
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
    }
}
