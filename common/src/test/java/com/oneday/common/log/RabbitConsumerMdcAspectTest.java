package com.oneday.common.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RabbitConsumerMdcAspectTest {

    private final RabbitConsumerMdcAspect aspect = new RabbitConsumerMdcAspect();
    private Logger auditLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        auditLogger = (Logger) LoggerFactory.getLogger("com.oneday.audit");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void setsMdcDuringInvocationAndClearsAfter() throws Throwable {
        UUID sid = UUID.randomUUID();
        ScanEvent payload = new ScanEvent(sid, ScanEventType.LABEL_GENERATED, "1DD-DEL-260530-000001", Instant.now());

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getArgs()).thenReturn(new Object[]{payload});
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getDeclaringType()).thenReturn(Object.class);
        when(sig.getName()).thenReturn("onScanEvent");
        String[] mdcInside = new String[3];
        when(pjp.proceed()).thenAnswer(inv -> {
            mdcInside[0] = MDC.get("shipmentId");
            mdcInside[1] = MDC.get("parcelId");
            mdcInside[2] = MDC.get("shipmentRef");
            return null;
        });

        aspect.aroundListener(pjp);

        // MDC populated for the duration of the listener…
        assertThat(mdcInside[0]).isEqualTo(sid.toString());
        assertThat(mdcInside[1]).isEqualTo("1DD-DEL-260530-000001");
        assertThat(mdcInside[2]).isNull();
        // …and cleared afterwards so it doesn't leak to the next task on this thread.
        assertThat(MDC.get("shipmentId")).isNull();
        assertThat(MDC.get("parcelId")).isNull();
    }

    @Test
    void emitsEventConsumedAuditLine() throws Throwable {
        UUID sid = UUID.randomUUID();
        ScanEvent payload = new ScanEvent(sid, ScanEventType.LABEL_GENERATED);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getArgs()).thenReturn(new Object[]{payload});
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getDeclaringType()).thenReturn(Object.class);
        when(sig.getName()).thenReturn("onScanEvent");
        when(pjp.proceed()).thenReturn(null);

        aspect.aroundListener(pjp);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent line = appender.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getMessage()).isEqualTo("event.consumed");
        // The correlation ids ride as structured arguments (→ JSON fields under the encoder),
        // not as message placeholders — so assert on the argument array.
        assertThat(java.util.Arrays.stream(line.getArgumentArray()).map(Object::toString))
                .anyMatch(s -> s.contains("shipmentId") && s.contains(sid.toString()));
    }
}
