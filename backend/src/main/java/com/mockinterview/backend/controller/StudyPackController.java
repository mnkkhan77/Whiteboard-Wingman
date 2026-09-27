package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.PackDto;
import com.mockinterview.backend.dto.PackLimitsDto;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.UserRepository;
import com.mockinterview.backend.service.StudyPackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Study Packs REST API (docs/study-packs-contract.md "REST API"). Every route is authenticated
 *  (SecurityConfig) and scoped to the caller's own packs. */
@RestController
@RequestMapping("/api/packs")
@RequiredArgsConstructor
public class StudyPackController {

    private final StudyPackService studyPackService;
    private final UserRepository userRepository;

    // Literal path segment, resolved before the sibling "/{id}" mapping — no routing collision.
    @GetMapping("/limits")
    public PackLimitsDto limits(Authentication auth) {
        return studyPackService.limits(resolveUser(auth));
    }

    @GetMapping
    public List<PackDto> list(Authentication auth) {
        return studyPackService.list(resolveUser(auth));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PackDto upload(@RequestParam("file") MultipartFile file,
                          @RequestParam(value = "title", required = false) String title,
                          Authentication auth) {
        return studyPackService.upload(resolveUser(auth), file, title);
    }

    @GetMapping("/{id}")
    public PackDto get(@PathVariable Long id, Authentication auth) {
        return studyPackService.get(resolveUser(auth), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        studyPackService.delete(resolveUser(auth), id);
    }

    private User resolveUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }
}
