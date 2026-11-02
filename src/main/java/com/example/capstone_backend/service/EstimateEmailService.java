package com.example.capstone_backend.service;

import com.example.capstone_backend.dto.EstimateEmailRequest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.web.multipart.MultipartFile;
import com.example.capstone_backend.entity.*;
import com.example.capstone_backend.entity.Case;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class EstimateEmailService {
    private final EntityManager em;
    private final PdfEstimateService pdfService;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String senderAddress;

    public record VerifiedPart(String category, String name, long price, boolean isOwned) { }
    public record VerifiedEstimate(String rankName, long budget, long totalPrice, List<VerifiedPart> parts) { }

    public EstimateEmailService(EntityManager em, PdfEstimateService pdfService,
                                ObjectProvider<JavaMailSender> mailSenderProvider,
                                @Value("${capstone.mail.from:}") String senderAddress) {
        this.em = em;
        this.pdfService = pdfService;
        this.mailSenderProvider = mailSenderProvider;
        this.senderAddress = senderAddress;
    }

    public void send(EstimateEmailRequest request) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null || senderAddress == null || senderAddress.isBlank()) {
            throw new EstimateEmailException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_NOT_CONFIGURED",
                    "이메일 서비스가 아직 설정되지 않았습니다. 관리자에게 문의해 주세요.");
        }
        VerifiedEstimate estimate = verifyEstimate(request);
        try {
            byte[] pdf = pdfService.create(estimate);
            submitMail(sender, request, pdf, "PickPC-estimate.pdf");
        } catch (EstimateEmailException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new EstimateEmailException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF_GENERATION_FAILED",
                    "견적서 PDF 생성에 실패했습니다. 서버의 한글 글꼴 설정을 확인해 주세요.");
        }
    }

    /** Sends the browser-rendered actual results page, not a newly drawn parts table. */
    public void sendRendered(EstimateEmailRequest request, MultipartFile uploaded) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null || senderAddress == null || senderAddress.isBlank()) {
            throw new EstimateEmailException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_NOT_CONFIGURED",
                    "이메일 서비스가 아직 설정되지 않았습니다. 관리자에게 문의해 주세요.");
        }
        verifyEstimate(request); // Do not allow anonymous arbitrary uploads without valid part references.
        if (uploaded == null || uploaded.isEmpty() || uploaded.getSize() > 12L * 1024 * 1024) {
            throw new EstimateEmailException(HttpStatus.BAD_REQUEST, "INVALID_PDF", "PDF 파일은 12MB 이하여야 합니다.");
        }
        try {
            byte[] pdf = uploaded.getBytes();
            if (pdf.length < 10 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D'
                    || pdf[3] != 'F' || pdf[4] != '-') {
                throw new EstimateEmailException(HttpStatus.BAD_REQUEST, "INVALID_PDF", "올바른 PDF 파일이 아닙니다.");
            }
            try (PDDocument document = Loader.loadPDF(pdf)) {
                if (document.isEncrypted() || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 20) {
                    throw new EstimateEmailException(HttpStatus.BAD_REQUEST, "INVALID_PDF",
                            "PDF는 암호화되지 않은 1~20페이지 문서여야 합니다.");
                }
            } catch (IOException exception) {
                throw new EstimateEmailException(HttpStatus.BAD_REQUEST, "INVALID_PDF", "PDF 파일을 열 수 없습니다.");
            }
            submitMail(sender, request, pdf, "PickPC-results.pdf");
        } catch (IOException exception) {
            throw new EstimateEmailException(HttpStatus.BAD_REQUEST, "INVALID_PDF", "PDF 파일을 읽을 수 없습니다.");
        }
    }

    private void submitMail(JavaMailSender sender, EstimateEmailRequest request,
                            byte[] pdf, String filename) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(senderAddress);
            helper.setTo(request.email().trim());
            helper.setSubject("[PickPC] PC 견적서 PDF");
            helper.setText("요청하신 PickPC 견적 결과 PDF를 첨부했습니다.\n"
                    + "화면 캡처 방식 PDF의 가격 및 분석은 사용자가 발송 시점에 본 결과를 기준으로 합니다.\n"
                    + "상품 가격은 이후 변동될 수 있습니다.", false);
            helper.addAttachment(filename, new ByteArrayResource(pdf), "application/pdf");
            sender.send(message); // Success only when SMTP submission completes.
        } catch (MessagingException | MailException ex) {
            throw new EstimateEmailException(HttpStatus.BAD_GATEWAY, "MAIL_DELIVERY_FAILED",
                    "메일 서버에서 발송을 처리하지 못했습니다. 설정 및 SMTP 연결 상태를 확인해 주세요.");
        }
    }

    private VerifiedEstimate verifyEstimate(EstimateEmailRequest request) {
        List<VerifiedPart> verified = new ArrayList<>();
        Set<String> usedCategories = new HashSet<>();
        long total = 0;
        for (EstimateEmailRequest.PartRef input : request.parts()) {
            if (input == null) throw new IllegalArgumentException("부품 정보가 올바르지 않습니다.");
            String category = normalizeCategory(input.category());
            if (!usedCategories.add(category)) {
                throw new IllegalArgumentException("동일한 부품 카테고리가 중복되었습니다: " + category);
            }
            if ((input.productCode() == null || input.productCode() <= 0)
                    && (input.id() == null || input.id() <= 0)) {
                throw new IllegalArgumentException("상품코드 또는 부품 ID가 필요합니다: " + category);
            }
            ProductRow row = readDbProduct(category, input.productCode(), input.id());
            if (row == null || row.price() == null || row.price() < 0) {
                throw new IllegalArgumentException("현재 DB에서 부품을 찾을 수 없습니다: " + category);
            }
            boolean owned = Boolean.TRUE.equals(input.isOwned());
            long price = owned ? 0 : row.price();
            total = Math.addExact(total, price);
            verified.add(new VerifiedPart(category, row.name(), price, owned));
        }
        return new VerifiedEstimate(request.rankName().trim(), request.budget(), total, List.copyOf(verified));
    }

    private record ProductRow(String name, Long price) { }

    private ProductRow readDbProduct(String category, Long code, Long id) {
        return switch (category) {
            case "CPU" -> product(Cpu.class, code, id, Cpu::getName, Cpu::getPrice);
            case "GPU" -> product(Gpu.class, code, id, Gpu::getName, Gpu::getPrice);
            case "RAM" -> product(Ram.class, code, id, Ram::getName, Ram::getPrice);
            case "SSD" -> product(Ssd.class, code, id, Ssd::getName, Ssd::getPrice);
            case "MAINBOARD" -> product(Mainboard.class, code, id, Mainboard::getName, Mainboard::getPrice);
            case "POWER" -> product(Power.class, code, id, Power::getName, Power::getPrice);
            case "CASE" -> product(Case.class, code, id, Case::getName, Case::getPrice);
            case "COOLER" -> product(Cooler.class, code, id, Cooler::getName, Cooler::getPrice);
            default -> throw new IllegalArgumentException("지원하지 않는 부품 카테고리입니다.");
        };
    }

    private <T> ProductRow product(Class<T> entityType, Long code, Long id,
                                   java.util.function.Function<T, String> name,
                                   java.util.function.Function<T, Long> price) {
        T found;
        if (code != null && code > 0) {
            List<T> results = em.createQuery("SELECT p FROM " + entityType.getSimpleName()
                            + " p WHERE p.productCode = :code", entityType)
                    .setParameter("code", code).setMaxResults(1).getResultList();
            found = results.isEmpty() ? null : results.get(0);
        } else {
            found = em.find(entityType, id);
        }
        return found == null ? null : new ProductRow(name.apply(found), price.apply(found));
    }

    private String normalizeCategory(String raw) {
        if (raw == null) throw new IllegalArgumentException("부품 카테고리가 필요합니다.");
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "CPU", "GPU", "RAM", "SSD", "COOLER" -> upper;
            case "MAINBOARD", "MOTHERBOARD", "메인보드" -> "MAINBOARD";
            case "POWER", "파워", "파워서플라이" -> "POWER";
            case "CASE", "케이스" -> "CASE";
            case "쿨러" -> "COOLER";
            case "그래픽카드" -> "GPU";
            default -> throw new IllegalArgumentException("지원하지 않는 부품 카테고리입니다: " + raw);
        };
    }
}
