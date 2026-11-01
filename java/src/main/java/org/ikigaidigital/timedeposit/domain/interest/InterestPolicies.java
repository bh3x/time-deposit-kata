package org.ikigaidigital.timedeposit.domain.interest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Maps a plan type to its {@link InterestPolicy} (ADR-0011, E1: Registry). Plan types are matched exactly and
 * case-sensitively; unknown ones get {@link NoInterestPolicy}. Instances are immutable and safe to share between
 * threads, as the calculator bean is a singleton.
 *
 * <p>A builder rather than a map factory: "today's plans plus one" reads as one expression
 * ({@code defaults().toBuilder().register(...).build()}), and each registration is checked where it is made.
 */
public final class InterestPolicies {

    /** "No interest for the first 30 days" — the brief's rule for every existing plan (ADR-0011, C2). */
    public static final int DEFAULT_GRACE_PERIOD_DAYS = 30;

    // Known issue #3: the legacy code ignores a null plan type within the grace period and throws
    // NullPointerException after it. Kept here, not in the calculator; checked before any map lookup, because
    // Map.copyOf maps throw on get(null).
    private static final InterestPolicy NULL_PLAN_TYPE_POLICY = terms -> {
        if (terms.getDays() <= DEFAULT_GRACE_PERIOD_DAYS) {
            return 0.0;
        }
        throw new NullPointerException("planType");
    };

    private final Map<String, InterestPolicy> policiesByPlanType;

    private InterestPolicies(Map<String, InterestPolicy> policiesByPlanType) {
        this.policiesByPlanType = Map.copyOf(policiesByPlanType);
    }

    /**
     * Today's plans. "No interest after 1 year" is the legacy {@code days < 366}, so day 365 still earns; other readings
     * (days &lt;= 365, a calendar year, twelve monthly applications) are not used: behaviour must stay identical.
     */
    public static InterestPolicies defaults() {
        return builder()
                .register("basic", new RateWindowPolicy(0.01, DEFAULT_GRACE_PERIOD_DAYS,
                        OptionalInt.empty(), OptionalInt.empty()))
                .register("student", new RateWindowPolicy(0.03, DEFAULT_GRACE_PERIOD_DAYS,
                        OptionalInt.empty(), OptionalInt.of(366)))
                .register("premium", new RateWindowPolicy(0.05, DEFAULT_GRACE_PERIOD_DAYS,
                        OptionalInt.of(45), OptionalInt.empty()))
                .build();
    }

    public static Builder builder() {
        return new Builder(Map.of());
    }

    /** A builder that starts with this registry's plans; this registry itself does not change. */
    public Builder toBuilder() {
        return new Builder(policiesByPlanType);
    }

    public InterestPolicy policyFor(String planType) {
        if (planType == null) {
            return NULL_PLAN_TYPE_POLICY;
        }
        return policiesByPlanType.getOrDefault(planType, NoInterestPolicy.INSTANCE);
    }

    public static final class Builder {

        private final Map<String, InterestPolicy> policiesByPlanType;

        private Builder(Map<String, InterestPolicy> policiesByPlanType) {
            this.policiesByPlanType = new LinkedHashMap<>(policiesByPlanType);
        }

        /**
         * Registers a plan type exactly as given (no trimming or case folding, like the matching). Rejects a
         * {@code null} plan type or policy with {@link NullPointerException}, and a blank or already registered plan
         * type with {@link IllegalArgumentException}: either would make the registry ambiguous.
         */
        public Builder register(String planType, InterestPolicy policy) {
            Objects.requireNonNull(planType, "planType");
            Objects.requireNonNull(policy, "policy");
            if (planType.isBlank()) {
                throw new IllegalArgumentException("planType must not be blank");
            }
            if (policiesByPlanType.putIfAbsent(planType, policy) != null) {
                throw new IllegalArgumentException("planType already registered: " + planType);
            }
            return this;
        }

        public InterestPolicies build() {
            return new InterestPolicies(policiesByPlanType);
        }
    }
}
