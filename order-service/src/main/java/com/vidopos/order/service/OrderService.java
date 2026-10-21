package com.vidopos.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidopos.order.api.dto.CreateOrderRequest;
import com.vidopos.order.api.dto.OrderResponse;
import com.vidopos.order.client.InventoryClient;
import com.vidopos.order.client.InventoryClientException;
import com.vidopos.order.client.InventoryDtos;
import com.vidopos.order.config.CorrelationIdFilter;
import com.vidopos.order.domain.Order;
import com.vidopos.order.domain.OrderItem;
import com.vidopos.order.domain.OrderStatus;
import com.vidopos.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OrderService(
            OrderRepository orderRepository,
            InventoryClient inventoryClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public OrderResponse create(String idempotencyKey, CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        String requestHash = RequestHashUtil.hash(objectMapper, request);
        Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey.trim());
        if (existing.isPresent()) {
            Order order = existing.get();
            if (!order.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            log.info("Idempotent create-order hit key={} orderId={}", idempotencyKey, order.getId());
            return OrderResponse.from(order);
        }

        Order pending;
        try {
            pending = transactionTemplate.execute(status -> {
                Order order = new Order(
                        UUID.randomUUID(),
                        request.customerEmail().trim().toLowerCase(),
                        idempotencyKey.trim(),
                        requestHash,
                        MDC.get(CorrelationIdFilter.MDC_KEY)
                );
                // Persist requested lines with placeholder price 0 until reservation returns authoritative prices.
                for (CreateOrderRequest.Item item : request.items()) {
                    order.addItem(new OrderItem(UUID.randomUUID(), item.productId(), item.quantity(), BigDecimal.ZERO));
                }
                return orderRepository.saveAndFlush(order);
            });
        } catch (DataIntegrityViolationException ex) {
            // Concurrent create with the same Idempotency-Key lost the unique-key race.
            Order winner = orderRepository.findByIdempotencyKey(idempotencyKey.trim())
                    .orElseThrow(() -> ex);
            if (!winner.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            log.info("Idempotent create-order race resolved key={} orderId={}", idempotencyKey, winner.getId());
            return OrderResponse.from(winner);
        }

        log.info("Created PENDING order id={} key={}", pending.getId(), idempotencyKey);
        return OrderResponse.from(processReservation(pending.getId()));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        return OrderResponse.from(order);
    }

    /**
     * Attempts reservation and moves the order to CONFIRMED or REJECTED.
     * Transient inventory failures leave the order PENDING for reconciliation.
     */
    public Order processReservation(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            return order;
        }

        if (order.getCorrelationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, order.getCorrelationId());
        }

        InventoryDtos.ReserveRequest reserveRequest = new InventoryDtos.ReserveRequest(
                order.getId(),
                order.getItems().stream()
                        .map(i -> new InventoryDtos.ReserveRequest.Item(i.getProductId(), i.getQuantity()))
                        .toList()
        );

        try {
            InventoryDtos.ReservationResponse reservation = inventoryClient.reserve(reserveRequest);
            return confirmFromReservation(orderId, reservation);
        } catch (InventoryClientException ex) {
            if (ex.getHttpStatus() != null && ex.getHttpStatus() == HttpStatus.CONFLICT.value()) {
                return reject(orderId, "Insufficient stock");
            }
            if (ex.getHttpStatus() != null && ex.getHttpStatus() == HttpStatus.NOT_FOUND.value()) {
                return reject(orderId, "Product not found");
            }
            if (ex.getHttpStatus() != null && ex.getHttpStatus() == HttpStatus.BAD_REQUEST.value()) {
                return reject(orderId, "Invalid reservation request");
            }
            // Transient: leave PENDING; reconciler / caller may retry. Never confirm optimistically.
            log.warn("Inventory unavailable for orderId={}: {}", orderId, ex.getMessage());
            return orderRepository.findById(orderId).orElseThrow();
        }
    }

    /**
     * Recovery path: inspect inventory reservation state and converge the order.
     */
    public Order reconcile(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PENDING) {
            return order;
        }

        if (order.getCorrelationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, order.getCorrelationId());
        }

        try {
            Optional<InventoryDtos.ReservationResponse> existing = inventoryClient.findReservation(orderId);
            if (existing.isPresent() && "RESERVED".equalsIgnoreCase(existing.get().status())) {
                log.info("Reconcile found existing reservation for orderId={}", orderId);
                return confirmFromReservation(orderId, existing.get());
            }
            // No reservation yet (or released): retry reserve. Inventory is idempotent on orderId.
            return processReservation(orderId);
        } catch (InventoryClientException ex) {
            log.warn("Reconcile deferred for orderId={}: {}", orderId, ex.getMessage());
            return order;
        }
    }

    private Order confirmFromReservation(UUID orderId, InventoryDtos.ReservationResponse reservation) {
        return transactionTemplate.execute(status -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
            if (order.getStatus() != OrderStatus.PENDING) {
                return order;
            }

            List<OrderItem> pricedItems = reservation.items().stream()
                    .map(i -> new OrderItem(UUID.randomUUID(), i.productId(), i.quantity(), i.unitPrice()))
                    .toList();
            BigDecimal total = pricedItems.stream()
                    .map(i -> i.getPurchasePrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            order.applyReservationPrices(pricedItems, total);
            order.confirm();
            Order saved = orderRepository.save(order);
            log.info("Order CONFIRMED id={} total={}", saved.getId(), saved.getTotalAmount());
            return saved;
        });
    }

    private Order reject(UUID orderId, String reason) {
        return transactionTemplate.execute(status -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId));
            if (order.getStatus() != OrderStatus.PENDING) {
                return order;
            }
            order.reject();
            Order saved = orderRepository.save(order);
            log.info("Order REJECTED id={} reason={}", orderId, reason);
            return saved;
        });
    }
}
