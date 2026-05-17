INSERT INTO roles (name, display_name, city_scoped, is_builtin) VALUES
    ('ADMIN',              'Administrator',        FALSE, TRUE),
    ('STATION_MANAGER',    'Station Manager',      TRUE,  TRUE),
    ('SUPERVISOR',         'Supervisor',           TRUE,  TRUE),
    ('HUB_OPERATOR',       'Hub Operator',         TRUE,  TRUE),
    ('DELIVERY_ASSOCIATE', 'Delivery Associate',   TRUE,  TRUE),
    ('VAN_DRIVER',         'Van Driver',           TRUE,  TRUE),
    ('CRON_DRIVER',        'Cron Driver',          TRUE,  TRUE),
    ('CALL_CENTER_AGENT',  'Call Center Agent',    TRUE,  TRUE),
    ('B2B_USER',           'B2B User',             FALSE, TRUE),
    ('B2C_CUSTOMER',       'B2C Customer',         FALSE, TRUE),
    ('C2C_CUSTOMER',       'C2C Customer',         FALSE, TRUE),
    ('AIRLINE_GHA',        'Airline GHA',          FALSE, TRUE);
