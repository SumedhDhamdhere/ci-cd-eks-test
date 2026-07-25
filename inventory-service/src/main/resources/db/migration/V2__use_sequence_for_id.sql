DO $$
DECLARE
    next_val BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1 INTO next_val FROM inventory;
    EXECUTE format('CREATE SEQUENCE inventory_seq INCREMENT BY 50 START WITH %s', next_val);
END $$;

ALTER TABLE inventory ALTER COLUMN id SET DEFAULT nextval('inventory_seq');
ALTER SEQUENCE inventory_seq OWNED BY inventory.id;
