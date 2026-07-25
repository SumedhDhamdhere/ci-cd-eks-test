DO $$
DECLARE
    next_val BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1 INTO next_val FROM payments;
    EXECUTE format('CREATE SEQUENCE payments_seq INCREMENT BY 50 START WITH %s', next_val);
END $$;

ALTER TABLE payments ALTER COLUMN id SET DEFAULT nextval('payments_seq');
ALTER SEQUENCE payments_seq OWNED BY payments.id;
