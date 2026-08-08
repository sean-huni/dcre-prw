package za.co.fnb.dcre.prw;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.prw.data.model.PrwEmissionEntity;
import za.co.fnb.dcre.prw.data.model.PrwEmissionGroupEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE STRUCTURAL GUARD OF THIS SERVICE. PRW was forked from CRW, and the fork's whole
 * purpose was to REMOVE a mechanism rather than rename one: CRW is clock-driven because
 * collections WAREHOUSES, and payments does not warehouse.
 *
 * <p>Removing a mechanism has a failure mode that renaming does not. A rename is loud when
 * it is incomplete: something fails to compile or fails to bind. A removal that is 90%
 * done compiles perfectly, passes every behavioural test on fixtures that never exercise
 * the residue, and reintroduces the whole concept the first time somebody "restores" the
 * missing predicate to make a query look symmetrical with CRW's. So the absence is
 * asserted directly, and it is asserted four different ways because no single way is
 * sufficient.
 *
 * <p>Why a literal scan alone is NOT enough (PRR review finding I2, inherited): a scan for
 * N string literals is satisfied by DELETING those N literals, not by removing the
 * concept. Hence the reflection checks on the persisted shape, the bean-surface check on
 * the job parameters, and a control assertion on every scan so an empty result is
 * distinguishable from a scan that read nothing (verification.md rule 11).
 */
class NoClockPathTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    /**
     * Every token that carries the warehousing/clock concept, mapped to what its presence
     * would mean. The message is part of the assertion: a bare "found a match" tells the
     * next reader nothing about why it is forbidden.
     */
    private static final Map<String, String> FORBIDDEN = Map.of(
            "process_date", "the collection-day predicate. Payments has no collection day:"
                    + " a payment is processed immediately, so there is no date to compare against",
            "cde_schedule", "CDE's warehouse table. There is no CDE on the payments sheet"
                    + " and no cde_schedule row in dcre_pay",
            "run_date", "the warehousing column. It exists in CRW so a futured remainder"
                    + " re-emits as a distinct artifact on a later date; a payments arrival"
                    + " emits once, so a run date in the business key is an identity"
                    + " dimension that varies for non-business reasons",
            "runDate", "the Java face of run_date, same reason",
            "@Scheduled", "a clock trigger. PRW is launched by AGT as a DAG stage after PAI,"
                    + " never by its own timer",
            "EnableScheduling", "a clock trigger, same reason",
            "run.date", "the clock job parameter. PRW's identifying parameter is arrival.id (R-16)",
            "ais_verdict", "AIS's verdict table. AIS is PAI in the payments family and the"
                    + " table is pai_verdict; reading the old name would silently read nothing",
            "futured", "warehousing vocabulary: R-38 futured-exclusion WARNs describe rows"
                    + " scheduled beyond the run date, which cannot exist here");

    private static List<Path> filesUnder(final Path root, final String suffix) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> suffix.isEmpty() || p.toString().endsWith(suffix))
                    .toList();
        }
    }

    /**
     * Scans CODE, never comments.
     *
     * <p>This service documents the removal at length, and it has to: a reader who does
     * not know WHY there is no run date will eventually add one back to make a query look
     * symmetrical with CRW's. Those explanations necessarily name the forbidden tokens, so
     * a raw text scan would force the documentation to be deleted in order to pass, which
     * trades the guard for the explanation. Stripping comments first keeps both, and it
     * narrows the assertion to what actually executes.
     *
     * <p>Per file type, because one stripper cannot serve all three: {@code //} in an XML
     * attribute is a URL, not a comment, and would swallow the rest of a real line.
     */
    static String codeOnly(final Path p) {
        final String raw;
        try {
            raw = Files.readString(p);
        } catch (Exception e) {
            throw new IllegalStateException(p.toString(), e);
        }
        final String name = p.toString();
        if (name.endsWith(".java")) {
            return raw.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
        }
        if (name.endsWith(".xml")) {
            return raw.replaceAll("(?s)<!--.*?-->", " ");
        }
        if (name.endsWith(".sql")) {
            return raw.replaceAll("(?m)--.*$", " ");
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return raw.replaceAll("(?m)^\\s*#.*$", " ");
        }
        return raw;
    }

    private static List<String> offenders(final List<Path> files, final String token) {
        return files.stream()
                .filter(p -> codeOnly(p).contains(token))
                .map(Path::toString)
                .toList();
    }

    /**
     * The literal scan over BOTH source trees. Resources matter as much as java: a
     * {@code process_date} predicate reintroduced by a Liquibase changeset or a
     * {@code run.date} key wired through application.yml is exactly as much of a clock
     * path as a Java constant, and the java-only walk cannot see either.
     */
    @Test
    void noClockOrWarehouseTokenAppearsInAnyShippedFile() throws Exception {
        List<Path> files = Stream.concat(filesUnder(MAIN_JAVA, ".java").stream(),
                filesUnder(MAIN_RESOURCES, "").stream()).toList();

        // Control (verification.md rule 11a): the scan must be able to FIND something.
        // Without this, a broken walk returns an empty offender list for every token and
        // reads as a clean pass. "prw_emission" is certainly present in both trees.
        assertThat(offenders(files, "prw_emission"))
                .as("control: the scan reads real file contents, so an empty result below"
                        + " means the token is absent and not that nothing was read")
                .isNotEmpty();

        FORBIDDEN.forEach((token, why) -> assertThat(offenders(files, token))
                .as("'%s' is %s", token, why)
                .isEmpty());
    }

    /**
     * The stripper's own premise, proven rather than assumed. If {@code codeOnly} silently
     * stopped stripping, the test above would fail loudly on this service's own javadoc,
     * so that direction is safe. The dangerous direction is the opposite one: a stripper
     * that removes too much makes every scan vacuous. DueSql's javadoc names
     * {@code runDate} while its SQL does not, so this file distinguishes the two.
     */
    @Test
    void theCommentStripperRemovesCommentsAndKeepsCode() throws Exception {
        Path dueSql = MAIN_JAVA.resolve("za/co/fnb/dcre/prw/data/repo/DueSql.java");
        String raw = Files.readString(dueSql);
        String code = codeOnly(dueSql);

        assertThat(raw).as("premise: the javadoc DOES discuss runDate, which is why"
                + " stripping is necessary rather than cosmetic").contains("runDate");
        assertThat(code).as("...and stripping removed it").doesNotContain("runDate");
        assertThat(code).as("...while keeping the executable SQL, so the scan is not vacuous")
                .contains("pai_verdict").contains("DUE_GATES");
    }

    /**
     * The PERSISTED shape, read off the classes rather than off their text. A field named
     * runDate survives any amount of comment rewording, and it is the one that would put
     * the column back into the claim's ON CONFLICT target and silently widen the
     * idempotency key.
     */
    @Test
    void neitherEmissionEntityDeclaresARunDate() {
        List<String> batchFields = Arrays.stream(PrwEmissionEntity.class.getDeclaredFields())
                .map(Field::getName).toList();
        List<String> groupFields = Arrays.stream(PrwEmissionGroupEntity.class.getDeclaredFields())
                .map(Field::getName).toList();

        assertThat(batchFields)
                .as("prw_emission is keyed (arrival_id, batch_ordinal); a runDate field"
                        + " would reintroduce the warehousing dimension")
                .doesNotContain("runDate");
        assertThat(groupFields)
                .as("prw_emission_group is keyed (arrival_id); one plan per arrival")
                .doesNotContain("runDate");

        // Control: this reflection CAN see the entities' fields, so the absences above are
        // missing fields and not empty reads of the wrong class.
        assertThat(batchFields).as("control: real fields are read")
                .contains("arrivalId", "batchOrdinal", "outboundMsgId");
        assertThat(groupFields).as("control: real fields are read")
                .contains("arrivalId", "appliedMax", "expectedBatchCount");
    }

    /**
     * The QUERY surface. CRW's repository takes a LocalDate on every due-path method; if
     * any survived here it would need a value, and the only value available would be a
     * clock read. No method anywhere in the repo or service tier may accept a date at all.
     */
    @Test
    void noRepositoryOrServiceMethodTakesADateParameter() throws Exception {
        List<String> offenders = new java.util.ArrayList<>();
        for (Path p : filesUnder(MAIN_JAVA, ".java")) {
            String fqcn = p.toString()
                    .replace("src/main/java/", "").replace(".java", "").replace('/', '.');
            Class<?> type = Class.forName(fqcn);
            for (Method m : type.getDeclaredMethods()) {
                for (Class<?> param : m.getParameterTypes()) {
                    if (param == java.time.LocalDate.class) {
                        offenders.add(fqcn + "#" + m.getName());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("a LocalDate parameter means something upstream had to READ A CLOCK to"
                        + " supply it. PRW's inputs are an arrival id and the spine rows"
                        + " that arrival already has")
                .isEmpty();
    }

    /**
     * The LAUNCH surface, asserted on the bean method rather than on source text. AGT
     * launches PRW with {@code arrival.id}; a residual {@code run.date} binding would be
     * silently ignored by a launcher that supplies neither, which is the worst shape:
     * the service would look configured for a clock it never receives.
     */
    @Test
    void theTaskletBindsArrivalIdAndNoClockParameter() throws Exception {
        Method tasklet = za.co.fnb.dcre.prw.config.PrwJobConfig.class
                .getDeclaredMethod("emissionTasklet",
                        za.co.fnb.dcre.prw.service.EmissionService.class, String.class);
        List<String> values = Arrays.stream(tasklet.getParameters())
                .map(p -> p.getAnnotation(org.springframework.beans.factory.annotation.Value.class))
                .filter(java.util.Objects::nonNull)
                .map(org.springframework.beans.factory.annotation.Value::value)
                .toList();

        assertThat(values)
                .as("PRW's identifying JobParameter is arrival.id (R-16), the MRW shape")
                .containsExactly("#{jobParameters['arrival.id']}");
    }
}
