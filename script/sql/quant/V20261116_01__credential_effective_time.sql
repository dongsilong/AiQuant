ALTER TABLE quant_credential
    ADD COLUMN effective_time DATETIME(3) NULL COMMENT '记录查询有效时间（UTC）' AFTER run_mode;
