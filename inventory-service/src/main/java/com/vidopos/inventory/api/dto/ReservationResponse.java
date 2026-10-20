package com.vidopos.inventory.api.dto;

import com.vidopos.inventory.domain.Reservation;
import com.vidopos.inventory.domain.ReservationStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID orderId,
        ReservationStatus status,
        List<Item> items
) {
    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }

    public static ReservationResponse from(Reservation reservation) {
        List<Item> items = reservation.getItems().stream()
                .map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new ReservationResponse(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getStatus(),
                items
        );
    }
}
