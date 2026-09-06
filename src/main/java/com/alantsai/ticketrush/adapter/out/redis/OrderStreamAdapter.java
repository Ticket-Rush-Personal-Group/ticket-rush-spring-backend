package com.alantsai.ticketrush.adapter.out.redis;

import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.out.OrderStreamPort;
import com.alantsai.ticketrush.application.port.out.StreamBacklog;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 以 Redis Stream 投遞待落庫的訂單。
 *
 * <p><b>選 Stream 而非 List,是為了讓「對帳收斂」這個驗收有意義。</b> Stream 有 consumer group、
 * ack 與 pending 清單:消費者崩潰時訊息仍留在 pending,可被重新領取。
 * 若載體本身會無聲掉單(如 {@code BLPOP} 取出即離開),對帳永遠不會收斂,
 * 而那個不收斂是載體造成的,不是設計造成的 —— 兩者混在一起就查不出來了。
 *
 * <p>欄位以字串儲存。Stream 的 entry 本身就是 field-value map,再包一層 JSON 只會多一次
 * 序列化與一組要維護的欄位名稱。
 */
@Component
public class OrderStreamAdapter implements OrderStreamPort {

    static final String FIELD_EVENT_ID = "eventId";
    static final String FIELD_USER_ID = "userId";
    static final String FIELD_QUANTITY = "quantity";
    static final String FIELD_IDEMPOTENCY_KEY = "idempotencyKey";

    private final StringRedisTemplate redis;
    private final String streamKey;
    private final String consumerGroup;

    public OrderStreamAdapter(
            StringRedisTemplate redis,
            @Value("${ticket-rush.redis.stream}") String streamKey,
            @Value("${ticket-rush.redis.consumer-group}") String consumerGroup) {
        this.redis = redis;
        this.streamKey = streamKey;
        this.consumerGroup = consumerGroup;
    }

    @Override
    public void publish(PurchaseTicketCommand command) {
        Map<String, String> payload = Map.of(
                FIELD_EVENT_ID, String.valueOf(command.eventId().value()),
                FIELD_USER_ID, String.valueOf(command.userId().value()),
                FIELD_QUANTITY, String.valueOf(command.quantity().value()),
                FIELD_IDEMPOTENCY_KEY, command.idempotencyKey().value());

        redis.opsForStream().add(StreamRecords.mapBacked(payload).withStreamKey(streamKey));
    }

    /**
     * 積壓量 = 已投遞未 ack + 是否有未投遞。
     *
     * <p><b>未投遞的部分只能靠比對 id 得知</b>:群組的 {@code last-delivered-id}
     * 與串流的 {@code last-generated-id} 不同,就代表還有訊息沒被任何消費者讀走。
     * {@code XPENDING} 看不到它們 —— 那是本專案在對帳上最容易踩的陷阱。
     */
    @Override
    public StreamBacklog backlog() {
        XInfoGroup group = redis.opsForStream().groups(streamKey).stream()
                .filter(candidate -> consumerGroup.equals(candidate.groupName()))
                .findFirst()
                .orElse(null);

        // 群組尚不存在：還沒有任何訊息被投遞過，也就沒有東西在飛。
        if (group == null) {
            return new StreamBacklog(0, false);
        }

        String lastGenerated = redis.opsForStream().info(streamKey).lastGeneratedId();
        boolean hasUndelivered = !Objects.equals(lastGenerated, group.lastDeliveredId());

        return new StreamBacklog(group.pendingCount() == null ? 0 : group.pendingCount(), hasUndelivered);
    }
}
