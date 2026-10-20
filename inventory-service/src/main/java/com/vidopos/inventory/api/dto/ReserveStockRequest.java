package com.vidopos.inventory.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReserveStockRequest(
        @NotNull UUID orderId,
        @NotEmpty @Valid List<Item> items
) {
    public record Item(
            @NotNull UUID productId,
            @NotNull @Min(1) Integer quantity
    ) {
    }
}
