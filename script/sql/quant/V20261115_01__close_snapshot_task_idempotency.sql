-- Enforce one persistent close-snapshot task per credential and exchange order.
-- Preflight: this query must return no rows before applying the unique index.
-- SELECT credential_id, recipient, COUNT(*) AS task_count
-- FROM quant_notification
-- WHERE channel = 'FACT_PENDING' AND recipient LIKE 'SNAPSHOT:%'
-- GROUP BY credential_id, recipient
-- HAVING COUNT(*) > 1;
--
-- Existing duplicate rows are intentionally not deleted automatically.
-- Resolve any preflight duplicates before applying this migration.
ALTER TABLE quant_notification
    ADD COLUMN snapshot_task_identity VARCHAR(128)
        GENERATED ALWAYS AS (
            CASE
                WHEN channel = 'FACT_PENDING' AND recipient LIKE 'SNAPSHOT:%'
                THEN CONCAT(CAST(credential_id AS CHAR), ':', recipient)
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_quant_notification_snapshot_task (snapshot_task_identity);
