package za.co.fnb.dcre.prw.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE CONFIG-PREFIX RED-PROOF. This estate has shipped the same defect three times, and it
 * is worth stating precisely because it is invisible by construction.
 *
 * <p>When a service is forked or renamed, the {@code @ConfigurationProperties} prefix moves
 * with the Java package while the key path in application.yml is easy to leave behind (here:
 * {@code dcre.crw.split} to {@code dcre.prw.split}). Boot does not fail on an unmatched
 * prefix. It binds an EMPTY overrides map, every client silently falls through to the
 * constant default, the context starts, the suite passes and the build exits 0. The only
 * symptom is a per-client split size that quietly stopped applying, months later, in
 * production.
 *
 * <p>A test asserting {@code overrides != null} does not catch it: the compact constructor
 * substitutes {@code Map.of()} for null, so null is unreachable and the assertion is true
 * whether or not binding worked. The map must be asserted POPULATED, and it must be
 * populated FROM THE SHIPPED application.yml rather than from inline test properties,
 * because inline properties are written against whatever prefix the annotation currently
 * declares and therefore agree with it by construction.
 *
 * <p>Note that {@code max-size} is NOT the dangerous key. An unbound int is 0, and the
 * compact constructor throws on {@code maxSize < 1}, so a prefix drift fails that one
 * loudly. The map is the silent half, which is why it carries a committed entry.
 */
@SpringBootTest(classes = SplitPropertiesBindingTest.PropsConfig.class)
class SplitPropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PrwSplitProperties.class)
    static class PropsConfig {
    }

    @Autowired
    PrwSplitProperties props;

    /**
     * The load-bearing assertion. This context boots against the real application.yml with
     * no inline property overrides at all, so everything below came through the declared
     * prefix.
     */
    @Test
    void theOverridesMapIsPopulatedFromTheShippedYml() {
        assertThat(props.overrides())
                .as("an EMPTY overrides map is what a prefix/yml drift looks like: it binds"
                        + " clean, drops every client to the constant default and exits 0."
                        + " Populated is the only proof the prefix actually matched")
                .isNotEmpty()
                .containsKey("FNBRF01");

        assertThat(props.maxSize())
                .as("max-size bound from the shipped yml, not a constant default")
                .isEqualTo(5000);
    }

    /**
     * Ties the annotation's prefix to the key path that actually exists in the shipped yml,
     * mechanically. The test reads the prefix from {@link PrwSplitProperties#PREFIX}, which
     * is the SAME string the annotation uses, so this cannot degrade into two literals
     * agreeing with each other while the annotation drifts away from both.
     */
    @Test
    void theDeclaredPrefixMatchesTheKeyPathInTheShippedYml() throws Exception {
        String annotated = PrwSplitProperties.class
                .getAnnotation(ConfigurationProperties.class).prefix();
        assertThat(annotated)
                .as("the annotation must use the constant, or the constant is decoration")
                .isEqualTo(PrwSplitProperties.PREFIX);

        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        // dcre.prw.split -> the nested block "dcre:\n  prw:\n    split:"
        String[] segments = annotated.split("\\.");
        int indent = 0;
        for (String segment : segments) {
            String expected = " ".repeat(indent) + segment + ":";
            assertThat(yml.lines().anyMatch(l -> l.equals(expected)))
                    .as("application.yml must carry '%s' at indent %d, because the Java"
                            + " prefix is '%s'. A prefix that names a path the yml does not"
                            + " have binds nothing and says nothing", expected, indent, annotated)
                    .isTrue();
            indent += 2;
        }
        // The keys themselves, at the depth the prefix implies.
        assertThat(yml).contains(" ".repeat(indent) + "max-size:");
        assertThat(yml).contains(" ".repeat(indent) + "overrides:");
    }

    /**
     * The committed override deliberately equals the default, so shipping it changes no
     * behaviour for FNBRF01: its only job is to give the map an entry, because an EMPTY
     * map is indistinguishable from a broken prefix. The override LOOKUP is a separate
     * concern and is tested separately in {@link SplitPropertiesLookupTest}, so neither
     * test passes for two reasons at once (verification.md rule 5).
     */
    @Test
    void theCommittedOverrideIsBehaviourNeutralAndPresentPurelyAsTheBindingWitness() {
        assertThat(props.overrides()).containsEntry("FNBRF01", 5000);
        assertThat(props.maxFor("FNBRF01")).isEqualTo(props.maxSize());
        assertThat(props.maxFor("NOSUCHCLIENT")).isEqualTo(5000);
    }
}
