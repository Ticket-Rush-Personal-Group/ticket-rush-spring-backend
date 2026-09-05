package com.alantsai.ticketrush.domain.exception;

import com.alantsai.ticketrush.domain.valueobject.UserId;

/**
 * 超過單人限購上限。
 *
 * <p>訊息刻意包含「超出幾張」而不只是「超過了」—— 呼叫端(以及看 log 的人)通常需要知道
 * 差多少才能決定下一步:差 1 張與差 10 張,對使用者是完全不同的處境。
 */
public class PurchaseLimitExceededException extends RuntimeException {

    private final transient UserId userId;
    private final int alreadyPurchased;
    private final int requesting;
    private final int limit;

    public PurchaseLimitExceededException(UserId userId, int alreadyPurchased, int requesting, int limit) {
        super("超過單人限購:使用者 %d 已購 %d 張,本次請求 %d 張,上限 %d 張(超出 %d 張)"
                .formatted(userId.value(), alreadyPurchased, requesting, limit, alreadyPurchased + requesting - limit));
        this.userId = userId;
        this.alreadyPurchased = alreadyPurchased;
        this.requesting = requesting;
        this.limit = limit;
    }

    public UserId userId() {
        return userId;
    }

    public int alreadyPurchased() {
        return alreadyPurchased;
    }

    public int requesting() {
        return requesting;
    }

    public int limit() {
        return limit;
    }
}
