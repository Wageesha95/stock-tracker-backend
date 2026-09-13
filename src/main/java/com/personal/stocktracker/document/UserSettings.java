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

    /**
     * Annual rate, as a percentage, used for the opportunity-cost calculation — the
     * interest the money tied up in each buy lot could have earned instead. Stored as
     * a percentage (6.5 means 6.5%/yr) to match what the user types and what the
     * dashboard reports back as bankInterestRate.
     */
    @Builder.Default
    private Double opportunityCostRate = DEFAULT_OPPORTUNITY_COST_RATE;

    /** Typical local savings rate; the value the feature shipped with. */
    public static final double DEFAULT_OPPORTUNITY_COST_RATE = 6.5;

    /**
     * The configured rate as a fraction ready to multiply into an interest formula,
     * falling back to the default for settings documents written before the rate
     * existed (where the field reads back as null).
     */
    public double opportunityCostRateFraction() {
        return (opportunityCostRate != null ? opportunityCostRate : DEFAULT_OPPORTUNITY_COST_RATE) / 100.0;
    }
}
