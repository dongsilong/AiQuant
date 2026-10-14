-- AiQuant: one credential belongs to exactly one user.
-- REVIEW FIRST: DO NOT DELETE historical grants without authorization.
-- Migration requires existing quant_credential / quant_credential_user tables.
-- Application and ALL writers must be stopped; back up and restore-test first.
-- The following query must return ZERO rows before creating the unique index:
SELECT credential_id, COUNT(*) AS grant_rows,
       COUNT(DISTINCT user_id) AS distinct_users
FROM quant_credential_user
GROUP BY credential_id
HAVING COUNT(*) > 1;

-- Review grants that do not match the immutable owner. Must return ZERO:
SELECT g.credential_id, g.user_id, c.user_id AS owner_user_id
FROM quant_credential_user g
LEFT JOIN quant_credential c ON c.id=g.credential_id
WHERE c.id IS NULL OR g.user_id <> c.user_id;

-- Fail on duplicates: do not silently select an arbitrary authorized user.
ALTER TABLE quant_credential_user
 ADD UNIQUE KEY uq_credential_exactly_one_user (credential_id);

-- The unique index enforces AT MOST one grant per credential. The service
-- enforces a mandatory single matching grant in the same insert transaction.
-- Never run this blindly against an existing populated production database.
