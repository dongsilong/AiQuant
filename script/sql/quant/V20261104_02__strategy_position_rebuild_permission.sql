-- 专用策略库存重建权限；不自动授予任何角色。
-- 应用前备份 sys_menu；仅当量化执行记录菜单存在时增加按钮权限。
START TRANSACTION;

INSERT INTO sys_menu (
  menu_id, menu_name, parent_id, order_num, path, component, query_param,
  is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
  ext, create_dept, create_by, create_time, update_by, update_time, remark
)
SELECT COALESCE((SELECT MAX(menu_id) FROM sys_menu), 0) + 1,
       '策略库存重建',
       (SELECT menu_id FROM sys_menu WHERE perms = 'quant:execution:list' LIMIT 1),
       91, '', NULL, '', 'N', 'Y', 'F', '1', '0', 'quant:allocation:rebuild', '#', '', '',
       NULL, NULL, NOW(), NULL, NULL, 'AiQuant 受控策略库存重建权限'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:execution:list')
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quant:allocation:rebuild');

COMMIT;

-- 本迁移不向 sys_role_menu 写入记录。请由管理员按最小权限原则单独授权。
