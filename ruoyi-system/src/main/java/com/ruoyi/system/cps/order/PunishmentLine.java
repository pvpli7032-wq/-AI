package com.ruoyi.system.cps.order;

import java.math.BigDecimal;

public class PunishmentLine {
    private String orderNo;
    private String subOrderNo;
    private BigDecimal penaltyAmount;
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getSubOrderNo() { return subOrderNo; }
    public void setSubOrderNo(String subOrderNo) { this.subOrderNo = subOrderNo; }
    public BigDecimal getPenaltyAmount() { return penaltyAmount; }
    public void setPenaltyAmount(BigDecimal penaltyAmount) { this.penaltyAmount = penaltyAmount; }
}
