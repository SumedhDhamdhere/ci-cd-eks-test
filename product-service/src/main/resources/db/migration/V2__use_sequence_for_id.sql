DO $$
DECLARE
    next_val BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1 INTO next_val FROM products;
    EXECUTE format('CREATE SEQUENCE products_seq INCREMENT BY 50 START WITH %s', next_val);
END $$;

ALTER TABLE products ALTER COLUMN id SET DEFAULT nextval('products_seq');
ALTER SEQUENCE products_seq OWNED BY products.id;
