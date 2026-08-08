package za.co.fnb.dcre.prw;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRW emits the PAYMENTS family only. The whole point of the split is that payments stops
 * sharing a writer with collections, so a flow discriminator in this service would mean
 * the split had not happened.
 *
 * <p>This is the PRR pattern, and it is applied here because CRW is where the
 * discriminator was WORST: CRW's due queries are a UNION of a DC arm and a pay arm, its
 * eligibility SQL branches on {@code h.flow = 'PAY'}, and {@code DueArms} exists purely to
 * decide which arm this database can resolve. That pay arm is the coupling the split
 * removes, so its residue is what this test hunts.
 *
 * <p>Per PRR review finding I2, a literal scan alone is not sufficient: scanning for N
 * literals is satisfied by deleting N literals. The companion structural assertions live
 * in {@link NoClockPathTest} (persisted shape, method signatures, launch surface); this
 * class covers the flow/arm vocabulary and the class-level absences a text scan can see
 * honestly.
 */
class PayFlowOnlyTest {

    private static List<String> offenders(final String... tokens) throws Exception {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String code = NoClockPathTest.codeOnly(p);
                        for (String token : tokens) {
                            if (code.contains(token)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .map(Path::toString)
                    .toList();
        }
    }

    @Test
    void prwCarriesNoFlowDiscriminator() throws Exception {
        assertThat(offenders("FLOW_PAY", "validatedFlow", "\"COL\"", "\"PAY\"", "flow ="))
                .as("PRW serves one family. dcre_pay's tx_header has no flow column at all,"
                        + " so a branch on flow here is collections logic carried across"
                        + " instead of left behind")
                .isEmpty();
    }

    /**
     * The ARM vocabulary. CRW composes its SQL from a DC arm and a pay arm at runtime
     * because one database served both families; PRW has one database, one family and one
     * query. A reintroduced {@code union(dcArm, payArm)} would be the coupling returning
     * under a different name.
     */
    @Test
    void prwComposesNoArms() throws Exception {
        assertThat(offenders("DueArms", "dcArm", "payArm", "UNION"))
                .as("there is exactly one eligibility query here; arm composition exists in"
                        + " CRW only to survive a two-family database")
                .isEmpty();
    }

    /**
     * The RESOURCES, which the java-only walk cannot reach. A flow column reintroduced by
     * a Liquibase changeset, or a flow key wired through application.yml, is exactly as
     * much of a discriminator as a Java constant.
     */
    @Test
    void noResourceReintroducesAFlowColumnOrKey() throws Exception {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/resources"))) {
            var offenders = paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String code = NoClockPathTest.codeOnly(p);
                        return code.contains("name=\"flow\"") || code.contains("columnName=\"flow\"")
                                || code.contains("flow:") || code.contains("'flow'");
                    })
                    .map(Path::toString)
                    .toList();
            assertThat(offenders)
                    .as("no changeset may add a flow column and no config may carry a flow"
                            + " key: dcre_pay's tx_header has no such column")
                    .isEmpty();
        }
    }

    /** Control: the walk reads real files, so the empty results above are absences. */
    @Test
    void theScanCanFindSomethingThatIsPresent() throws Exception {
        assertThat(offenders("pai_verdict"))
                .as("control: pai_verdict IS in the shipped SQL, so a non-empty result here"
                        + " proves the scan reads code rather than returning empty")
                .isNotEmpty();
    }
}
