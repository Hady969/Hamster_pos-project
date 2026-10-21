package com.vidopos.order.api.dto;

import com.vidopos.order.domain.Order;
import com.vidopos.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String customerEmail,
        OrderStatus status,
        BigDecimal total,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt
) {
    public record Item(UUID productId, int quantity, BigDecimal purchasePrice) {
    }

    public static OrderResponse from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getProductId(), i.getQuantity(), i.getPurchasePrice()))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getCustomerEmail(),
                order.getStatus(),
                order.getTotalAmount(),
                items,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
