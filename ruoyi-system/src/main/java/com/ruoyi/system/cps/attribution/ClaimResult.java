package com.ruoyi.system.cps.attribution;
import java.util.List;
public record ClaimResult(String code, String message, String maskedOrderNo, List<Long> orderLineIds) {}
