-- Rename the persisted TriggerSource value KAFKA_EVENT -> BROKER_EVENT to match the code enum after
-- the common.kafka -> common.messaging package rename (the system uses RabbitMQ, never Kafka). The
-- column is @Enumerated(STRING), so existing audit rows store the literal name and must be rewritten
-- or JPA would fail to map them to the (now renamed) enum constant.
UPDATE shipment_state_history
   SET trigger_source = 'BROKER_EVENT'
 WHERE trigger_source = 'KAFKA_EVENT';
