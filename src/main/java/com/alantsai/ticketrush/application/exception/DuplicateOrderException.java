package com.alantsai.ticketrush.application.exception;

import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;

/**
 * 相同冪等鍵的訂單已存在。
 *
 * <p><b>由持久化 adapter 翻譯自資料庫的唯一約束違反。</b> application 層不得直接辨識
 * {@code DataIntegrityViolationException} 與約束名稱 —— 那是持久化的細節,
 * 而 application 依賴 adapter 會違反分層方向(由 ArchUnit 強制)。
 * **adapter 的職責之一就是把基礎設施的例外翻譯成應用層看得懂的語彙。**
 *
 * <p>對第 3 層而言,這個例外**不是失敗**:它代表那則訊息先前已經成功落庫過,
 * 而票已經賣出去了。**把它當成落庫失敗會觸發回補,那就是超賣。**
 */
public class DuplicateOrderException extends RuntimeException {

    private final transient IdempotencyKey idempotencyKey;

    public DuplicateOrderException(IdempotencyKey idempotencyKey, Throwable cause) {
        super("冪等鍵 %s 的訂單已存在".formatted(idempotencyKey.value()), cause);
        this.idempotencyKey = idempotencyKey;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }
}
