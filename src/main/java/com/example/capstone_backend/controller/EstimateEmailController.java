package com.example.capstone_backend.controller;

import com.example.capstone_backend.dto.EstimateEmailRequest;
import com.example.capstone_backend.service.EstimateEmailRateLimiter;
import com.example.capstone_backend.service.EstimateEmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/estimates")
public class EstimateEmailController {
    private final EstimateEmailService service;
    private final EstimateEmailRateLimiter limiter;

    public EstimateEmailController(EstimateEmailService service, EstimateEmailRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @PostMapping(value = "/send-pdf", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sendPdf(@Valid @RequestBody EstimateEmailRequest request,
                                                        HttpServletRequest httpRequest) {
        // Use the server-visible remote address, never an untrusted X-Forwarded-For header.
        limiter.acquire(httpRequest.getRemoteAddr());
        service.send(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "메일 서버에 견적서 PDF 전송 요청이 정상 접수되었습니다."
        ));
    }
    /** Browser-rendered results screen PDF (with charts and AI cards). */
    @PostMapping(value = "/send-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> sendScreenPdf(
            @Valid @RequestPart("estimate") EstimateEmailRequest request,
            @RequestPart("pdf") MultipartFile pdf,
            HttpServletRequest httpRequest) {
        limiter.acquire(httpRequest.getRemoteAddr());
        service.sendRendered(request, pdf);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "메일 서버에 결과 화면 PDF 전송 요청이 정상 접수되었습니다."
        ));
    }

}
