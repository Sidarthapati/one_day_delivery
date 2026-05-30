package com.oneday.orders.service;

/**
 * Extension point for registering additional state transitions into the
 * {@link TransitionRegistry} at application startup.
 *
 * <p>Any Spring bean implementing this interface will be discovered automatically
 * and applied by {@code TransitionRegistry} in its {@code @PostConstruct} phase,
 * after the V1 base transitions have been registered. This allows future modules
 * or V2 flows to contribute new transitions without modifying existing code.</p>
 *
 * <p>Example:</p>
 * <pre>
 * {@literal @}Component
 * class V2PartialDeliveryTransitions implements TransitionRegistryConfigurer {
 *     {@literal @}Override
 *     public void configure(TransitionRegistry registry) {
 *         registry.register(ShipmentState.DROPPED, ShipmentState.PARTIAL_RETURN_INITIATED);
 *     }
 * }
 * </pre>
 */
public interface TransitionRegistryConfigurer {
    void configure(TransitionRegistry registry);
}
