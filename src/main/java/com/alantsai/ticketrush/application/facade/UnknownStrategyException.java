package com.alantsai.ticketrush.application.facade;

import java.util.Set;

/**
 * 指定的併發策略不存在。
 *
 * <p>這是設定錯誤而非領域錯誤,因此不放在 {@code domain.exception}。
 * 訊息中列出所有可用的策略名稱 —— 設定打錯字時,「noLock 不存在」遠不如
 * 「noLock2 不存在,可用的有 [noLock]」有用。
 */
public class UnknownStrategyException extends IllegalStateException {

    public UnknownStrategyException(String requested, Set<String> available) {
        super("找不到併發策略「%s」,目前可用的有 %s".formatted(requested, available));
    }
}
