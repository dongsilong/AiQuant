-- Persist the trigger price basis with each protection intent.
-- Existing rows remain NULL and are retried with the legacy CONTRACT_PRICE basis.
ALTER TABLE quant_protection_order
    ADD COLUMN working_type VARCHAR(16) NULL COMMENT '条件单触发价格类型：MARK_PRICE/CONTRACT_PRICE' AFTER order_type;
