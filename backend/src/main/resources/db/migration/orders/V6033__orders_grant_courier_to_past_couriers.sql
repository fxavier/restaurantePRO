-- DELIVERY_COURIER without capability loss (complete-app Q4, after identity V3018). Before the permission, any active
-- employee of the store could be a delivery's courier; V3018 grants it only to the roles holding DELIVERY_OPERATE, so a
-- driver holding another role (a waiter, a custom role) would drop off the POS list. Rule: every role assigned to an
-- employee in the store of a delivery that employee was the courier of also gets DELIVERY_COURIER, whatever the role.
-- Here, not in identity, because it reads orders.delivery (V6021), which a fresh database creates after V3018. Like
-- tenancy V15001 reading organization, it reaches into a lower block (identity, D5 blocks: reads its assignments,
-- writes its role grants) and depends on no higher one, so the out-of-order application of ADR 0001 still holds. tenant_id comes from the assignment row (the
-- owner bypasses RLS, D163); idempotent through ON CONFLICT.
INSERT INTO identity.role_permission (tenant_id, role_id, permission)
SELECT DISTINCT a.tenant_id, a.role_id, 'DELIVERY_COURIER'
FROM orders.delivery d
         JOIN identity.employee_store_assignment a
              ON a.tenant_id = d.tenant_id
                  AND a.employee_id = d.courier_employee_id
                  AND a.store_id = d.store_id
WHERE d.courier_employee_id IS NOT NULL
ON CONFLICT (tenant_id, role_id, permission) DO NOTHING;
