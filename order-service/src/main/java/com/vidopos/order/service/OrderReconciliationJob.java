package com.vidopos.order.service;

import com.vidopos.order.config.CorrelationIdFilter;
import com.vidopos.order.config.OrderReconciliationProperties;
import com.vidopos.order.domain.Order;
import com.vidopos.order.domain.OrderStatus;
import com.vidopos.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OrderReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationJob.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OrderReconciliationProperties properties;

    public OrderReconciliationJob(
            OrderRepository orderRepository,
            OrderService orderService,
            OrderReconciliationProperties properties
    ) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${order.reconciliation.fixed-delay-ms:5000}")
    public void reconcilePendingOrders() {
        Instant cutoff = Instant.now().minus(properties.getPendingAge());
        List<Order> pending = orderRepository.findPendingOlderThan(
                OrderStatus.PENDING,
                cutoff,
                PageRequest.of(0, properties.getBatchSize())
        );

        for (Order order : pending) {
            String correlationId = order.getCorrelationId() != null
                    ? order.getCorrelationId()
                    : UUID.randomUUID().toString();
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            try {
                log.info("Reconciling PENDING order id={}", order.getId());
                orderService.reconcile(order.getId());
            } catch (Exception ex) {
                log.warn("Failed to reconcile order id={}: {}", order.getId(), ex.getMessage());
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }
}
