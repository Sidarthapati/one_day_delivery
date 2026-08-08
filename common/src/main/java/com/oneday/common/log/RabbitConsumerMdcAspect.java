package com.oneday.common.log;

import com.oneday.common.kafka.DomainEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * Wraps every {@code @RabbitListener} method so a consumed message is (a) recorded as a self-contained
 * {@code event.consumed} audit line and (b) run with the shipment/parcel ids in the MDC, tagging the
 * consumer's own {@code log.*} calls with the same correlation keys.
 *
 * <p>This is the counterpart to the {@code event.published} line in {@code RabbitEventPublisher}: together
 * they make the RabbitMQ hop — otherwise invisible once a message is consumed — queryable end-to-end by
 * {@code shipmentId} / {@code shipmentRef} / {@code parcelId}.</p>
 *
 * <p>Implemented as AOP around the listener bean method (not a container-factory advice) so it composes
 * with Boot's retry/DLQ advice chain instead of replacing it, and gets the already-deserialized payload.</p>
 */
@Aspect
@Component
public class RabbitConsumerMdcAspect {

    @Around("@annotation(org.springframework.amqp.rabbit.annotation.RabbitListener)")
    public Object aroundListener(ProceedingJoinPoint pjp) throws Throwable {
        Object payload = firstEventArg(pjp.getArgs());
        CorrelationKeys keys = CorrelationKeys.from(payload);

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String consumer = sig.getDeclaringType().getSimpleName() + "#" + sig.getName();
        String eventType = payload instanceof DomainEvent de ? de.eventTypeName() : null;

        try (var ignored = LogContext.forShipment(keys.shipmentId(), keys.shipmentRef(), keys.parcelId())) {
            AuditLog.event("event.consumed")
                    .kv("consumer", consumer)
                    .kv("eventType", eventType)
                    .kv("shipmentId", keys.shipmentId())
                    .kv("shipmentRef", keys.shipmentRef())
                    .kv("parcelId", keys.parcelId())
                    .log();
            return pjp.proceed();
        }
    }

    /** The message payload is the first argument that is a {@link DomainEvent}; else the first arg. */
    private static Object firstEventArg(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof DomainEvent) {
                return a;
            }
        }
        return args[0];
    }
}
