package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.CodeRunRequest;
import com.mockinterview.backend.dto.CodeRunResponse;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.UserRepository;
import com.mockinterview.backend.service.InterviewSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final InterviewSessionService interviewSessionService;
    private final UserRepository userRepository;

    @PostMapping("/{id}/run")
    public CodeRunResponse runCode(@PathVariable Long id, @Valid @RequestBody CodeRunRequest request, Authentication auth) {
        return interviewSessionService.runCode(resolveUser(auth), id, request);
    }

    private User resolveUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }
}
