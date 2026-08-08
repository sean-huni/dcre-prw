package za.co.fnb.dcre.prw.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeLayout;
import za.co.fnb.dcre.platform.files.ExchangeSub;
import za.co.fnb.dcre.prw.PrwTestcontainersBase;
import za.co.fnb.dcre.prw.data.model.PrwEmissionGroupEntity;
import za.co.fnb.dcre.prw.data.repo.PrwEmissionGroupRepo;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-43, ruled 2026-08-08: {@code tx_header.client_token} is the outbound client authority and
 * {@code prw_emission_group.client} carries it, with {@code initg_pty} as the fallback for an
 * arrival that carries no R-31 filename token.
 *
 * <p>PRW inherited the derivation from CRW by forking it, not by choosing it, so it inherits the
 * correction the same way. It also inherits the reason the correction matters HERE more than in
 * collections: {@code ext_tx_status.client} and {@code prg_watermark.client} in {@code dcre_pay}
 * both carry {@code tx_header.client_token}, so an emission group written from {@code initg_pty}
 * can name a parent in {@code prg_report_due} whose rows the watermark cannot then select. The
 * report emits nothing, nothing is ledgered, the parent stays due and AGT retriggers it forever
 * with no error anywhere. That is the defect this repo reported to the owner on 2026-08-08, and
 * this is its fix.
 *
 * <p>THIS IS THE ONLY PRW TEST THAT CAN SEE THE DIFFERENCE: every other fixture seeds one value
 * into both columns.
 *
 * <p>Red-proof (recorded 2026-08-08): reverting {@code DueSql.CLIENT_EXPR} to a bare
 * {@code h.initg_pty} fails {@link #divergentHeaderEmitsUnderTheFilenameTokenNotTheHeader} on the
 * emission-group column, the file name and the directory. Both assertions were seen red before
 * they were seen green.
 */
class ClientAuthorityIT extends PrwTestcontainersBase {

    @Autowired
    EmissionService service;

    @Autowired
    PrwEmissionGroupRepo groups;

    @Autowired
    ExchangeLayout layout;

    private Path fintReqOut(final String client) {
        return layout.resolve(client, ExchangeChannel.FINT_REQ, ExchangeSub.OUT);
    }

    /**
     * The two homes hold DIFFERENT configured clients, so the emission group column, the file
     * name and the output directory each have somewhere wrong they could land.
     */
    @Test
    void divergentHeaderEmitsUnderTheFilenameTokenNotTheHeader() {
        final UUID arrivalId = UUID.randomUUID();
        final String msgId = "ENDOCC2026082000000201";
        seedArrival(arrivalId, "FNBCC02", "FNBRF01", msgId, 3, 3, "PASS");

        service.emit(arrivalId);

        final PrwEmissionGroupEntity group = groups.findByArrivalId(arrivalId).orElseThrow();
        assertThat(group.getClient())
                .as("prw_emission_group.client carries tx_header.client_token, not initg_pty")
                .isEqualTo("FNBCC02");
        assertThat(fintReqOut("FNBCC02").resolve("FNBCC02_%s_PAIN008.xml".formatted(msgId)))
                .as("outbound file lands in the filename token's own fint-req/out leaf")
                .exists();
        assertThat(fintReqOut("FNBRF01").resolve("FNBRF01_%s_PAIN008.xml".formatted(msgId)))
                .as("nothing is written under the header client's directory")
                .doesNotExist();
        assertThat(fintReqOut("FNBRF01").resolve("FNBCC02_%s_PAIN008.xml".formatted(msgId)))
                .as("the directory and the file name must not come from different sources")
                .doesNotExist();
    }

    /**
     * The NOT NULL hazard, stated as a test. {@code client_token} is nullable by design and
     * {@code prw_emission_group.client} is NOT NULL, so a bare swap for the authority would
     * convert a silent mis-selection into an insert failure. The fallback is what makes this a
     * correction rather than a new defect, and it is the shape {@code mandates/mrw} chose first.
     */
    @Test
    void absentFilenameTokenFallsBackToTheCopybookHeader() {
        final UUID arrivalId = UUID.randomUUID();
        final String msgId = "ENDOCC2026082000000202";
        seedArrival(arrivalId, null, "FNBCC01", msgId, 2, 2, "PASS");

        service.emit(arrivalId);

        final PrwEmissionGroupEntity group = groups.findByArrivalId(arrivalId).orElseThrow();
        assertThat(group.getClient()).isEqualTo("FNBCC01");
        assertThat(fintReqOut("FNBCC01").resolve("FNBCC01_%s_PAIN008.xml".formatted(msgId))).exists();
    }
}
