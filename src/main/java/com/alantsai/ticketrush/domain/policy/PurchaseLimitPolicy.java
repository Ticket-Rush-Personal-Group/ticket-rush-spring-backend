package com.alantsai.ticketrush.domain.policy;

import com.alantsai.ticketrush.domain.exception.PurchaseLimitExceededException;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;

/**
 * 單人限購規則:同一使用者在同一場次的累計購買張數不得超過上限。
 *
 * <p><b>計算單位是張數而非訂單筆數</b> —— 一次買 4 張與四次各買 1 張,對限購而言等價。
 * 以訂單筆數計算會讓前者輕易過關,那是限購最典型的實作錯誤之一。
 *
 * <p>本類別**沒有任何框架註解**(由 ArchUnit 強制)。上限值由
 * {@code infrastructure.config} 依設定建立實例時傳入 —— 上限寫死會讓壓測無法調整它,
 * 而上限高低會改變競爭形態:上限越低,同一人的請求被拒越多,鎖競爭的分布就不同。
 *
 * <p><b>本規則在無鎖策略下會被併發突破</b>,成因與超賣相同(lost update):
 * 兩個併發請求讀到相同的已購數、各自通過檢查。這是刻意保留的第二組證據,見
 * {@code strategy-no-lock} 的 spec。
 */
public record PurchaseLimitPolicy(int maxTicketsPerUser) {

    public PurchaseLimitPolicy {
        if (maxTicketsPerUser <= 0) {
            throw new IllegalArgumentException("限購上限必須大於 0,實際為 " + maxTicketsPerUser);
        }
    }

    /**
     * 確認本次購買不會使累計張數超過上限。
     *
     * @param userId 購買者
     * @param alreadyPurchased 該使用者在該場次已購買的張數
     * @param requesting 本次請求的張數
     * @throws PurchaseLimitExceededException 累計張數超過上限時
     */
    public void ensureWithinLimit(UserId userId, int alreadyPurchased, Quantity requesting) {
        if (alreadyPurchased < 0) {
            throw new IllegalArgumentException("已購張數不得為負,實際為 " + alreadyPurchased);
        }
        int total = alreadyPurchased + requesting.value();
        if (total > maxTicketsPerUser) {
            throw new PurchaseLimitExceededException(userId, alreadyPurchased, requesting.value(), maxTicketsPerUser);
        }
    }
}
