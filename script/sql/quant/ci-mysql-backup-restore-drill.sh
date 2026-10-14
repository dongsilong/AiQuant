#!/usr/bin/env bash
# Disaster-recovery rehearsal on a DISPOSABLE GitHub Actions MySQL 8.4 service.
# NEVER run this against an actual customer/production database.
set -Eeuo pipefail
: "${MYSQL_CONTAINER:?Provide disposable MySQL container ID}"
: "${MYSQL_CI_PASSWORD:?Provide disposable CI MySQL password}"
DB="aiquant_ci_test"
RESTORE_DB="aiquant_ci_restore_drill"
if [[ "${AIQUANT_DISPOSABLE_CI:-}" != "YES_I_CONFIRM_DISPOSABLE" ]]; then
  echo "Refusing recovery drill without explicit disposable CI scope" >&2
  exit 2
fi
if ! docker inspect "$MYSQL_CONTAINER" --format '{{.Config.Image}}' | grep -Fxq 'mysql:8.4'; then
  echo "Refusing non-MySQL-8.4 container" >&2
  exit 2
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mysql_ci() {
  docker exec -i -e MYSQL_PWD="$MYSQL_CI_PASSWORD" "$MYSQL_CONTAINER" mysql -uroot "$@"
}
dump_ci() {
  docker exec -i -e MYSQL_PWD="$MYSQL_CI_PASSWORD" "$MYSQL_CONTAINER" mysqldump -uroot \
    --single-transaction --quick --triggers --routines --set-gtid-purged=OFF "$DB"
}
echo "Creating known, unresolved Binance dispatch facts in disposable database"
mysql_ci "$DB" <<'SQL'
INSERT INTO quant_live_dispatch_guard
 (credential_id,symbol,active_dispatch_key)
 VALUES (930001,'BTCUSDT','ci-unknown-dispatch');
INSERT INTO quant_order_dispatch_ticket
 (credential_id,symbol,idempotency_key,operation_type,client_order_id,
  strategy_instance_id,lease_epoch,dispatch_status)
 VALUES (930001,'BTCUSDT','ci-unknown-dispatch','OPEN','ci-client-1',81001,41,'UNKNOWN');
INSERT INTO quant_strategy_pre_dispatch_intent
 (credential_id,symbol,idempotency_key,client_order_id,strategy_id,
  strategy_instance_id,operation_type,position_side,exchange_side,lease_epoch)
 VALUES (930001,'BTCUSDT','ci-unknown-dispatch','ci-client-1',71001,
         81001,'OPEN','LONG','BUY',41);
INSERT INTO quant_live_recovery_case
 (case_id,credential_id,symbol,dispatch_key,operation_type,requested_by,
  evidence_sha256,expected_client_order_id,state)
 VALUES (85001,930001,'BTCUSDT','ci-unknown-dispatch','OPEN',2001,
         REPEAT('a',64),'ci-client-1','PENDING');
INSERT INTO quant_live_recovery_audit
 (audit_id,case_id,actor_id,action,evidence_sha256,detail_hash)
 VALUES (86001,85001,2001,'REQUEST',REPEAT('a',64),REPEAT('b',64));
SQL
# Snapshot SQL dump is the disaster recovery artifact. An uncompressed file is
# only kept in CI temporary storage; never upload database secrets/artifacts.
dump_ci > "$tmp/backup.sql"
test -s "$tmp/backup.sql"
sha256sum "$tmp/backup.sql" > "$tmp/backup.sql.sha256"
(cd "$tmp" && sha256sum -c backup.sql.sha256)
# Validate the backup's logical facts BEFORE destroying the disposable clone.
facts_sql="SELECT CONCAT_WS('|',g.active_dispatch_key,t.dispatch_status,p.lease_epoch,p.strategy_id)
FROM quant_live_dispatch_guard g
JOIN quant_order_dispatch_ticket t ON t.credential_id=g.credential_id
 AND t.symbol=g.symbol AND t.idempotency_key=g.active_dispatch_key
