-- Apply ONLY after V05 is installed on a verified MySQL 8.4 staging clone.
-- Guard application mistakes; privileged DB administrators can still bypass
-- triggers, so this is NOT a substitute for external WORM evidence.
DELIMITER //
CREATE TRIGGER trg_quant_recovery_audit_no_update
BEFORE UPDATE ON quant_live_recovery_audit
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Recovery audit records are immutable';
END//
CREATE TRIGGER trg_quant_recovery_audit_no_delete
BEFORE DELETE ON quant_live_recovery_audit
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Recovery audit records cannot be deleted';
END//
DELIMITER ;

-- Deployment DBA must separately verify:
-- 1. Application DB principal has INSERT/SELECT, not UPDATE/DELETE/ALTER/DROP,
--    on quant_live_recovery_audit; no TRIGGER privilege.
-- 2. Audit exports go to externally managed write-once retention with separate
--    credentials and retention lock. A DBA can otherwise DROP these triggers.
-- 3. Restore and tamper-attempt drills against a disposable MySQL clone.
