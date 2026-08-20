package com.oneday.app.prod;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockGuardTest {

    @Test
    void passesWhenNoMockBeansPresent() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.containsBean(anyString())).thenReturn(false);
        assertThatCode(() -> new MockGuard(ctx).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void refusesBootWhenAMockBeanLeaksIntoProd() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.containsBean(anyString())).thenReturn(false);
        when(ctx.containsBean("mockPaymentController")).thenReturn(true);
        assertThatThrownBy(() -> new MockGuard(ctx).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mockPaymentController");
    }
}
