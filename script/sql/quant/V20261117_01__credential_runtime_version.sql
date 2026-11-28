ALTER TABLE quant_credential
    ADD COLUMN credential_runtime_version BIGINT NOT NULL DEFAULT 1 COMMENT '凭证 Runtime 生命周期版本，交易身份或启停状态变更时递增' AFTER update_time;
