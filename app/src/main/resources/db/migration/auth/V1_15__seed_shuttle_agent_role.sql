-- M12: the shuttle agent persona (carries flight bags hub↔airport). City-scoped like other field roles.
INSERT INTO roles (name, display_name, city_scoped, is_builtin) VALUES
    ('SHUTTLE_AGENT', 'Shuttle Agent', TRUE, TRUE);
