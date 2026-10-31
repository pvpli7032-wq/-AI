package com.ruoyi.system.cps.withdraw;

import java.util.Arrays;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches the union adapter's explicit non-production mock whitelist. */
public class MockWithdrawalProfileCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // Default profiles are deliberately excluded: mock payments must be explicitly enabled.
        String[] profiles = context.getEnvironment().getActiveProfiles();
        boolean nonProduction = Arrays.stream(profiles)
            .anyMatch(profile -> profile.equalsIgnoreCase("dev") || profile.equalsIgnoreCase("test"));
        boolean production = Arrays.stream(profiles)
            .anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
        return nonProduction && !production;
    }
}
