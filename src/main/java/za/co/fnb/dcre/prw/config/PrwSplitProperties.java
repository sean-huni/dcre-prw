package za.co.fnb.dcre.prw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * SCRUM-55: max tx per outbound pain.008. Unknown clients inherit the default (Sean ruling
 * 12); a JobLauncher program arg {@code --dcre.prw.split.max-size=N} wins by standard
 * Spring property precedence. INTERIM home until the R-14 client reference table
 * materializes (R-39).
 *
 * <p>THE PREFIX IS LOAD-BEARING AND IS TESTED AS SUCH. Renaming a service moves this
 * prefix, and moving the Java prefix while leaving the yml key behind binds an EMPTY
 * overrides map: every client silently falls to the constant default and the build still
 * exits 0. That has shipped three times in this estate.
 * {@code SplitPropertiesBindingTest} asserts the prefix declared here matches the key path
 * in the SHIPPED application.yml, and that the bound map is POPULATED rather than merely
 * non-null.
 */
@ConfigurationProperties(prefix = PrwSplitProperties.PREFIX)
public record PrwSplitProperties(int maxSize, Map<String, Integer> overrides) {

    /**
     * Declared as a constant so the binding test can assert against the SAME string the
     * annotation uses. A test that repeats the literal proves only that two literals
     * agree, and the copy is the one that stays right while the annotation drifts.
     */
    public static final String PREFIX = "dcre.prw.split";

    public PrwSplitProperties {
        overrides = overrides == null ? Map.of() : overrides;
        if (maxSize < 1) {
            throw new IllegalArgumentException(PREFIX + ".max-size must be >= 1");
        }
    }

    public int maxFor(final String client) {
        return overrides.getOrDefault(client, maxSize);
    }
}
