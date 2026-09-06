package com.alantsai.ticketrush.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.application.port.out.PreDeductResult;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
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
