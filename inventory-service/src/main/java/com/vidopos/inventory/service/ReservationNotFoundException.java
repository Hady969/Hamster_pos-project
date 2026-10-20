package com.vidopos.inventory.service;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID orderId) {
        super("Reservation not found for order: " + orderId);
    }
}
