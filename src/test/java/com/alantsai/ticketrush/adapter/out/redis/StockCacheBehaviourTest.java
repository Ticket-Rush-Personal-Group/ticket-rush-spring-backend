package com.alantsai.ticketrush.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.application.port.out.PreDeductResult;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Lua 預扣腳本的行為驗證(對真實 Redis 容器)。
 *
 * <p>驗四種回傳結果、每種結果下兩個 key 的實際值,以及**併發下的原子性**。
 *
 * <p><b>「失敗時兩個 key 都不得變動」這件事必須驗。</b> 一個只檢查回傳碼的測試,
 * 對「拒絕了但庫存已扣」完全沒有防護能力 —— 而那正是預扣策略最糟的失效方式:
 * 票沒賣出去,庫存卻消失了。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockCacheBehaviourTest {

    private static final EventId EVENT_ID = new EventId(7L);
    private static final UserId USER_ID = new UserId(1L);
    private static final int LIMIT = 4;

    @Autowired
    private StockCachePort stockCachePort;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void clearKeys() {
        Set<String> keys = redis.keys("stock:*");
        keys.addAll(redis.keys("purchased:*"));
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("庫存 key 不存在時回 NOT_ON_SALE,不得視為庫存無限")
    void missingStockKeyIsNotOnSale() {
        PreDeductResult result = stockCachePort.preDeduct(EVENT_ID, USER_ID, one(), LIMIT);

        assertThat(result).isEqualTo(PreDeductResult.NOT_ON_SALE);
        // 缺 key 時不得留下任何痕跡——尤其不得建立已購數。
        assertThat(purchasedInRedis()).isNull();
    }

    @Test
    @DisplayName("預扣成功:庫存減少、已購數增加")
    void successDeductsStockAndRecordsPurchase() {
        givenStock(500);

        PreDeductResult result = stockCachePort.preDeduct(EVENT_ID, USER_ID, new Quantity(2), LIMIT);

        assertThat(result).isEqualTo(PreDeductResult.SUCCESS);
        assertThat(stockInRedis()).isEqualTo("498");
        assertThat(purchasedInRedis()).isEqualTo("2");
    }

    @Test
    @DisplayName("超過限購時回 LIMIT_EXCEEDED,且兩個 key 都不變")
    void limitExceededChangesNothing() {
        givenStock(500);
        stockCachePort.preDeduct(EVENT_ID, USER_ID, new Quantity(3), LIMIT);

        PreDeductResult result = stockCachePort.preDeduct(EVENT_ID, USER_ID, new Quantity(2), LIMIT);

        assertThat(result).isEqualTo(PreDeductResult.LIMIT_EXCEEDED);
        // 只檢查回傳碼會漏掉「拒絕了但庫存已扣」——票沒賣出去，庫存卻消失了。
        assertThat(stockInRedis()).isEqualTo("497");
        assertThat(purchasedInRedis()).isEqualTo("3");
    }

    @Test
    @DisplayName("庫存不足時回 INSUFFICIENT_STOCK,且兩個 key 都不變")
    void insufficientStockChangesNothing() {
        givenStock(1);

        PreDeductResult result = stockCachePort.preDeduct(EVENT_ID, USER_ID, new Quantity(3), LIMIT);

        assertThat(result).isEqualTo(PreDeductResult.INSUFFICIENT_STOCK);
        assertThat(stockInRedis()).isEqualTo("1");
        assertThat(purchasedInRedis()).isNull();
    }

    @Test
    @DisplayName("同時超限且庫存不足時回 LIMIT_EXCEEDED —— 檢查順序與前三層一致")
    void limitIsCheckedBeforeStock() {
        givenStock(1);

        // 上限 4、一次買 5：兩個條件同時不成立。
        // 四層的檢查順序必須相同，否則同一組輸入在不同層會得到不同的錯誤碼。
        PreDeductResult result = stockCachePort.preDeduct(EVENT_ID, USER_ID, new Quantity(5), LIMIT);

        assertThat(result).isEqualTo(PreDeductResult.LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("200 併發搶 100 張:成功恰好 100、餘量恰好 0")
    void preDeductIsAtomicUnderConcurrency() throws Exception {
        int stock = 100;
        int threads = 200;
        givenStock(stock);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                // 每個執行緒用不同的使用者，否則會被限購擋下而測不到庫存的競爭。
                final UserId user = new UserId(i + 1L);
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (stockCachePort.preDeduct(EVENT_ID, user, one(), LIMIT) == PreDeductResult.SUCCESS) {
                        succeeded.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        // 用「恰好」而非「不超過」——後者在腳本完全失效、一張都沒扣的情況下也會通過。
        assertThat(succeeded.get()).as("成功次數應恰好等於庫存量").isEqualTo(stock);
        assertThat(stockInRedis()).as("餘量應恰好為 0").isEqualTo("0");
    }

    @Test
    @DisplayName("同一人 200 併發:成功恰好等於限購上限 —— 限購的原子性")
    void limitIsAtomicUnderConcurrency() throws Exception {
        givenStock(500);
        int threads = 200;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (stockCachePort.preDeduct(EVENT_ID, USER_ID, one(), LIMIT) == PreDeductResult.SUCCESS) {
                        succeeded.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        // 限購檢查若移出 Lua 腳本（讀 → 判斷 → 扣），這裡就會超買——
        // 兩個併發請求讀到相同的已購數並各自通過檢查，與第 0 層的缺陷同源。
        assertThat(succeeded.get()).as("同一人的成功次數應恰好等於限購上限").isEqualTo(LIMIT);
        assertThat(purchasedInRedis()).isEqualTo(String.valueOf(LIMIT));
        assertThat(stockInRedis()).as("庫存只應被扣掉成交的部分").isEqualTo(String.valueOf(500 - LIMIT));
    }

    @Test
    @DisplayName("已購數的到期時間**晚於**庫存 —— 不是相同")
    void purchasedExpiresAfterStock() {
        givenStockWithTtl(500, Duration.ofHours(24));

        stockCachePort.preDeduct(EVENT_ID, USER_ID, one(), LIMIT);

        Long stockTtl = redis.getExpire(RedisKeys.stock(EVENT_ID));
        Long purchasedTtl = redis.getExpire(RedisKeys.purchased(EVENT_ID, USER_ID));

        assertThat(purchasedTtl).as("已購數必須有過期時間").isNotNull().isPositive();
        // 相同的到期時間並不安全：Redis 的過期是惰性 + 抽樣的，
        // 兩個同時到期的 key 完全可能一個已消失、另一個還在——
        // 而其中一半的可能性是「已購數先消失」，那會讓限購額度歸零而超買。
        assertThat(purchasedTtl).as("已購數必須確定活得比庫存久,而不是同時到期").isGreaterThan(stockTtl);
    }

    @Test
    @DisplayName("庫存沒有過期時間時,已購數也不設 —— 保持一致")
    void purchasedHasNoTtlWhenStockHasNone() {
        givenStock(500);

        stockCachePort.preDeduct(EVENT_ID, USER_ID, one(), LIMIT);

        // 一致地都不過期，而不是只有其中一個過期——後者有一半機率走向不安全的方向。
        // 這個情況本身是設定遺漏，由對帳的警告負責讓人看見。
        assertThat(redis.getExpire(RedisKeys.stock(EVENT_ID))).isEqualTo(-1);
        assertThat(redis.getExpire(RedisKeys.purchased(EVENT_ID, USER_ID))).isEqualTo(-1);
    }

    @Test
    @DisplayName("回補之後庫存的過期時間仍在 —— 釘住「INCRBY 保留 TTL」這個假設")
    void restorePreservesExpiry() {
        givenStockWithTtl(500, Duration.ofHours(24));
        stockCachePort.preDeduct(EVENT_ID, USER_ID, new Quantity(2), LIMIT);

        stockCachePort.restore(EVENT_ID, USER_ID, new Quantity(2));
        stockCachePort.restoreStockOnly(EVENT_ID, 1);

        // 這條守的不是本專案的邏輯，是本專案對 Redis 的假設：
        // INCRBY/DECRBY 保留 TTL，只有 SET 會清掉它。
        // 把回補改寫成「讀出來、算好、SET 回去」是個看起來完全合理的重構，
        // 而它會無聲地移除過期時間——所有其他測試依然全綠。
        assertThat(redis.getExpire(RedisKeys.stock(EVENT_ID))).as("回補不得清除過期時間").isPositive();
    }

    @Test
    @DisplayName("hasExpiry 正確反映庫存 key 有無過期時間")
    void reportsWhetherStockHasExpiry() {
        givenStock(500);
        assertThat(stockCachePort.hasExpiry(EVENT_ID)).isFalse();

        givenStockWithTtl(500, Duration.ofHours(24));
        assertThat(stockCachePort.hasExpiry(EVENT_ID)).isTrue();
    }

    private void givenStockWithTtl(int available, Duration ttl) {
        redis.opsForValue().set(RedisKeys.stock(EVENT_ID), String.valueOf(available), ttl);
    }

    private Quantity one() {
        return new Quantity(1);
    }

    private void givenStock(int available) {
        redis.opsForValue().set(RedisKeys.stock(EVENT_ID), String.valueOf(available));
    }

    private String stockInRedis() {
        return redis.opsForValue().get(RedisKeys.stock(EVENT_ID));
    }

    private String purchasedInRedis() {
        return redis.opsForValue().get(RedisKeys.purchased(EVENT_ID, USER_ID));
    }
}
