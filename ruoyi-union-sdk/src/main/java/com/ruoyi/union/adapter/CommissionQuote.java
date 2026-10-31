package com.ruoyi.union.adapter;

import java.math.BigDecimal;
public record CommissionQuote(BigDecimal rate, BigDecimal couponAmount, BigDecimal finalPrice) {}
