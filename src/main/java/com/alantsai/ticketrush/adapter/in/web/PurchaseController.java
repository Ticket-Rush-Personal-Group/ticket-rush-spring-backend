package com.alantsai.ticketrush.adapter.in.web;

import com.alantsai.ticketrush.adapter.in.web.dto.ApiResponse;
import com.alantsai.ticketrush.adapter.in.web.dto.PurchaseRequest;
import com.alantsai.ticketrush.adapter.in.web.dto.PurchaseResponse;
import com.alantsai.ticketrush.application.facade.PurchaseFacade;
import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 購票端點。
 *
 * <p><b>只依賴 {@link PurchaseFacade},對當前是哪一種併發策略一無所知。</b>
 * 這是「同一個 API、四種實作」得以成立的前提 —— 也因此本類別不得注入
 * {@code PurchaseTicketUseCase}(由 ArchUnit 強制)。
 *
 * <p>此處沒有 {@code @Transactional}:交易邊界屬於 application service。標在 controller
 * 會把 HTTP 處理納入交易範圍,而四層策略的差異有一半來自交易邊界的位置。
 */
@RestController
@RequestMapping("/api/events")
public class PurchaseController {

    private final PurchaseFacade purchaseFacade;

    public PurchaseController(PurchaseFacade purchaseFacade) {
        this.purchaseFacade = purchaseFacade;
    }

    @PostMapping("/{eventId}/purchase")
    ResponseEntity<ApiResponse<PurchaseResponse>> purchase(
            @PathVariable long eventId,
            @RequestHeader("X-User-Id") long userId,
            @Valid @RequestBody PurchaseRequest request) {

        PurchaseResult result = purchaseFacade.purchase(new PurchaseTicketCommand(
                new EventId(eventId),
                new UserId(userId),
                new Quantity(request.quantity()),
                new IdempotencyKey(request.idempotencyKey())));

        // 201 Created 或 202 Accepted，依「訂單是否已經建立」決定。
        //
        // **判斷的依據是結果本身，不是策略的身分。** controller 依然對當前是哪一層一無所知——
        // 它只知道「有沒有拿到訂單識別碼」，而那是回應語意的真正來源。
        // 若改成詢問策略名稱再分支，策略就洩漏到 adapter 層了。
        //
        // 非同步落庫的策略回應時訂單確實還不存在，回 201 Created 會是謊報：
        // 客戶端據此認定訂單存在並立即查詢，會得到查無此單——那比契約不一致更糟。
        if (result.orderId() == null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.of(PurchaseResponse.accepted(result, request.idempotencyKey())));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(PurchaseResponse.created(result)));
    }
}
