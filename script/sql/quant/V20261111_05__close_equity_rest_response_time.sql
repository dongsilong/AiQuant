-- Record when the REST account snapshot request started and when its response reached this node.
ALTER TABLE quant_trade_close_equity
    ADD COLUMN rest_query_started_at DATETIME(3) NULL COMMENT '平仓事件触发的 Binance REST 账户查询开始时间（UTC）' AFTER stream_received_at,
    ADD COLUMN rest_response_received_at DATETIME(3) NULL COMMENT 'Binance REST 账户查询响应到达应用节点的时间（UTC）' AFTER rest_query_started_at;
