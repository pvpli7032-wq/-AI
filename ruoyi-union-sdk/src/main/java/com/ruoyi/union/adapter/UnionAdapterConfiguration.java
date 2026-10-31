package com.ruoyi.union.adapter;

import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Adapter selection settings. No platform implementation is supplied here. */
@Component
public class UnionAdapterConfiguration implements SmartInitializingSingleton {
    private List<UnionAdapter> adapters = List.of();
    private final Environment environment;

    public UnionAdapterConfiguration(Environment environment) {
        this.environment = environment;
        for (Platform platform : Platform.values()) {
            AdapterKind kind = kindFor(platform);
            if (kind == AdapterKind.MOCK && !mockAllowed()) {
                throw new IllegalStateException("Mock union adapters require an explicit dev or test profile and cannot run in production");
            }
        }
    }

    @Autowired(required = false)
    public void setAdapters(List<UnionAdapter> adapters) { this.adapters = adapters; }

    @Override
    public void afterSingletonsInstantiated() {
        for (Platform platform : Platform.values()) select(platform, adapters);
    }

    public AdapterKind kindFor(Platform platform) {
        String value = environment.getProperty("cps.union.adapter." + platform.name().toLowerCase(Locale.ROOT), "official");
        return AdapterKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** Resolve exactly one implementation; the same check runs at application startup. */
    public UnionAdapter select(Platform platform, List<UnionAdapter> adapters) {
        List<UnionAdapter> matches = adapters.stream()
            .filter(adapter -> adapter.platform() == platform && adapter.kind() == kindFor(platform)).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one configured adapter for " + platform + ", found " + matches.size());
        }
        return matches.get(0);
    }

    /** Mock is allowed only with dev or test, and never with prod/production. */
    private boolean mockAllowed() {
        String[] profiles = environment.getActiveProfiles();
        boolean nonProduction = Arrays.stream(profiles).anyMatch(p -> p.equalsIgnoreCase("dev") || p.equalsIgnoreCase("test"));
        boolean production = Arrays.stream(profiles).anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));
        return nonProduction && !production;
    }
}
