package com.vidopos.order.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record ReserveRequest(UUID orderId, List<Item> items) {
        public record Item(UUID productId, Integer quantity) {
        }
    }

    public record ReservationResponse(
            UUID reservationId,
            UUID orderId,
            String status,
            List<Item> items
    ) {
        public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
        }
    }
}
