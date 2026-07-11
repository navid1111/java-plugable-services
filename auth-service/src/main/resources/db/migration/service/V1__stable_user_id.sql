CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL
);
ALTER TABLE users ADD COLUMN IF NOT EXISTS user_id UUID;
UPDATE users SET user_id = gen_random_uuid() WHERE user_id IS NULL;
ALTER TABLE users ALTER COLUMN user_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_user_id ON users(user_id);