JOIN quant_strategy_pre_dispatch_intent p ON p.credential_id=t.credential_id
 AND p.idempotency_key=t.idempotency_key
WHERE g.credential_id=930001 AND g.symbol='BTCUSDT';"
before="$(mysql_ci -N -B "$DB" -e "$facts_sql" | tr -d '\r')"
test "$before" = 'ci-unknown-dispatch|UNKNOWN|41|71001'
# Restoration occurs into an independently named, disposable schema;
# no production data is overwritten and the original source remains untouched.
mysql_ci -e "CREATE DATABASE \`$RESTORE_DB\`;"
mysql_ci "$RESTORE_DB" < "$tmp/backup.sql"
after="$(mysql_ci -N -B "$RESTORE_DB" -e "$facts_sql" | tr -d '\r')"
test "$after" = "$before"
# Every restored audit event must remain protected by immutable UPDATE/DELETE triggers.
audit_count="$(mysql_ci -N -B "$RESTORE_DB" -e "SELECT COUNT(*) FROM quant_live_recovery_audit WHERE audit_id=86001;" | tr -d '\r')"
test "$audit_count" = 1
if mysql_ci "$RESTORE_DB" -e "UPDATE quant_live_recovery_audit SET actor_id=7777 WHERE audit_id=86001;" >/dev/null 2>&1; then
  echo "FAIL: restored audit UPDATE unexpectedly allowed" >&2
  exit 1
fi
if mysql_ci "$RESTORE_DB" -e "DELETE FROM quant_live_recovery_audit WHERE audit_id=86001;" >/dev/null 2>&1; then
  echo "FAIL: restored audit DELETE unexpectedly allowed" >&2
  exit 1
fi
# Both PK/foreign key and unique idempotency invariants must survive restore.
fk_count="$(mysql_ci -N -B -e "
 SELECT COUNT(*) FROM information_schema.referential_constraints
 WHERE constraint_schema='$RESTORE_DB' AND constraint_name='fk_pre_dispatch_ticket';" | tr -d '\r')"
test "$fk_count" = 1
if mysql_ci "$RESTORE_DB" -e "
 INSERT INTO quant_strategy_pre_dispatch_intent
 (credential_id,symbol,idempotency_key,client_order_id,strategy_id,
  strategy_instance_id,operation_type,position_side,exchange_side,lease_epoch)
 VALUES (930001,'BTCUSDT','ci-unknown-dispatch','ci-client-1',
         71001,81001,'OPEN','LONG','BUY',41);" >/dev/null 2>&1; then
  echo "FAIL: restored unique pre-dispatch intent did not reject duplication" >&2
  exit 1
fi
if mysql_ci "$RESTORE_DB" -e "
 INSERT INTO quant_strategy_pre_dispatch_intent
 (credential_id,symbol,idempotency_key,client_order_id,strategy_id,
  strategy_instance_id,operation_type,position_side,exchange_side,lease_epoch)
 VALUES (930001,'BTCUSDT','missing-ticket','missing-client',
         71001,81001,'OPEN','LONG','BUY',41);" >/dev/null 2>&1; then
  echo "FAIL: restored FK did not reject orphaned pre-dispatch intent" >&2
  exit 1
fi
# Simulate loss of the test schema and validate full restore *again*.
mysql_ci -e "DROP DATABASE \`$RESTORE_DB\`; CREATE DATABASE \`$RESTORE_DB\`;"
mysql_ci "$RESTORE_DB" < "$tmp/backup.sql"
recovered="$(mysql_ci -N -B "$RESTORE_DB" -e "$facts_sql" | tr -d '\r')"
test "$recovered" = "$before"
echo "PASS: CI MySQL 8.4 verified backup checksum, two restores, UNKNOWN block, and FK/unique constraints"
