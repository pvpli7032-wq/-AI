-- 用户 + 业务类型 + 订单佣金记录ID 是现有统一入账入口的业务唯一键。
-- 旧流水原样保留；已有重复业务编号时迁移应失败并交由人工核对，不能删除或改写流水。
-- 历史上未记录订单佣金ID的流水保留NULL；新的统一入账入口强制提供业务编号。
ALTER TABLE sys_commission_flow
    ADD UNIQUE KEY uk_commission_flow_business (user_id, flow_type, order_commission_id);
