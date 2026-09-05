package com.alantsai.ticketrush.application.facade;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 持有當前生效的併發策略名稱。
 *
 * <p>以 {@code volatile} 欄位持有,讓切換能立即對所有執行緒可見 —— 壓測要跑完四種策略,
 * 每次切換都重啟應用會使流程極為痛苦。
 *
 * <p>Phase 1 僅由設定檔初始化;Phase 2 的管理介面才開放執行期修改。
 *
 * <p><b>虛擬執行緒無法以此方式切換</b>:{@code spring.threads.virtual.enabled} 是啟動時設定。
 * 因此八組壓測數據的取得方式是「啟動兩次,每次跑完四種策略」。
 */
@Component
public class StrategyRegistry {

    private volatile String current;

    public StrategyRegistry(@Value("${ticket-rush.strategy:noLock}") String initial) {
        this.current = initial;
    }

    public String current() {
        return current;
    }

    public void switchTo(String strategyName) {
        this.current = strategyName;
    }
}
