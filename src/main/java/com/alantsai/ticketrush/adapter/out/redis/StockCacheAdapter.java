package com.alantsai.ticketrush.adapter.out.redis;

import com.alantsai.ticketrush.application.port.out.PreDeductResult;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 快取庫存閘門的 Redis 實作。
 *
 * <p>此處沒有 {@code @Transactional} —— 本層的交易邊界不存在:Redis 不參與資料庫交易,
 * 而這正是第 3 層與前三層最根本的差異(見 {@code backend-architecture.md})。
 *
 * <p><b>本類別是「Redis 回傳碼」這個協定的唯一邊界。</b> 腳本回傳整數,
 * 而整數的意義只在這裡被翻譯成 {@link PreDeductResult} —— 讓 {@code -2} 這種東西
 * 出現在 application service 裡,會使「它是什麼意思」變成一個要跨層才答得出來的問題。
 */
@Component
public class StockCacheAdapter implements StockCachePort {

    // 以 int 而非 long 宣告:Java 的 switch 不接受 long selector。
    // 腳本回傳的是 Redis 的整數型別，Spring Data 以 Long 承接，此處縮為 int 比對。
    private static final int CODE_SUCCESS = 1;
    private static final int CODE_LIMIT_EXCEEDED = -1;
    private static final int CODE_INSUFFICIENT_STOCK = -2;
    private static final int CODE_NOT_ON_SALE = -3;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> preDeductScript;
    private final RedisScript<Long> restoreScript;

    // 兩個腳本的型別相同，靠參數名稱對應 bean 名稱注入。
    // 型別相同的多個 bean 若不以名稱區分，啟動時會拋 NoUniqueBeanDefinitionException。
    public StockCacheAdapter(
            StringRedisTemplate redis, RedisScript<Long> preDeductScript, RedisScript<Long> restoreScript) {
        this.redis = redis;
        this.preDeductScript = preDeductScript;
        this.restoreScript = restoreScript;
    }

    @Override
    public PreDeductResult preDeduct(EventId eventId, UserId userId, Quantity quantity, int maxTicketsPerUser) {
        Long code = redis.execute(
                preDeductScript,
                List.of(RedisKeys.stock(eventId), RedisKeys.purchased(eventId, userId)),
                String.valueOf(quantity.value()),
                String.valueOf(maxTicketsPerUser));

        return toResult(code);
    }

    @Override
    public void restore(EventId eventId, UserId userId, Quantity quantity) {
        redis.execute(
                restoreScript,
                List.of(RedisKeys.stock(eventId), RedisKeys.purchased(eventId, userId)),
                String.valueOf(quantity.value()));
    }

    /**
     * 只回補庫存。單一 {@code INCRBY} 本身即為原子操作,不需要 Lua。
     *
     * <p>不動已購數 —— 對帳不知道那些扣減屬於哪些使用者。詳見 port 的說明。
     */
    @Override
    public void restoreStockOnly(EventId eventId, int quantity) {
        redis.opsForValue().increment(RedisKeys.stock(eventId), quantity);
    }

    @Override
    public OptionalInt available(EventId eventId) {
        String value = redis.opsForValue().get(RedisKeys.stock(eventId));
        return value == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(value));
    }

    /**
     * 以 {@code SCAN} 而非 {@code KEYS} 列舉開賣中的場次。
     *
     * <p>{@code KEYS} 會阻塞整個 Redis 直到掃完 —— 而 Redis 是單執行緒的,
     * 那段時間**所有預扣都在排隊**。對帳是背景工作,不該有能力拖垮購票路徑。
     * 資料量小的時候兩者看起來一樣快,差別只在出事的時候。
     */
    @Override
    public Set<EventId> eventsOnSale() {
        Set<EventId> events = new HashSet<>();
        try (Cursor<String> cursor =
                redis.scan(ScanOptions.scanOptions().match("stock:*").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                events.add(new EventId(Long.parseLong(key.substring("stock:".length()))));
            }
        }
        return events;
    }

    @Override
    public int purchasedBy(EventId eventId, UserId userId) {
        String value = redis.opsForValue().get(RedisKeys.purchased(eventId, userId));
        return value == null ? 0 : Integer.parseInt(value);
    }

    /**
     * 把腳本的回傳碼翻譯為結果。
     *
     * <p>未知的碼**拋出例外而非當作失敗**。當作失敗會讓「腳本被改壞了」看起來像
     * 「大家都買不到票」—— 一個安靜的錯誤,而且在壓測數據上會呈現為超高的拒絕率,
     * 很容易被誤讀成競爭激烈。
     */
    private PreDeductResult toResult(Long code) {
        if (code == null) {
            throw new IllegalStateException("預扣腳本沒有回傳值 —— 腳本可能未被執行");
        }
        return switch (code.intValue()) {
            case CODE_SUCCESS -> PreDeductResult.SUCCESS;
            case CODE_LIMIT_EXCEEDED -> PreDeductResult.LIMIT_EXCEEDED;
            case CODE_INSUFFICIENT_STOCK -> PreDeductResult.INSUFFICIENT_STOCK;
            case CODE_NOT_ON_SALE -> PreDeductResult.NOT_ON_SALE;
            default -> throw new IllegalStateException("預扣腳本回傳了未知的碼:" + code);
        };
    }
}
