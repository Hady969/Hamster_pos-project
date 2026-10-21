CREATE TABLE orders (
    id                  UUID PRIMARY KEY,
    customer_email      VARCHAR(320) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    total_amount        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    idempotency_key     VARCHAR(128) NOT NULL UNIQUE,
    request_hash        VARCHAR(64) NOT NULL,
    correlation_id      VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE order_items (
    id                  UUID PRIMARY KEY,
    order_id            UUID NOT NULL REFERENCES orders(id),
    product_id          UUID NOT NULL,
    quantity            INTEGER NOT NULL CHECK (quantity > 0),
    purchase_price      NUMERIC(19, 2) NOT NULL CHECK (purchase_price >= 0)
);

CREATE INDEX idx_orders_status_updated_at ON orders(status, updated_at);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
