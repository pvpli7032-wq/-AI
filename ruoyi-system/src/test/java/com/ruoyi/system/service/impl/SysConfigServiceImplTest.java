package com.ruoyi.system.service.impl;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.mapper.SysConfigMapper;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class SysConfigServiceImplTest
{
    private SysConfigServiceImpl service;
    private SysConfigMapper mapper;
    private RedisCache cache;

    @Before
    public void setUp()
    {
        service = new SysConfigServiceImpl();
        mapper = mock(SysConfigMapper.class);
        cache = mock(RedisCache.class);
        ReflectionTestUtils.setField(service, "configMapper", mapper);
        ReflectionTestUtils.setField(service, "redisCache", cache);
    }

    @Test
    public void readsCachedValueWithoutQueryingMapper()
    {
        String key = CacheConstants.SYS_CONFIG_KEY + "test.config";
        when(cache.getCacheObject(key)).thenReturn("cached-value");

        assertEquals("cached-value", service.selectConfigByKey("test.config"));
        verifyNoInteractions(mapper);
    }

    @Test
    public void cachesValueReturnedByMapperOnCacheMiss()
    {
        SysConfig config = new SysConfig();
        config.setConfigValue("database-value");
        when(mapper.selectConfig(any(SysConfig.class))).thenReturn(config);

        assertEquals("database-value", service.selectConfigByKey("test.config"));
        verify(cache).setCacheObject(CacheConstants.SYS_CONFIG_KEY + "test.config", "database-value");
    }
}
