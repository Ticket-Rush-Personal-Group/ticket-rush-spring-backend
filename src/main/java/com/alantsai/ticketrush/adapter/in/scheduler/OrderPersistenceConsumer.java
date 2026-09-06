package com.alantsai.ticketrush.adapter.in.scheduler;

import com.alantsai.ticketrush.application.port.in.PersistOutcome;
import com.alantsai.ticketrush.application.port.in.PersistPendingOrderUseCase;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 消費 Redis Stream 的待落庫訂單。**第 3 層非同步流程的執行者。**
 *
 * <p><b>本類別是入站 adapter,只呼叫 in port。</b> 它不得直接使用 out port ——
 * 那會讓 adapter 跨過 application 直接碰持久化,與 controller 不得繞過 facade 是同一條原則。
 *
 * <p><b>沒有 {@code @Transactional}。</b> 交易邊界在 {@code PersistPendingOrderService},
 * 而且必須在那裡:ack 是 Redis 的操作,它若被納入資料庫交易,會產生一個
 * 「交易回滾了但訊息已 ack」的組合 —— 訂單沒進去,訊息也消失了。
 *
 * <p><b>ack 的順序是本類別最重要的部分:</b>
 *
 * <table border="1">
 *   <caption>處置</caption>
 *   <tr><th>情況</th><th>動作</th><th>為什麼</th></tr>
 *   <tr><td>落庫成功</td><td>ack</td><td>正常完成</td></tr>
 *   <tr><td>冪等鍵重複</td><td>ack,<b>不回補</b></td>
 *       <td>那筆庫存已經賣出去了,回補它就是超賣</td></tr>
 *   <tr><td>落庫失敗</td><td>回補後 ack(第 5 塊)</td><td>庫存被扣了卻永遠不會有訂單</td></tr>
 *   <tr><td>消費者崩潰</td><td>不 ack</td><td>訊息留在 pending,可被重新領取</td></tr>
 * </table>
 */
@Component
public class OrderPersistenceConsumer
        implements StreamListener<String, MapRecord<String, String, String>>, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistenceConsumer.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final int BATCH_SIZE = 64;

    private final PersistPendingOrderUseCase persistPendingOrderUseCase;
    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private final String streamKey;
    private final String consumerGroup;
    private final String consumerName;

    /** 冪等鍵擋下的重複落庫次數。**大於 0 代表崩潰窗口真的發生過** —— 那是量測項目,不是錯誤。 */
    private final AtomicLong duplicatesBlocked = new AtomicLong();

    /** 落庫失敗後回補的次數。非同步落庫的失敗成本,同樣是量測項目。 */
    private final AtomicLong compensations = new AtomicLong();

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean running;

    public OrderPersistenceConsumer(
            PersistPendingOrderUseCase persistPendingOrderUseCase,
            StringRedisTemplate redis,
            RedisConnectionFactory connectionFactory,
            @Value("${ticket-rush.redis.stream}") String streamKey,
            @Value("${ticket-rush.redis.consumer-group}") String consumerGroup,
            @Value("${ticket-rush.redis.consumer-name}") String consumerName) {
        this.persistPendingOrderUseCase = persistPendingOrderUseCase;
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
    }

    /**
     * 以 {@link SmartLifecycle} 管理啟停,而非 {@code @PostConstruct} / {@code @PreDestroy}。
     *
     * <p><b>順序是關鍵。</b> Spring 會先停止 Lifecycle bean,之後才銷毀 bean ——
     * 用 {@code @PreDestroy} 停止容器時,Redis 連線工廠可能已經先被銷毀,
     * 而輪詢執行緒還在跑,於是拋出 {@code RedisException: Connection closed}。
     * 那個錯誤指向連線,實際原因是關閉順序。
     */
    @Override
    public void start() {
        ensureConsumerGroup();

        container = StreamMessageListenerContainer.create(
                connectionFactory,
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(POLL_TIMEOUT)
                        .batchSize(BATCH_SIZE)
                        .build());

        // receive(...) 而非 receiveAutoAck(...)：自動 ack 會在訊息「送達」時就確認，
        // 而不是在「落庫成功」時。那等於把 pending 清單變成裝飾品——
        // 崩潰的訊息不會留下，對帳也就永遠不可能收斂。
        container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                this);
        container.start();
        running = true;
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 相位越高越晚啟動、越早停止 —— 消費者必須在 Redis 連線相關的基礎設施之前停下來。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        PurchaseTicketCommand command = toCommand(record.getValue());

        try {
            // 所有決策都在 application：本類別只負責「讀訊息 → 交出去 → ack」。
            // 入站 adapter 不得直接呼叫 out port——補償是 Redis 的寫入，
            // 讓 adapter 自己做，等於跨過 application 決定業務行為。
            PersistOutcome outcome = persistPendingOrderUseCase.persist(command);
            switch (outcome) {
                case DUPLICATE -> duplicatesBlocked.incrementAndGet();
                case COMPENSATED -> compensations.incrementAndGet();
                case PERSISTED -> {
                    // 正常完成。
                }
            }
            // 三種結果都 ack：它們都代表「這則訊息處理完畢」。
            // 已回補的訊息若留在 pending，會讓對帳因保守而停止回補，差額就卡住不動。
            acknowledge(record);
        } catch (RuntimeException e) {
            // 走到這裡代表連回補都失敗了。**不 ack** ——
            // 訊息留在 pending，由重新領取或對帳收拾。
            log.error("落庫與回補皆失敗,訊息留在 pending", e);
        }
    }

    /** 冪等鍵擋下的重複落庫次數。供整合測試與壓測讀取。 */
    public long duplicatesBlocked() {
        return duplicatesBlocked.get();
    }

    /** 落庫失敗後回補的次數。供整合測試與壓測讀取。 */
    public long compensations() {
        return compensations.get();
    }

    private void acknowledge(MapRecord<String, String, String> record) {
        redis.opsForStream().acknowledge(streamKey, consumerGroup, record.getId());
    }

    private PurchaseTicketCommand toCommand(Map<String, String> fields) {
        return new PurchaseTicketCommand(
                new EventId(Long.parseLong(fields.get("eventId"))),
                new UserId(Long.parseLong(fields.get("userId"))),
                new Quantity(Integer.parseInt(fields.get("quantity"))),
                new IdempotencyKey(fields.get("idempotencyKey")));
    }

    /**
     * 建立 consumer group,並在 stream 尚不存在時一併建立它。
     *
     * <p>{@code mkStream = true} 不可省:第一次啟動時還沒有任何訂單被投遞,stream 不存在,
     * 不加這個旗標會直接失敗 —— 而症狀是「應用起不來」,看起來與 Redis 設定錯誤一模一樣。
     *
     * <p>群組已存在時 Redis 回 {@code BUSYGROUP} 錯誤。**那是重啟時的正常路徑,不是失敗** ——
     * 群組本來就該留著,pending 清單也是。
     */
    private void ensureConsumerGroup() {
        try {
            redis.execute((RedisCallback<String>) connection -> connection
                    .streamCommands()
                    .xGroupCreate(
                            streamKey.getBytes(StandardCharsets.UTF_8), consumerGroup, ReadOffset.from("0"), true));
        } catch (RuntimeException e) {
            // BUSYGROUP 的訊息在最根本的 cause 裡，不在 Spring 包裝後的外層例外上——
            // 檢查 e.getMessage() 會漏掉它，症狀是「應用重啟後起不來」。
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                return;
            }
            throw e;
        }
    }
}
