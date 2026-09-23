CREATE TABLE akim.anonymous_users (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT anonymous_user_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE akim.simulations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES akim.anonymous_users(id),
    created_at TIMESTAMPTZ NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    request JSONB NOT NULL,
    result JSONB NOT NULL,
    final_score NUMERIC NOT NULL,
    baseline_score NUMERIC NOT NULL,
    score_delta NUMERIC NOT NULL,
    budget_spent INTEGER NOT NULL CHECK (budget_spent BETWEEN 0 AND 100),
    CONSTRAINT simulation_request_object CHECK (jsonb_typeof(request) = 'object'),
    CONSTRAINT simulation_five_decisions CHECK (
        request ? 'decisions'
        AND jsonb_typeof(request -> 'decisions') = 'array'
        AND jsonb_array_length(request -> 'decisions') = 5
    ),
    CONSTRAINT simulation_result_object CHECK (jsonb_typeof(result) = 'object')
);

CREATE INDEX simulations_user_history_idx
    ON akim.simulations (user_id, created_at DESC, id DESC);
