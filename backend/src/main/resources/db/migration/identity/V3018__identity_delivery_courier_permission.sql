-- DELIVERY_COURIER (complete-app follow-ups Q4): who may be a delivery's courier. The POS dispatch lists, and accepts
-- as courierEmployeeId, only the store's active employees whose role in that store holds it.
-- No seeded role is a driver (spec §3 has seven profiles), so the permission goes to every role that already holds
-- DELIVERY_OPERATE (Administrador, Gerente, Caixa as seeded): an operator who dispatched themselves before keeps doing
-- so. A dedicated driver gets a tenant role holding DELIVERY_COURIER (BackOffice role editor). Every administrator
-- role holds every permission (RoleSeedTest), DELIVERY_OPERATE included, so it is covered too.
-- Applied to the templates a new tenant is provisioned from (D181) and to the roles of every existing tenant; the
-- migration owner bypasses RLS (D163). Idempotent through ON CONFLICT.
INSERT INTO identity.role_template_permission (template_id, permission)
SELECT template_id, 'DELIVERY_COURIER'
FROM identity.role_template_permission
WHERE permission = 'DELIVERY_OPERATE'
UNION
SELECT id, 'DELIVERY_COURIER'
FROM identity.role_template
WHERE administrator
ON CONFLICT (template_id, permission) DO NOTHING;

INSERT INTO identity.role_permission (tenant_id, role_id, permission)
SELECT tenant_id, role_id, 'DELIVERY_COURIER'
FROM identity.role_permission
WHERE permission = 'DELIVERY_OPERATE'
UNION
SELECT tenant_id, id, 'DELIVERY_COURIER'
FROM identity.role
WHERE administrator
ON CONFLICT (tenant_id, role_id, permission) DO NOTHING;
