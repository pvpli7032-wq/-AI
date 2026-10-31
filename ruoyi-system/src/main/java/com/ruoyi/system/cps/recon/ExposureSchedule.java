package com.ruoyi.system.cps.recon;

import java.time.LocalDateTime;

/** Locked together with snapshot inserts so failed captures do not advance the schedule. */
public record ExposureSchedule(int id, LocalDateTime lastCaptureAt) { }
