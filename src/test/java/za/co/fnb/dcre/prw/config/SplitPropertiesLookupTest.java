package za.co.fnb.dcre.prw.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The override LOOKUP, with a value that differs from the default so the two are
 * distinguishable. Split from {@link SplitPropertiesBindingTest} on purpose: that test
 * proves the shipped yml binds under the declared prefix, this one proves
 * {@code maxFor} prefers an override over the default. One control each.
 *
 * <p>Plain context, no DB: only the properties binding is on trial, so the context is the
 * minimal EnableConfigurationProperties config.
 */
@SpringBootTest(classes = SplitPropertiesLookupTest.PropsConfig.class, properties = {
        "dcre.prw.split.max-size=5000",
        "dcre.prw.split.overrides.[FNBCC01]=10000"})
class SplitPropertiesLookupTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PrwSplitProperties.class)
    static class PropsConfig {
    }

    @Autowired
    PrwSplitProperties props;

    @Test
    void anOverrideWinsAndUnknownClientsInheritTheDefault() {
        assertThat(props.maxFor("FNBCC01")).isEqualTo(10000);   // override applied
        assertThat(props.maxFor("FNBRF01")).isEqualTo(5000);    // unknown: default (Sean ruling 12)
    }

    @Test
    void aMaxSizeBelowOneIsRejectedRatherThanSilentlyAccepted() {
        // The constructor guard is why a max-size prefix drift is LOUD while an overrides
        // drift is silent: an unbound int is 0 and 0 throws here.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PrwSplitProperties(0, java.util.Map.of()));
    }
}
