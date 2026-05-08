-- Chatbot Messages Table for conversation history
CREATE TABLE IF NOT EXISTS chatbot_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_message TEXT NOT NULL,
    bot_response TEXT NOT NULL,
    message_type VARCHAR(50) NOT NULL DEFAULT 'GENERAL',
    context_data JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for faster queries
CREATE INDEX idx_chatbot_user_id ON chatbot_messages(user_id);
CREATE INDEX idx_chatbot_created_at ON chatbot_messages(created_at DESC);
CREATE INDEX idx_chatbot_user_created ON chatbot_messages(user_id, created_at DESC);

-- Comment
COMMENT ON TABLE chatbot_messages IS 'Stores conversation history between users and the AI chatbot';
COMMENT ON COLUMN chatbot_messages.message_type IS 'Type of response: GENERAL, CONTEXTUAL, ERROR, SUGGESTION';
COMMENT ON COLUMN chatbot_messages.context_data IS 'JSON data: projects, tests, user info used for contextual answers';
