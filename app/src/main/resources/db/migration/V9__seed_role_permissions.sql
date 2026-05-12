-- Uses subselects on name/action so no hardcoded UUIDs are needed.

-- ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'ADMIN' AND p.action IN (
    'shipment:view', 'shipment:override',
    'hub:manage',
    'route:approve', 'route:override',
    'api-key:manage',
    'audit:view',
    'user:create', 'user:deactivate', 'user:role:change',
    'config:manage', 'flight:manage'
);

-- STATION_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'STATION_MANAGER' AND p.action IN (
    'shipment:view:city',
    'route:approve:city', 'route:override:city',
    'sla:red:action',
    'audit:view:city',
    'user:create:city', 'user:role:change:city'
);

-- SUPERVISOR
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'SUPERVISOR' AND p.action IN (
    'shipment:view',
    'sla:red:action',
    'exception:escalate'
);

-- HUB_OPERATOR
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'HUB_OPERATOR' AND p.action IN (
    'hub:scan', 'hub:stand:assign', 'hub:bag:manage',
    'shipment:view'
);

-- DELIVERY_ASSOCIATE
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'DELIVERY_ASSOCIATE' AND p.action IN (
    'da:queue:view', 'barcode:attach', 'scan:event:create',
    'shipment:view:assigned'
);

-- VAN_DRIVER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'VAN_DRIVER' AND p.action IN (
    'route:view:assigned', 'route:stop:confirm',
    'shipment:view:assigned'
);

-- CRON_DRIVER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'CRON_DRIVER' AND p.action IN (
    'cron:run:confirm', 'scan:event:create',
    'shipment:view:assigned'
);

-- CALL_CENTER_AGENT
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'CALL_CENTER_AGENT' AND p.action IN (
    'exception:capture', 'shipment:reschedule', 'shipment:view'
);

-- B2B_USER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'B2B_USER' AND p.action IN (
    'shipment:create', 'shipment:view:own',
    'pricing:quote', 'invoice:view:own', 'api-key:create:own'
);

-- B2C_CUSTOMER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'B2C_CUSTOMER' AND p.action IN (
    'shipment:create', 'shipment:track:own', 'pricing:quote'
);

-- C2C_CUSTOMER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'C2C_CUSTOMER' AND p.action IN (
    'shipment:create', 'shipment:view:own', 'shipment:track:own', 'pricing:quote'
);

-- AIRLINE_GHA
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'AIRLINE_GHA' AND p.action IN (
    'manifest:view', 'handover:acknowledge'
);
