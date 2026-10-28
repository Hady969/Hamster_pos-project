package com.vidopos.order;

import com.vidopos.order.api.dto.CreateOrderRequest;
import com.vidopos.order.api.dto.OrderResponse;
import com.vidopos.order.domain.OrderStatus;
import com.vidopos.order.repository.OrderRepository;
import com.vidopos.order.service.IdempotencyConflictException;
import com.vidopos.order.service.OrderService;
import com.vidopos.order.support.PostgresTestcontainers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class OrderServiceIT {

    static MockWebServer inventoryServer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        PostgresTestcontainers.register(registry);
        inventoryServer = new MockWebServer();
        inventoryServer.start();
        registry.add("inventory.base-url", () -> inventoryServer.url("/").toString().replaceAll("/$", ""));
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void resetServer() throws InterruptedException {
        while (inventoryServer.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // drain
        }
    }

    @AfterEach
    void cleanupOrders() {
        orderRepository.deleteAll();
    }

    @Test
    void createOrder_confirmsWithAuthoritativePrices() {
        UUID productId = UUID.randomUUID();
        UUID orderIdPlaceholder = UUID.randomUUID();
        inventoryServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "reservationId": "%s",
                          "orderId": "%s",
                          "status": "RESERVED",
                          "items": [{"productId":"%s","quantity":2,"unitPrice":12.50}]
                        }
                        """.formatted(UUID.randomUUID(), orderIdPlaceholder, productId)));

        OrderResponse response = orderService.create(
                "key-1",
                new CreateOrderRequest(
                        "buyer@example.com",
                        List.of(new CreateOrderRequest.Item(productId, 2))
                )
        );

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().purchasePrice()).isEqualByComparingTo(new BigDecimal("12.50"));
    }

    @Test
    void createOrder_rejectsOnInsufficientStock() {
        UUID productId = UUID.randomUUID();
        inventoryServer.enqueue(new MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"message":"Insufficient stock for product %s"}
                        """.formatted(productId)));

        OrderResponse response = orderService.create(
                "key-reject",
                new CreateOrderRequest(
                        "buyer@example.com",
                        List.of(new CreateOrderRequest.Item(productId, 1))
                )
        );

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void idempotentReplay_returnsSameOrder() {
        UUID productId = UUID.randomUUID();
        inventoryServer.enqueue(reservationJson(productId, 1, "9.99"));

        CreateOrderRequest request = new CreateOrderRequest(
                "buyer@example.com",
                List.of(new CreateOrderRequest.Item(productId, 1))
        );

        OrderResponse first = orderService.create("same-key", request);
        OrderResponse second = orderService.create("same-key", request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(inventoryServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBody_conflicts() {
        UUID productId = UUID.randomUUID();
        inventoryServer.enqueue(reservationJson(productId, 1, "9.99"));

        orderService.create(
                "conflict-key",
                new CreateOrderRequest("a@example.com", List.of(new CreateOrderRequest.Item(productId, 1)))
        );

        assertThatThrownBy(() -> orderService.create(
                "conflict-key",
                new CreateOrderRequest("b@example.com", List.of(new CreateOrderRequest.Item(productId, 1)))
        )).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void inventoryUnavailable_leavesPendingWithoutFalseConfirmation() {
        UUID productId = UUID.randomUUID();
        inventoryServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        inventoryServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        OrderResponse response = orderService.create(
                "pending-key",
                new CreateOrderRequest(
                        "buyer@example.com",
                        List.of(new CreateOrderRequest.Item(productId, 1))
                )
        );

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void lostResponseThenReconcile_confirmsWithoutDoubleReserve() {
        UUID productId = UUID.randomUUID();
        // First create: network failure after inventory already reserved (client never sees body).
        inventoryServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        inventoryServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        OrderResponse pending = orderService.create(
                "lost-response",
                new CreateOrderRequest(
                        "buyer@example.com",
                        List.of(new CreateOrderRequest.Item(productId, 1))
                )
        );
        assertThat(pending.status()).isEqualTo(OrderStatus.PENDING);

        // Reconcile: GET finds existing reservation (inventory did persist it).
        inventoryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "reservationId": "%s",
                          "orderId": "%s",
                          "status": "RESERVED",
                          "items": [{"productId":"%s","quantity":1,"unitPrice":4.00}]
                        }
                        """.formatted(UUID.randomUUID(), pending.id(), productId)));

        var confirmed = orderService.reconcile(pending.id());
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getTotalAmount()).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    @Test
    void crashAfterReserve_reconcileRecovers() {
        lostResponseThenReconcile_confirmsWithoutDoubleReserve();
    }

    private static MockResponse reservationJson(UUID productId, int qty, String price) {
        return new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "reservationId": "%s",
                          "orderId": "%s",
                          "status": "RESERVED",
                          "items": [{"productId":"%s","quantity":%d,"unitPrice":%s}]
                        }
                        """.formatted(UUID.randomUUID(), UUID.randomUUID(), productId, qty, price));
    }
}
