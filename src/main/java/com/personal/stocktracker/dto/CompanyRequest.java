package com.personal.stocktracker.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompanyRequest {

    @NotBlank(message = "Company code is required")
    private String code;

    @NotBlank(message = "Company name is required")
    private String name;

    private String industryGroupId;
}
