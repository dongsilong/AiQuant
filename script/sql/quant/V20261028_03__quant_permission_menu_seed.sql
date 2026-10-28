-- AiQuant 量化模块权限菜单增量迁移
-- 仅补齐 sys_menu 中缺失的量化按钮权限节点，不自动授予任何角色权限。
-- 仅当对应量化页面菜单已存在时才插入；重复执行安全。
-- 应用前先备份 sys_menu，并在测试库验证页面菜单 parent_id 关系。
START TRANSACTION;

-- 量化策略-新增
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '量化策略-新增',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:strategy:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:strategy:add', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:strategy:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:strategy:add');

-- 量化策略-修改
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '量化策略-修改',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:strategy:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:strategy:edit', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:strategy:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:strategy:edit');

-- 量化策略-删除
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '量化策略-删除',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:strategy:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:strategy:remove', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:strategy:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:strategy:remove');

-- 交易凭证-新增
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易凭证-新增',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:credential:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:credential:add', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:credential:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:credential:add');

-- 交易凭证-修改
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易凭证-修改',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:credential:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:credential:edit', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:credential:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:credential:edit');

-- 交易凭证-删除
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易凭证-删除',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:credential:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:credential:remove', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:credential:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:credential:remove');

-- 交易凭证-测试
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易凭证-测试',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:credential:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:credential:test', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:credential:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:credential:test');

-- 风控配置-新增
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '风控配置-新增',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:risk:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:risk:add', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:risk:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:risk:add');

-- 风控配置-修改
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '风控配置-修改',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:risk:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:risk:edit', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:risk:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:risk:edit');

-- 风控配置-删除
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '风控配置-删除',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:risk:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:risk:remove', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:risk:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:risk:remove');

-- 交易对-新增
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易对-新增',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:symbol:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:symbol:add', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:symbol:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:symbol:add');

-- 交易对-修改及收藏
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易对-修改及收藏',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:symbol:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:symbol:edit', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:symbol:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:symbol:edit');

-- 交易对-删除
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易对-删除',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:symbol:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:symbol:remove', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:symbol:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:symbol:remove');

-- 交易对-同步
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易对-同步',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:symbol:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:symbol:sync', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:symbol:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:symbol:sync');

-- 成交记录-同步
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '成交记录-同步',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:trade:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:trade:sync', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:trade:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:trade:sync');

-- 订单-撤单
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '订单-撤单',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:order:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:order:cancel', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:order:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:order:cancel');

-- 持仓-平仓
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '持仓-平仓',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:position:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:position:close', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:position:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:position:close');

-- 交易终端-平仓
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易终端-平仓',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:terminal:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:position:close', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:terminal:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:position:close');

-- 交易终端-撤单
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '交易终端-撤单',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:terminal:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:order:cancel', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:terminal:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:order:cancel');

-- 行情传输-切换
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '行情传输-切换',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:terminal:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:transport:switch', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:terminal:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:transport:switch');

-- 成交归属-查看
INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '成交归属-查看',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:execution:list' LIMIT 1),
       90, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:allocation:view', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 量化模块权限按钮'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:execution:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:allocation:view');

COMMIT;

-- 注意：本迁移不向 sys_role_menu 写入记录。
-- 管理员应在角色管理中按最小权限原则为角色勾选所需按钮权限。
