package com.mockinterview.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.entity.Role;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RBAC + happy-path coverage for the Phase 5 admin analytics endpoints (PLAN.md §5/§10): "a
 * USER-role JWT must get 403 on every /admin/** endpoint; only an ADMIN-role JWT succeeds" — the
 * kind of gap that's easy to silently introduce with a new admin endpoint added without the guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "email", email, "password", "password123", "displayName", "Test User"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        return email;
    }

    private String login(String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", email, "password", "password123"));
        String response = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String registerAndGetUserToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        register(email);
        return login(email);
    }

    /** Registers a user, promotes it to ADMIN directly via the repository, then re-logs-in
     *  (login re-reads the role from the DB and puts the current role in the JWT claim, same as
     *  AdminSeeder's dev-time promotion — see AuthService.login). */
    private String registerAndGetAdminToken() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        register(email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        return login(email);
    }

    @Test
    void listUsersIsRejectedForAnOrdinaryUser() throws Exception {
        String token = registerAndGetUserToken();
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void userDetailIsRejectedForAnOrdinaryUser() throws Exception {
        String token = registerAndGetUserToken();
        mockMvc.perform(get("/api/admin/users/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void statsIsRejectedForAnOrdinaryUser() throws Exception {
        String token = registerAndGetUserToken();
        mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsersSucceedsForAnAdminAndIncludesTheNewlyRegisteredUser() throws Exception {
        String email = "findme-" + UUID.randomUUID() + "@example.com";
        register(email);
        String adminToken = registerAndGetAdminToken();

        mockMvc.perform(get("/api/admin/users?size=100").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.email == '" + email + "')].sessionCount").value(0));
    }

    @Test
    void userDetailSucceedsForAnAdminAndReturnsAnEmptySessionListForABrandNewUser() throws Exception {
        String email = "detail-" + UUID.randomUUID() + "@example.com";
        register(email);
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        String adminToken = registerAndGetAdminToken();

        mockMvc.perform(get("/api/admin/users/" + userId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.sessions").isArray())
                .andExpect(jsonPath("$.sessions").isEmpty());
    }

    @Test
    void statsSucceedsForAnAdminAndReturnsAPositiveUserCount() throws Exception {
        registerAndGetUserToken(); // ensures at least one non-admin user exists
        String adminToken = registerAndGetAdminToken();

        mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers", greaterThanOrEqualTo(2)));
    }
}
