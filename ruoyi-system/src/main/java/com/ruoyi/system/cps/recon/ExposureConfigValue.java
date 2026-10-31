package com.ruoyi.system.cps.recon;

/** Raw values remain visible to validation, including duplicate or blank configuration. */
public record ExposureConfigValue(String key, String value) { }
