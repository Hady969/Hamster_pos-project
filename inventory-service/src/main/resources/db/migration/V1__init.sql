CREATE TABLE products (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    price           NUMERIC(19, 2) NOT NULL CHECK (price >= 0),
    available_qty   INTEGER NOT NULL CHECK (available_qty >= 0),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL UNIQUE,
    status          VARCHAR(32) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE reservation_items (
    id              UUID PRIMARY KEY,
    reservation_id  UUID NOT NULL REFERENCES reservations(id),
    product_id      UUID NOT NULL REFERENCES products(id),
    quantity        INTEGER NOT NULL CHECK (quantity > 0),
    unit_price      NUMERIC(19, 2) NOT NULL CHECK (unit_price >= 0)
);

CREATE INDEX idx_reservation_items_reservation_id ON reservation_items(reservation_id);
