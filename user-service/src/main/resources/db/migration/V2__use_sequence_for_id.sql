DO $$
DECLARE
    next_val BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 1 INTO next_val FROM users;
    EXECUTE format('CREATE SEQUENCE users_seq INCREMENT BY 50 START WITH %s', next_val);
END $$;

ALTER TABLE users ALTER COLUMN id SET DEFAULT nextval('users_seq');
ALTER SEQUENCE users_seq OWNED BY users.id;
