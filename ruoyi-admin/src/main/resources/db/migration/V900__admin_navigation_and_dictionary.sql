-- Menu tree for the new CPS administration. Existing system management IDs and links are untouched.
-- Only the dashboard is registered until other page commits are integrated; no dangling route is created.
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '数据看板',0,1,'cps-dashboard','','','',1,0,'M','0','0','','dashboard','admin',NOW(),'CPS新版后台'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_name='数据看板' AND parent_id=0);
SET @cps_root = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='数据看板' AND parent_id=0 LIMIT 1);

INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '运营看板',@cps_root,1,'overview','cps/dashboard/index','','CpsDashboard',1,0,'C','0','0','system:commission:dashboard:list','dashboard','admin',NOW(),'新版运营看板'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:commission:dashboard:list');
