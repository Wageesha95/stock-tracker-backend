package com.personal.stocktracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.stocktracker.document.User;
import com.personal.stocktracker.repository.UserRepository;
import com.personal.stocktracker.service.CustomUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void login_returnsUnauthorized_withInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "bad", "password", "bad"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_returnsUser_withValidCredentials() throws Exception {
        User user = User.builder()
                .id("u1")
                .username("testuser")
                .password(passwordEncoder.encode("pass123"))
                .role("USER")
                .build();
        org.mockito.Mockito.when(userRepository.findByUsername("testuser"))
                .thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "pass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void me_returnsUnauthorized_whenNotLoggedIn() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_returnsUnauthorized_withoutSession() throws Exception {
        mockMvc.perform(get("/api/companies"))
                .andExpect(status().isUnauthorized());
    }
}
