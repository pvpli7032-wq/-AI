-- CPS settings, union, member and balance-flow pages.
SET @cps_function = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='功能管理' AND parent_id=0 LIMIT 1);
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '功能管理',0,2,'cps-functions','','','',1,0,'M','0','0','','component','admin',NOW(),'CPS功能设置'
WHERE @cps_function IS NULL;
SET @cps_function = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='功能管理' AND parent_id=0 LIMIT 1);
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '系统设置',@cps_function,1,'settings','cps/settings/index','','CpsSettings',1,0,'C','0','0','system:config:list','edit','admin',NOW(),'查券与结算设置'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:config:list' AND component='cps/settings/index');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '导购查券',@cps_function,2,'coupon-settings','cps/coupon/index','','CpsCouponSettings',1,0,'C','0','0','system:config:query','search','admin',NOW(),'查券设置'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='cps/coupon/index');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '返利设置',@cps_function,3,'rules','cps/rules/index','','CpsRules',1,0,'C','0','0','system:commission:rule:list','money','admin',NOW(),'版本化返利规则'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='cps/rules/index');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '订阅消息',@cps_function,4,'subscription','cps/subscription/index','','CpsSubscription',1,0,'C','0','0','system:commission:subscription:list','message','admin',NOW(),'订阅消息接入状态'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='cps/subscription/index');

SET @cps_mini = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='小程序' AND parent_id=0 LIMIT 1);
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '小程序',0,3,'cps-miniapp','','','',1,0,'M','0','0','','wechat','admin',NOW(),'小程序配置'
WHERE @cps_mini IS NULL;
SET @cps_mini = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='小程序' AND parent_id=0 LIMIT 1);
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '基础设置',@cps_mini,1,'base','cps/miniapp/index','','CpsMiniappSettings',1,0,'C','0','0','system:config:list','edit','admin',NOW(),'小程序基础配置'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='cps/miniapp/index');

SET @cps_union = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='联盟管理' AND parent_id=0 LIMIT 1);
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '联盟管理',0,4,'cps-union','','','',1,0,'M','0','0','','link','admin',NOW(),'联盟授权及PID'
WHERE @cps_union IS NULL;
SET @cps_union = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='联盟管理' AND parent_id=0 LIMIT 1);
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '联盟账号与 PID 池',@cps_union,1,'accounts','cps/union/index','tab=accounts','CpsUnionAccounts',1,0,'C','0','0','system:unionPlatform:list','link','admin',NOW(),'平台授权和PID管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='cps/union/index' AND query='tab=accounts');

SET @cps_users = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='用户管理' AND parent_id=0 LIMIT 1);
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '用户管理',0,5,'cps-users','','','',1,0,'M','0','0','','peoples','admin',NOW(),'小程序会员'
WHERE @cps_users IS NULL;
SET @cps_users = (SELECT menu_id FROM sys_menu WHERE menu_type='M' AND menu_name='用户管理' AND parent_id=0 LIMIT 1);
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '会员列表',@cps_users,1,'members','cps/members/index','','CpsMembers',1,0,'C','0','0','system:commission:user:list','user','admin',NOW(),'小程序会员只读列表'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='cps/members/index');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '余额记录',@cps_users,2,'flows','cps/flows/index','','CpsFlows',1,0,'C','0','0','system:commission:flow:list','money','admin',NOW(),'真实佣金流水查询'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='cps/flows/index');
