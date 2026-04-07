package com.personal.stocktracker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class GeoIpService {

    private final RestTemplate restTemplate = new RestTemplate();

    public String lookup(String ip) {
        if (ip == null || ip.isBlank() || "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return "Localhost";
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(
                    "http://ip-api.com/json/" + ip + "?fields=status,city,country", Map.class);
            if (response != null && "success".equals(response.get("status"))) {
                String city = (String) response.getOrDefault("city", "");
                String country = (String) response.getOrDefault("country", "");
                if (!city.isEmpty() && !country.isEmpty()) return city + ", " + country;
                if (!country.isEmpty()) return country;
            }
        } catch (Exception e) {
            log.warn("GeoIP lookup failed for {}: {}", ip, e.getMessage());
        }
        return "Unknown";
    }
}
