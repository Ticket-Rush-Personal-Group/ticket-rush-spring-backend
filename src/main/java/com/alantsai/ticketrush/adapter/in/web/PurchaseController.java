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

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(PurchaseResponse.from(result)));
    }
}
