package io.github.doctor277.launchguard.demo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderHealthControllerTest {

    @Test
    void togglesFailureModeAndRecovers() {
        var controller = new OrderHealthController(new DemoProperties(2000));
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
        controller.fail();
        assertThat(controller.health().getStatusCode().value()).isEqualTo(500);
        controller.recover();
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void validatesAndConfiguresLatencyControlsIndependentlyOfFailure() {
        var controller = new OrderHealthController(new DemoProperties(1500));
        assertThat(controller.slow(null).delayMs()).isEqualTo(1500);
        assertThat(controller.slow(300).delayMs()).isEqualTo(300);
        assertThatThrownBy(() -> controller.slow(0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.slow(30001)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new DemoProperties(0)).isInstanceOf(IllegalArgumentException.class);
        controller.fail();
        assertThat(controller.normal().delayMs()).isZero();
        assertThat(controller.health().getStatusCode().value()).isEqualTo(500);
        controller.recover();
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void separateInstancesDoNotShareFailureState() {
        var first = new OrderHealthController(new DemoProperties(2000));
        var second = new OrderHealthController(new DemoProperties(2000));
        first.fail();
        assertThat(second.health().getStatusCode().value()).isEqualTo(200);
        assertThat(first.health().getStatusCode().value()).isEqualTo(500);
    }
}
