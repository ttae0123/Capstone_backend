package com.example.capstone_backend.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Server-side Korean PDF. No fonts are distributed: configure a licensed local TTF. */
@Service
public class PdfEstimateService {
    private final String configuredFontPath;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public PdfEstimateService(@Value("${capstone.mail.pdf-font-path:}") String configuredFontPath) {
        this.configuredFontPath = configuredFontPath;
    }

    public byte[] create(EstimateEmailService.VerifiedEstimate estimate) throws IOException {
        Path fontPath = resolveFontPath();
        try (PDDocument pdf = new PDDocument()) {
            pdf.getDocumentInformation().setTitle("PickPC PC 견적서");
            PDType0Font font = PDType0Font.load(pdf, fontPath.toFile());
            String issuedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DATE_FORMAT);
            try (Writer writer = new Writer(pdf, font, estimate, issuedAt)) {
                for (EstimateEmailService.VerifiedPart part : estimate.parts()) writer.part(part);
                writer.note("* 가격은 PDF 생성 시점의 DB 기준이며 변동될 수 있습니다.");
                writer.note("* 보유 부품 표시 여부는 사용자 선택에 따른 것입니다.");
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            pdf.save(result);
            return result.toByteArray();
        }
    }

    private Path resolveFontPath() throws IOException {
        List<String> candidates = new ArrayList<>();
        if (configuredFontPath != null && !configuredFontPath.isBlank()) candidates.add(configuredFontPath);
        candidates.add("/usr/share/fonts/truetype/nanum/NanumGothic.ttf");
        candidates.add("/usr/share/fonts/truetype/nanum/NanumBarunGothic.ttf");
        candidates.add("C:/Windows/Fonts/malgun.ttf");
        for (String path : candidates) {
            if (Files.isRegularFile(Path.of(path))) return Path.of(path);
        }
        throw new IOException("한글 PDF 글꼴을 찾을 수 없습니다. PDF_FONT_PATH를 지정해 주세요.");
    }

    private static final class Writer implements AutoCloseable {
        private static final float LEFT = 48f;
        private static final float RIGHT = 547f;
        private final PDDocument document;
        private final PDType0Font font;
        private final EstimateEmailService.VerifiedEstimate estimate;
        private final String issuedAt;
        private PDPageContentStream canvas;
        private float y;
        private int pageCount;

        Writer(PDDocument document, PDType0Font font,
               EstimateEmailService.VerifiedEstimate estimate, String issuedAt) throws IOException {
            this.document = document;
            this.font = font;
            this.estimate = estimate;
            this.issuedAt = issuedAt;
            nextPage();
        }

        private void nextPage() throws IOException {
            if (canvas != null) finishCurrentPage();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            canvas = new PDPageContentStream(document, page);
            pageCount++;
            canvas.setNonStrokingColor(37 / 255f, 99 / 255f, 235 / 255f);
            canvas.addRect(0, 811, PDRectangle.A4.getWidth(), 31);
            canvas.fill();
            draw("PickPC  |  PC 견적서", LEFT, 783, 19, 30, 41, 59);
            draw("발행 일시: " + issuedAt + " (KST)", LEFT, 761, 9, 90, 103, 122);
            List<String> nameLines = wrap("견적 구성: " + estimate.rankName(), 480, 11);
            float titleY = 728;
            for (String line : nameLines) {
                draw(line, LEFT, titleY, 11, 37, 48, 64);
                titleY -= 15;
            }
            draw("설정 예산: " + comma(estimate.budget()) + "원", LEFT, titleY - 9, 10, 62, 75, 91);
            draw("신규 구매 총액: " + comma(estimate.totalPrice()) + "원", 305, titleY - 9, 11, 37, 99, 235);
            y = titleY - 42;
            header();
        }

        private void header() throws IOException {
            canvas.setNonStrokingColor(238 / 255f, 242 / 255f, 250 / 255f);
            canvas.addRect(LEFT, y - 23, RIGHT - LEFT, 27);
            canvas.fill();
            draw("분류", LEFT + 9, y - 14, 10, 54, 65, 83);
            draw("부품명", LEFT + 94, y - 14, 10, 54, 65, 83);
            draw("가격", RIGHT - 63, y - 14, 10, 54, 65, 83);
            y -= 28;
        }

        void part(EstimateEmailService.VerifiedPart part) throws IOException {
            List<String> lines = wrap(part.name(), 302, 10);
            float rowHeight = Math.max(37, 14f * lines.size() + 15);
            if (y - rowHeight < 105) nextPage();
            draw(part.category(), LEFT + 9, y - 18, 9, 65, 78, 95);
            float nameY = y - 17;
            for (String line : lines) {
                draw(line, LEFT + 94, nameY, 10, 33, 45, 64);
                nameY -= 14;
            }
            String price = part.isOwned() ? "보유 (0원)" : comma(part.price()) + "원";
            draw(price, RIGHT - 86, y - 18, 9, part.isOwned() ? 5 : 44, part.isOwned() ? 125 : 56,
                    part.isOwned() ? 85 : 76);
            canvas.setStrokingColor(226 / 255f, 232 / 255f, 240 / 255f);
            canvas.moveTo(LEFT, y - rowHeight + 1);
            canvas.lineTo(RIGHT, y - rowHeight + 1);
            canvas.stroke();
            y -= rowHeight;
        }

        void note(String value) throws IOException {
            if (y < 93) nextPage();
            draw(value, LEFT + 3, y - 17, 8.5f, 105, 115, 132);
            y -= 20;
        }

        private List<String> wrap(String value, float maxWidth, float size) throws IOException {
            String input = sanitize(value);
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int cp : input.codePoints().toArray()) {
                String chr = new String(Character.toChars(cp));
                String candidate = current + chr;
                if (current.length() > 0 && width(candidate, size) > maxWidth) {
                    lines.add(current.toString());
                    current = new StringBuilder(chr);
                } else {
                    current.append(chr);
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            if (lines.isEmpty()) lines.add("-");
            return lines;
        }

        private float width(String value, float size) throws IOException {
            return font.getStringWidth(value) * size / 1000f;
        }

        private String sanitize(String input) throws IOException {
            if (input == null) return "-";
            StringBuilder safe = new StringBuilder();
            for (int cp : input.codePoints().toArray()) {
                if (Character.isISOControl(cp)) continue;
                String chr = new String(Character.toChars(cp));
                try { font.getStringWidth(chr); safe.append(chr); }
                catch (IllegalArgumentException ex) { safe.append('?'); }
            }
            return safe.toString();
        }

        private void draw(String string, float x, float baseline, float size,
                          int r, int g, int b) throws IOException {
            canvas.setNonStrokingColor(r / 255f, g / 255f, b / 255f);
            canvas.beginText();
            canvas.setFont(font, size);
            canvas.newLineAtOffset(x, baseline);
            canvas.showText(sanitize(string));
            canvas.endText();
        }

        private static String comma(long value) { return String.format(java.util.Locale.KOREA, "%,d", value); }

        private void finishCurrentPage() throws IOException {
            draw("PickPC | 본 견적서는 구매 가격을 보증하지 않습니다.", LEFT, 46, 8, 107, 114, 128);
            draw("페이지 " + pageCount, RIGHT - 48, 46, 8, 107, 114, 128);
            canvas.close();
            canvas = null;
        }

        @Override public void close() throws IOException {
            if (canvas != null) finishCurrentPage();
        }
    }
}
