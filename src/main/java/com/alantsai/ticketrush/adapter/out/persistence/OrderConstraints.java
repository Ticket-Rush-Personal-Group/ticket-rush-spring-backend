package com.alantsai.ticketrush.adapter.out.persistence;

/**
 * 訂單資料表的約束名稱。
 *
 * <p><b>只有一個使用者:{@code OrderPersistenceAdapter}。</b> 這是刻意的 ——
 * 約束違反在那裡被翻譯成 {@code DuplicateOrderException},之後所有人看到的都是應用層的例外。
 * 落庫的消費者與 HTTP 的例外處理因此不必各自比對約束名稱。
 *
 * <p>本類別存在的理由不是「共用」,而是**讓那個字串有一個名字與一段說明**:
 * 直接寫在 adapter 的 if 裡也能動,但下一個人不會知道
 * 「判斷錯的代價是重複回補一筆已經賣出去的庫存,也就是超賣」。
 */
public final class OrderConstraints {

    /** 冪等鍵的唯一約束,建立於第 2 支的 {@code V1__init_schema.sql}。 */
    public static final String IDEMPOTENCY_KEY = "uq_purchase_order_idempotency_key";

    private OrderConstraints() {}

    /**
     * 判斷某個資料完整性錯誤是否來自冪等鍵重複。
     *
     * @param detail 最根本的例外訊息({@code getMostSpecificCause().getMessage()})
     */
    public static boolean isIdempotencyKeyViolation(String detail) {
        return detail != null && detail.contains(IDEMPOTENCY_KEY);
    }
}
