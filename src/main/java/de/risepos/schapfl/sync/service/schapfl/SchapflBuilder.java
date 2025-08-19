package de.risepos.schapfl.sync.service.schapfl;

import de.risepos.schapfl.sync.service.dto.ArticlePosDto;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@ApplicationScoped
public class SchapflBuilder {

    @ConfigProperty(name = "schapfl.store-number") int store;
    @ConfigProperty(name = "schapfl.lane-number") int lane;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static final String ART_HEADER =
            "EAN;INDEX;BEZ;BONTEXT;VK;WGR;UWGR;EANGEB;BEZGEB;BONTEXTGEB;VKGEB;MENGEGEB;LIEFARTNR;STABILENR;EK;EKGEB;" +
                    "BE;BESTELLKZ;BS;ME;MT;GPFAKTOR;MEGEB;MTGEB;GPFAKTORGEB;PFANDID;PFANDIDGEB;PREISGEBUNDEN;FSK;VE;" +
                    "PE;PEGEB;KURZCODE;KURZCODEGEB;TARA;TARAGEB;MARKE;GROESSE;FARBE;NOTIZEN";

    public static final String MEH_HEADER =
            "EANHAUPT;INDEX;EAN;BEZEICHNUNG;LIEFARTNR;MENGEGEB";

    /** Result for a whole batch (one ART + one MEH). */
    public record BuiltBatch(String artName, byte[] artContent,
                             Optional<String> mehName, Optional<byte[]> mehContent) { }

    /** Build one ART file and (if any scancodes exist) one MEH file for the whole list. */
    public BuiltBatch buildBatch(List<ArticlePosDto> items, LocalDateTime now) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("No articles to build");
        }

        String ts = TS.format(now);
        String artName = "ART_%06d_%03d_%s.SCHAPFL".formatted(store, lane, ts);
        String mehName = "MEH_%06d_%03d_%s.SCHAPFL".formatted(store, lane, ts);

        // Collect rows
        List<String> artRows = new ArrayList<>(items.size());
        List<String> mehRows = new ArrayList<>(); // unknown count

        for (ArticlePosDto dto : items) {
            artRows.add(buildArtLine(dto));
            mehRows.addAll(buildMehRows(dto));
        }

        // Build ART content
        String artText = joinWithHeader(ART_HEADER, artRows);
        byte[] artBytes = withBom(artText);

        // Build MEH content only if at least one scancode exists
        if (mehRows.isEmpty()) {
            return new BuiltBatch(artName, artBytes, Optional.empty(), Optional.empty());
        }
        String mehText = joinWithHeader(MEH_HEADER, mehRows);
        byte[] mehBytes = withBom(mehText);

        return new BuiltBatch(artName, artBytes, Optional.of(mehName), Optional.of(mehBytes));
    }

    private String joinWithHeader(String header, List<String> rows) {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder(header.length() + rows.size() * 128);
        sb.append(header).append(nl);
        for (String r : rows) sb.append(r).append(nl);
        return sb.toString();
    }

    private String buildArtLine(ArticlePosDto dto) {
        String eanHaupt = String.valueOf(dto.getId());
        String index = "0";
        String bez = Optional.ofNullable(dto.getDescription()).orElse("");
        String bontext = "";
        String vk = dto.getSelling_price() == null
                ? "0.00" : dto.getSelling_price().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String wgr = dto.getArticlegroup_id() == null ? "0" : String.valueOf(dto.getArticlegroup_id());
        String uwgr = "0";

        String eangeb = "";
        String bezgeb = "";
        String bontextgeb = "";
        String vkgeb = "0";
        String mengegeb = "0";
        String liefartnr = "";
        String stabilenr = "";
        String ek = "0";
        String ekgeb = "0";
        String be = "0";
        String bestellkz = "J";
        String bs = "N";
        String me = "0";
        String mt = "";
        String gpFaktor = "0";
        String megeb = "0";
        String mtgeb = "";
        String gpFaktorGeb = "0";
        String pfandId = String.valueOf(Optional.ofNullable(dto.getDeposit_id()).orElse(0L));
        String pfandIdGeb = "0";
        String preisgebunden = "N";
        String fsk = String.valueOf(dto.getMin_age());
        String ve = "1";
        String pe = "1";
        String pegeb = "0";
        String kurzcode = "0";
        String kurzcodeGeb = "0";
        String tara = "0";
        String taraGeb = "0";
        String marke = "";
        String groesse = "";
        String farbe = "";
        String notizen = "";

        return String.join(";", Arrays.asList(
                eanHaupt, index, bez, bontext, vk, wgr, uwgr, eangeb, bezgeb, bontextgeb,
                vkgeb, mengegeb, liefartnr, stabilenr, ek, ekgeb, be,
                bestellkz, bs, me, mt, gpFaktor, megeb, mtgeb, gpFaktorGeb,
                pfandId, pfandIdGeb, preisgebunden, fsk, ve, pe,
                pegeb, kurzcode, kurzcodeGeb, tara, taraGeb, marke, groesse, farbe, notizen
        ));
    }

    private List<String> buildMehRows(ArticlePosDto dto) {
        if (dto.getScancodes() == null || dto.getScancodes().isEmpty()) return List.of();
        String eanHaupt = String.valueOf(dto.getId());
        String index = "0";
        String bez = Optional.ofNullable(dto.getDescription()).orElse("");

        List<String> rows = new ArrayList<>();
        for (String code : dto.getScancodes()) {
            if (code == null || code.isBlank()) continue;
            rows.add(String.join(";", eanHaupt, index, code.trim(), bez, "", "0"));
        }
        return rows;
    }

    private static byte[] withBom(String s) {
        byte[] bom = {(byte)0xEF,(byte)0xBB,(byte)0xBF};
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + data.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(data, 0, out, bom.length, data.length);
        return out;
    }
}
