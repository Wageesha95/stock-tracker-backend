package com.personal.stocktracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.stocktracker.document.User;
import com.personal.stocktracker.document.Watchlist;
import com.personal.stocktracker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WatchlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private WatchlistRepository watchlistRepository;

    @MockitoBean
    private TransactionRepository transactionRepository;

    @MockitoBean
    private DividendRepository dividendRepository;

    @MockitoBean
    private PdfUploadRecordRepository pdfUploadRecordRepository;

    private MockHttpSession session;

    @BeforeEach
    void login() throws Exception {
        User user = User.builder()
                .id("u1")
                .username("testuser")
                .password(passwordEncoder.encode("pass123"))
                .role("USER")
                .build();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        session = (MockHttpSession) mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "pass123"))))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession();
    }

    @Test
    void getAll_returnsUserWatchlists() throws Exception {
        when(watchlistRepository.findByUserIdOrderByCreatedAtAsc("testuser"))
                .thenReturn(List.of(
                        Watchlist.builder().id("w1").name("My List").userId("testuser").companyCodes(new ArrayList<>()).build()
                ));

        mockMvc.perform(get("/api/watchlists").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("My List"));
    }

    @Test
    void create_createsWatchlist() throws Exception {
        when(watchlistRepository.save(any(Watchlist.class))).thenAnswer(i -> {
            Watchlist w = i.getArgument(0);
            w.setId("w1");
            return w;
        });

        mockMvc.perform(post("/api/watchlists")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Tech Stocks", "color", "#3182ce"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Tech Stocks"))
                .andExpect(jsonPath("$.color").value("#3182ce"));
    }

    @Test
    void addCompany_addsToWatchlist() throws Exception {
        Watchlist wl = Watchlist.builder()
                .id("w1").userId("testuser").name("Test")
                .companyCodes(new ArrayList<>())
                .build();
        when(watchlistRepository.findById("w1")).thenReturn(Optional.of(wl));
        when(watchlistRepository.save(any(Watchlist.class))).thenAnswer(i -> i.getArgument(0));

        mockMvc.perform(post("/api/watchlists/w1/companies")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("companyCode", "JKH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyCodes[0]").value("JKH"));
    }

    @Test
    void delete_otherUsersWatchlist_throwsError() throws Exception {
        Watchlist wl = Watchlist.builder()
                .id("w2").userId("otheruser").name("Other")
                .companyCodes(new ArrayList<>())
                .build();
        when(watchlistRepository.findById("w2")).thenReturn(Optional.of(wl));

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                mockMvc.perform(delete("/api/watchlists/w2").session(session))
        );
    }
}
