package za.co.fnb.dcre.prw.service;

import org.springframework.stereotype.Component;
import za.co.fnb.dcre.prw.data.model.PrwEmissionMemberEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SYNTHETIC-CONTRACT (R-35, A-9): pain.008-shaped XML skeleton. Real bindings become JAXB
 * from the Fintegrate XSD profile when recovered (R-18); outbound EndToEndId is the
 * canonical value byte-preserved (R-15). The MsgId parameter receives the batch's outbound
 * identity (bare source MsgId unsplit, source_N for split children); the writer itself is
 * grain-agnostic.
 *
 * <p>This is a byte-for-byte port of CRW's writer, and that is the point of forking CRW
 * rather than MRW: payments instructions ARE pain.008, so CRW's emission body is exactly
 * what PRW needs, while MRW emits pain.009 mandate initiation, a different ISO message.
 *
 * <p>DUPLICATION, ACKNOWLEDGED: this class is now identical in crw and prw. The family
 * design (2026-08-07) puts the shared pain.008 builder in a `platform-fintegrate` module
 * consumed by both. That module does not exist yet, and extracting it means repointing
 * CRW, which is out of this task's scope (crw must remain unmodified). Recorded as the
 * follow-up rather than pre-empted here, because a copy that is KNOWN and scheduled is
 * cheaper than an extraction done blind across a live service.
 */
@Component
public class Pain008Writer {

    public List<String> build(final String outboundMsgId, final List<PrwEmissionMemberEntity> members,
                              final BigDecimal controlSum) {
        List<String> xml = new ArrayList<>();
        xml.add("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.add("<Document><!-- SYNTHETIC-CONTRACT pain.008 skeleton (A-9) -->");
        xml.add("  <CstmrDrctDbtInitn><GrpHdr>");
        xml.add("    <MsgId>%s</MsgId>".formatted(outboundMsgId));
        xml.add("    <NbOfTxs>%d</NbOfTxs>".formatted(members.size()));
        xml.add("    <CtrlSum>%s</CtrlSum>".formatted(controlSum));
        xml.add("  </GrpHdr><PmtInf>");
        xml.add("    <PmtTpInf><LclInstrm><Cd>TT2</Cd></LclInstrm></PmtTpInf>");
        for (PrwEmissionMemberEntity member : members) {
            xml.add("    <DrctDbtTxInf><PmtId><EndToEndId>%s</EndToEndId></PmtId><InstdAmt Ccy=\"ZAR\">%s</InstdAmt></DrctDbtTxInf>".formatted(member.getE2e().strip(), member.getAmount()));
        }
        xml.add("  </PmtInf></CstmrDrctDbtInitn>");
        xml.add("</Document>");
        return xml;
    }
}
