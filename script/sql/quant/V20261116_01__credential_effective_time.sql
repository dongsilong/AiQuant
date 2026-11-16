ALTER TABLE quant_credential
    ADD COLUMN effective_time DATETIME(3) NULL COMMENT '记录查询有效时间（UTC）' AFTER run_mode;

ALTER TABLE quant_order
    ADD COLUMN exchange_created_at DATETIME(3) NULL COMMENT '交易所订单创建时间（UTC）' AFTER updated_at;
