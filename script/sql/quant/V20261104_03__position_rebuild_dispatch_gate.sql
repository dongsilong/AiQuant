-- V20261104_03: persist an account-symbol gate for isolated inventory rebuilds.
-- OPEN dispatches consult this gate. CLOSE dispatches invalidate the rebuild and
-- release the gate in the same transaction before the close order is dispatched.
ALTER TABLE quant_live_dispatch_guard
    ADD COLUMN rebuild_run_id bigint DEFAULT NULL COMMENT '正在执行的策略库存重建任务 ID' AFTER active_dispatch_key;
