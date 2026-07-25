DO $$
DECLARE
    next_val BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1 INTO next_val FROM orders;
    EXECUTE format('CREATE SEQUENCE orders_seq INCREMENT BY 50 START WITH %s', next_val);
END $$;

ALTER TABLE orders ALTER COLUMN id SET DEFAULT nextval('orders_seq');
ALTER SEQUENCE orders_seq OWNED BY orders.id;

DO $$
DECLARE
    next_val BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1 INTO next_val FROM order_items;
    EXECUTE format('CREATE SEQUENCE order_items_seq INCREMENT BY 50 START WITH %s', next_val);
END $$;

ALTER TABLE order_items ALTER COLUMN id SET DEFAULT nextval('order_items_seq');
ALTER SEQUENCE order_items_seq OWNED BY order_items.id;
