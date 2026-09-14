package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.ReportResponse;
import com.mockinterview.backend.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated read access to a shared report — see SecurityConfig's permit-all on
 *  /api/public/**. Resolves by share token only, never by session id. */
@RestController
@RequestMapping("/api/public/reports")
@RequiredArgsConstructor
public class PublicReportController {

    private final ReportService reportService;

    @GetMapping("/{token}")
    public ReportResponse getReport(@PathVariable String token) {
        return reportService.getPublicReport(token);
    }
}
