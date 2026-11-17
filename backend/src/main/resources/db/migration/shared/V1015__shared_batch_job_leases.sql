-- R6 S77 (A92, F3): a reach-ALL job's tick takes the leases of all its tenants in one transaction and releases them in
-- one, instead of two transactions per tenant. The same compare-and-set as shared.try_lease (V1010), per (job, tenant):
-- a lease is taken when it is free or expired, or renewed by its own holder. The tenants are locked in id order, so two
-- instances taking overlapping batches cannot deadlock. Not SECURITY DEFINER: the application role writes
-- shared.job_lease (PLATFORM, V1012's allow-list) and binds no tenant here. shared.try_lease stays for a rolling
-- upgrade (an older instance still calls it).
CREATE FUNCTION shared.try_leases(p_job text, p_tenants uuid[], p_holder text, p_ttl interval) RETURNS SETOF uuid
    LANGUAGE sql AS
$$
INSERT INTO shared.job_lease AS l (job, tenant_id, holder, lease_until)
SELECT p_job, t.id, p_holder, clock_timestamp() + p_ttl
FROM (SELECT DISTINCT unnest(p_tenants) AS id) t
ORDER BY t.id
ON CONFLICT ON CONSTRAINT job_lease_pk DO UPDATE
    SET holder = excluded.holder, lease_until = excluded.lease_until
WHERE l.lease_until <= clock_timestamp()
   OR l.holder = excluded.holder
RETURNING l.tenant_id
$$;

CREATE FUNCTION shared.release_leases(p_job text, p_tenants uuid[], p_holder text) RETURNS void
    LANGUAGE sql AS
$$
UPDATE shared.job_lease
SET lease_until = clock_timestamp()
WHERE job = p_job
  AND tenant_id = ANY (p_tenants)
  AND holder = p_holder
$$;
