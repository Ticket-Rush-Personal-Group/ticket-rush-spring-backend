-- 場次
CREATE TABLE event (
    id             BIGSERIAL    PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    sales_start_at TIMESTAMPTZ  NOT NULL,
    total_quantity INTEGER      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_event_total_quantity_positive CHECK (total_quantity > 0)
);

-- 庫存:獨立於 event 成表,而非作為 event 的欄位。
-- available 是全系統競爭最激烈的一列;若與場次名稱同表,鎖住庫存等同鎖住整列場次資料,
-- 連讀取活動名稱都要排隊。這個切分本身就是併發設計的一部分。
CREATE TABLE stock (
    event_id  BIGINT  PRIMARY KEY REFERENCES event (id),
    available INTEGER NOT NULL,
    -- 樂觀鎖使用(第 7 支)。現在納入是因為它是已定案的需求,不是預留彈性。
    version   BIGINT  NOT NULL DEFAULT 0,
    -- 基礎設施的第二道防線。領域規則(扣減不得超過可用量)定義於 domain 層,
    -- 不依賴本約束。
    --
    -- 本約束不會攔截無鎖對照組的超賣:那是 lost update(多執行緒讀到相同的 available,
    -- 各自計算後寫回同一個值),available 全程不會變成負數。超賣的定義是
    -- 「累計賣出張數超過總量」,不是「庫存為負」。
    CONSTRAINT ck_stock_available_non_negative CHECK (available >= 0)
);

-- 訂單。表名為 purchase_order 而非 order —— order 是 SQL 保留字,
-- 使用它會讓每一次 JPQL、原生 SQL、psql 查詢都必須加引號,
-- 而漏加時的錯誤訊息會指向下一個 token,不會指出保留字才是原因。
CREATE TABLE purchase_order (
    id              BIGSERIAL   PRIMARY KEY,
    event_id        BIGINT      NOT NULL REFERENCES event (id),
    user_id         BIGINT      NOT NULL,
    quantity        INTEGER     NOT NULL,
    status          VARCHAR(20) NOT NULL,
    -- 由客戶端產生。在有重試機制的系統中這是必要條件而非加分項:
    -- 樂觀鎖策略必然重試,Redis 預扣的補償機制也會重試。
    idempotency_key VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    CONSTRAINT ck_purchase_order_quantity_positive CHECK (quantity > 0),
    CONSTRAINT uq_purchase_order_idempotency_key UNIQUE (idempotency_key)
);

-- 單人限購上限的檢查(第 5 支)會以 (event_id, user_id) 聚合查詢。
CREATE INDEX idx_purchase_order_event_user ON purchase_order (event_id, user_id);
