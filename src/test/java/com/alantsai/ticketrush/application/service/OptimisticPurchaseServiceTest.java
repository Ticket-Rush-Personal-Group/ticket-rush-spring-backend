package com.alantsai.ticketrush.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alantsai.ticketrush.application.exception.RetryExhaustedException;
import com.alantsai.ticketrush.application.metrics.RetryStatistics;
import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.exception.PurchaseLimitExceededException;
import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.OrderId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 樂觀鎖重試迴圈的單元測試(mock 單次嘗試,不啟動 Spring、不連資料庫)。
 *
 * <p>這裡驗證的是**迴圈的決策**:什麼情況重試、什麼情況立刻放棄、什麼情況算耗盡,
 * 以及每種情況記進統計的嘗試次數對不對。CAS 本身的行為由
 * {@code OptimisticCasBehaviourTest} 驗證,併發正確性由 {@code OptimisticLockCorrectnessTest} 驗證。
 *
 * <p>{@link RetryStatistics} 使用真實實例而非 mock:它沒有外部依賴,而且
 * 「哪些請求該被記進分佈」正是本測試要驗的事之一 —— mock 掉就驗不到了。
 */
@ExtendWith(MockitoExtension.class)
class OptimisticPurchaseServiceTest {

    private static final EventId EVENT_ID = new EventId(7L);
    private static final UserId USER_ID = new UserId(1L);
    private static final int MAX_ATTEMPTS = 5;
    private static final int LIMIT = 4;

    @Mock
    private LoadEventPort loadEventPort;

    @Mock
    private OptimisticPurchaseAttempt attempt;

    private RetryStatistics statistics;
    private OptimisticPurchaseService service;

    @BeforeEach
    void setUp() {
        statistics = new RetryStatistics(MAX_ATTEMPTS);
        service = new OptimisticPurchaseService(loadEventPort, attempt, statistics, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("一次就成功:嘗試 1 次,分佈記在第 1 桶,無耗盡")
    void succeedsOnFirstAttempt() {
        givenEventExists();
        when(attempt.tryPurchase(any())).thenReturn(Optional.of(result()));

        PurchaseResult purchased = service.purchase(command());

        assertThat(purchased).isNotNull();
        verify(attempt, times(1)).tryPurchase(any());
        assertThat(statistics.distribution()[1]).isEqualTo(1);
        assertThat(statistics.exhaustedCount()).isZero();
    }

    @Test
    @DisplayName("兩次版本衝突後成功:嘗試 3 次,分佈記在第 3 桶")
    void retriesUntilCasSucceeds() {
        givenEventExists();
        when(attempt.tryPurchase(any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(result()));

        service.purchase(command());

        verify(attempt, times(3)).tryPurchase(any());
        assertThat(statistics.distribution()[3]).isEqualTo(1);
        assertThat(statistics.maxObservedAttempts()).isEqualTo(3);
        assertThat(statistics.exhaustedCount()).isZero();
    }

    @Test
    @DisplayName("達到上限仍衝突:拋 RetryExhaustedException,耗盡另計且同時記入分佈")
    void throwsWhenRetriesAreExhausted() {
        givenEventExists();
        when(attempt.tryPurchase(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchase(command()))
                .isInstanceOf(RetryExhaustedException.class)
                .hasMessageContaining(String.valueOf(MAX_ATTEMPTS));

        verify(attempt, times(MAX_ATTEMPTS)).tryPurchase(any());
        assertThat(statistics.exhaustedCount()).isEqualTo(1);
        // 耗盡的請求確實嘗試了上限次數，排除在分佈外會低估尾端。
        assertThat(statistics.distribution()[MAX_ATTEMPTS]).isEqualTo(1);
    }

    @Test
    @DisplayName("庫存不足不重試 —— 重試一百次也不會變出票來")
    void doesNotRetryOnInsufficientStock() {
        givenEventExists();
        when(attempt.tryPurchase(any())).thenThrow(new InsufficientStockException(EVENT_ID, 0, 1));

        assertThatThrownBy(() -> service.purchase(command())).isInstanceOf(InsufficientStockException.class);

        // 對庫存不足重試會讓售罄後的請求各自重試到上限才放棄，
        // 把重試風暴放大一個數量級，量到的分佈也不再反映真實競爭。
        verify(attempt, times(1)).tryPurchase(any());
        assertThat(statistics.distribution()[1]).isEqualTo(1);
        assertThat(statistics.exhaustedCount()).isZero();
    }

    @Test
    @DisplayName("超過限購不重試")
    void doesNotRetryOnPurchaseLimitExceeded() {
        givenEventExists();
        when(attempt.tryPurchase(any())).thenThrow(new PurchaseLimitExceededException(USER_ID, LIMIT, 1, LIMIT));

        assertThatThrownBy(() -> service.purchase(command())).isInstanceOf(PurchaseLimitExceededException.class);

        verify(attempt, times(1)).tryPurchase(any());
        assertThat(statistics.distribution()[1]).isEqualTo(1);
    }

    @Test
    @DisplayName("場次不存在時不進入迴圈,也不記入統計")
    void rejectsUnknownEventBeforeAnyAttempt() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchase(command())).isInstanceOf(EventNotFoundException.class);

        verify(attempt, never()).tryPurchase(any());
        assertThat(statistics.totalRecorded()).isZero();
    }

    @Test
    @DisplayName("場次只查一次,不隨重試放大 —— 否則重試風暴會把它放大 N 倍")
    void loadsEventOnceRegardlessOfRetries() {
        givenEventExists();
        when(attempt.tryPurchase(any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(result()));

        service.purchase(command());

        // 場次存在與否在一次購票期間不會改變。把它放進迴圈是實作造成的額外負載，
        // 會混進本層要量測的吞吐裡。
        verify(loadEventPort, times(1)).loadEvent(EVENT_ID);
    }

    private void givenEventExists() {
        when(loadEventPort.loadEvent(EVENT_ID))
                .thenReturn(Optional.of(new Event(EVENT_ID, "測試場次", Instant.parse("2026-12-01T12:00:00Z"), 500)));
    }

    private PurchaseTicketCommand command() {
        return new PurchaseTicketCommand(EVENT_ID, USER_ID, new Quantity(1), new IdempotencyKey("key-1"));
    }

    private PurchaseResult result() {
        return new PurchaseResult(new OrderId(1L), EVENT_ID, new Quantity(1), OrderStatus.PENDING);
    }
}
