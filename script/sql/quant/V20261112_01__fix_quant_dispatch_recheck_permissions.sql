-- Keep the existing LIVE dispatch queue under the existing 交易对 page.
-- Both controls are sibling function permissions; no ordinary role is granted either one.
SET @quant_market_menu_id := (
    SELECT menu_id
    FROM sys_menu
    WHERE component = 'quant/market/index'
      AND menu_type = 'C'
      AND status = '0'
    ORDER BY menu_id DESC
    LIMIT 1
);

UPDATE sys_menu
SET parent_id = @quant_market_menu_id,
    menu_name = 'LIVE 外发订单查看',
    order_num = 91
WHERE perms = 'quant:dispatch:view'
  AND @quant_market_menu_id IS NOT NULL;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
    ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       'LIVE 外发订单查看', @quant_market_menu_id, 91, '', NULL, '',
       'N', 'Y', 'F', '1', '0', 'quant:dispatch:view', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL,
       '仅超级管理员查看 LIVE 外发订单待核查队列'
FROM DUAL
WHERE @quant_market_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:dispatch:view');

UPDATE sys_menu
SET parent_id = @quant_market_menu_id,
    menu_name = 'LIVE 外发订单人工核查',
    order_num = 92
WHERE perms = 'quant:dispatch:recover'
  AND @quant_market_menu_id IS NOT NULL;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param,
    is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
    ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       'LIVE 外发订单人工核查', @quant_market_menu_id, 92, '', NULL, '',
       'N', 'Y', 'F', '1', '0', 'quant:dispatch:recover', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL,
       '仅超级管理员核实 Binance 保护单事实并按原流程恢复，不提供强制解锁'
FROM DUAL
WHERE @quant_market_menu_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:dispatch:recover');

SELECT
    CASE
        WHEN @quant_market_menu_id IS NULL THEN 'ERROR: 未找到 quant/market/index 页面菜单，未创建 LIVE 外发订单权限'
        ELSE 'OK: LIVE 外发订单查看和人工核查权限已挂载到交易对页面'
    END AS migration_result;
