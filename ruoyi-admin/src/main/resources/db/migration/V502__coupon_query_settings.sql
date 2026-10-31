-- Anonymous browsing is recorded without inventing a registered user ID.
ALTER TABLE cps_query_log MODIFY COLUMN user_id BIGINT NULL,
    ADD KEY idx_query_item_time(platform,item_id,created_at);

-- Operational defaults and editable copy; no rebate ratios or real promotion credentials.
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark) VALUES
('查券缓存秒数','cps.coupon.cache-seconds','60','Y','system',NOW(),'Only public product facts; never cache another user promotion link'),
('查券最小佣金','cps.coupon.minimum-commission','0','Y','system',NOW(),'0 disables the low-commission threshold; zero commission remains ineligible'),
('无佣金提示','cps.coupon.message.no-commission','该商品暂无佣金，请选择其他商品或稍后重试。','Y','system',NOW(),''),
('无佣金提示开关','cps.coupon.show-no-commission','true','Y','system',NOW(),''),
('低佣金提示','cps.coupon.message.low-commission','该商品佣金低于当前参与门槛。','Y','system',NOW(),''),
('拼多多比价提示','cps.coupon.message.pdd-comparison','拼多多比价订单可能无法获得佣金，具体以平台订单结果为准。','Y','system',NOW(),''),
('拼多多比价提示开关','cps.coupon.show-pdd-comparison','true','Y','system',NOW(),''),
('下单绑定提示分钟','cps.coupon.binding-notice-minutes','','Y','system',NOW(),'Set the agreed notification delay before enabling this copy; not a claim deadline'),
('购物红包提示','cps.coupon.message.redpacket','请勿使用其他渠道的购物红包或礼金。','Y','system',NOW(),''),
('购物车收藏提示','cps.coupon.message.old-cart','购物车和收藏中的旧商品请先删除，再通过本次链接下单。','Y','system',NOW(),''),
('取消维权提示','cps.coupon.message.refund','订单取消、退款或维权可能导致返利失效。','Y','system',NOW(),''),
('京东联盟账号ID','cps.coupon.jd-union-id','','Y','system',NOW(),'Set by the operator before enabling JD conversions');
