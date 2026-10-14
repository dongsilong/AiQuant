#!/usr/bin/env bash
set -euo pipefail
: "${MYSQL_CONTAINER:?MySQL disposable CI container required}"
mysql_ci() {
  docker exec "$MYSQL_CONTAINER" mysql -N -B -uroot -ponly_for_ci aiquant_ci_test -e "$1" | tr -d '\r'
}
account=987655
mysql_ci "INSERT IGNORE INTO quant_live_user_stream_lease (credential_id,owner_token,lease_epoch,lease_until)
 VALUES ($account,'node-a',1,DATE_ADD(NOW(6),INTERVAL 75 SECOND));"
# Another node cannot steal a non-expired lease.
rejected="$(mysql_ci "UPDATE quant_live_user_stream_lease
 SET owner_token='node-b',lease_epoch=lease_epoch+1,lease_until=DATE_ADD(NOW(6),INTERVAL 75 SECOND)
 WHERE credential_id=$account AND (owner_token='node-b' OR lease_until<=NOW(6));
 SELECT ROW_COUNT();")"
test "$rejected" = 0
# Simulate node A dying: lease is left behind and expires.
mysql_ci "UPDATE quant_live_user_stream_lease SET lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND)
 WHERE credential_id=$account AND owner_token='node-a';"
claimed="$(mysql_ci "UPDATE quant_live_user_stream_lease SET
 lease_epoch=IF(owner_token<>'node-b' OR lease_until<=NOW(6),lease_epoch+1,lease_epoch),
 owner_token='node-b',lease_until=DATE_ADD(NOW(6),INTERVAL 75 SECOND)
 WHERE credential_id=$account AND (owner_token='node-b' OR lease_until<=NOW(6));
 SELECT ROW_COUNT();")"
test "$claimed" = 1
current="$(mysql_ci "SELECT CONCAT(owner_token,':',lease_epoch)
 FROM quant_live_user_stream_lease WHERE credential_id=$account")"
test "$current" = "node-b:2"
# Paused stale owner A cannot reclaim or release node B's active lease.
stale="$(mysql_ci "UPDATE quant_live_user_stream_lease SET lease_until=DATE_ADD(NOW(6),INTERVAL 75 SECOND)
 WHERE credential_id=$account AND (owner_token='node-a' OR lease_until<=NOW(6));
 SELECT ROW_COUNT();")"
test "$stale" = 0
mysql_ci "DELETE FROM quant_live_user_stream_lease WHERE credential_id=$account;"
echo "MySQL owner takeover and stale-node exclusion verified"
