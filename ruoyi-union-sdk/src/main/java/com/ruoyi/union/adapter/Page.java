package com.ruoyi.union.adapter;

import java.util.List;
/** Cursor is opaque to consumers; null nextCursor indicates the final page. */
public record Page<T>(List<T> items, String nextCursor) {}
