package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_settings")
public class UserSettings {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    @Builder.Default
    private List<String> selectedBrokerIds = new ArrayList<>();

    // Brokers to filter dashboard / transaction data by. Empty = no filter (show all).
    // May contain the token "__none__" to include manually-entered (no-broker) transactions.
    @Builder.Default
    private List<String> selectedDataBrokerIds = new ArrayList<>();

    @Builder.Default
    private Map<String, List<String>> tableColumns = new HashMap<>();

    @Builder.Default
    private Map<String, Integer> companyTtmWeeks = new HashMap<>();
}
