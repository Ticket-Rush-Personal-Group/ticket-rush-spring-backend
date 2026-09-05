# api-ticket-purchase Specification

## Purpose
TBD - created by archiving change add-purchase-api-no-lock. Update Purpose after archive.
## Requirements
### Requirement: 購票

`POST /api/events/{eventId}/purchase`

系統 SHALL 提供購票端點。使用者身分由 `X-User-Id` 標頭帶入(Phase 1 不做認證)。請求 MUST 攜帶客戶端產生的冪等鍵,系統 SHALL 以它防止重試造成重複訂單。

本端點的實作由當前生效的併發策略決定,呼叫端無從得知也不需得知是哪一種 —— 四層策略共用此契約。

**Request**(path + header + body):

| 位置 | 名稱 | 型別 | 必填 | 說明 |
| --- | --- | --- | --- | --- |
| path | `eventId` | long | ✅ | 場次識別碼 |
| header | `X-User-Id` | long | ✅ | 使用者識別碼 |
| body | `quantity` | int | ✅ | 購買張數,必須大於 0 |
| body | `idempotencyKey` | string | ✅ | 客戶端產生,長度上限 64 |

```json
{
  "quantity": 2,
  "idempotencyKey": "b3f1c2a4-7d8e-4f10-9a2b-5c6d7e8f9a0b"
}
```

**Success Response** `201 Created`:

```json
{
  "success": true,
  "data": {
    "orderId": 42,
    "eventId": 7,
    "quantity": 2,
    "status": "PENDING"
  },
  "timestamp": "2026-09-06T06:00:00.000Z"
}
```

**Failure Responses**:

- `400`、`code: "INVALID_REQUEST"`:`quantity` 小於等於 0、`idempotencyKey` 為空或超過 64 字元
- `400`、`code: "MISSING_USER_ID"`:未帶 `X-User-Id` 標頭
- `404`、`code: "EVENT_NOT_FOUND"`:場次不存在
- `409`、`code: "INSUFFICIENT_STOCK"`:可用庫存不足
- `409`、`code: "DUPLICATE_REQUEST"`:相同 `idempotencyKey` 的訂單已存在

#### Scenario: 庫存充足時購票成功

- **WHEN** 對可用庫存 500 的場次購買 2 張,且冪等鍵未曾使用
- **THEN** 回應 `201`,`data.orderId` 為正整數,`data.status` 為 `PENDING`
- **AND** 該場次的可用庫存減少 2

#### Scenario: 庫存不足時拒絕

- **WHEN** 對可用庫存 1 的場次購買 5 張
- **THEN** 回應 `409` 且 `code` 為 `INSUFFICIENT_STOCK`
- **AND** 庫存維持 1,不建立任何訂單

#### Scenario: 場次不存在

- **WHEN** 對不存在的 `eventId` 購票
- **THEN** 回應 `404` 且 `code` 為 `EVENT_NOT_FOUND`

#### Scenario: 冪等鍵重複

- **WHEN** 以已使用過的 `idempotencyKey` 再次購票
- **THEN** 回應 `409` 且 `code` 為 `DUPLICATE_REQUEST`
- **AND** 不建立第二筆訂單,庫存不再次扣減

#### Scenario: 張數為零或負數

- **WHEN** 請求的 `quantity` 為 0 或負數
- **THEN** 回應 `400` 且 `code` 為 `INVALID_REQUEST`
- **AND** 請求 SHALL 在進入應用服務之前即被拒絕(Bean Validation)

#### Scenario: 未帶使用者標頭

- **WHEN** 請求未包含 `X-User-Id`
- **THEN** 回應 `400` 且 `code` 為 `MISSING_USER_ID`

---

### Requirement: 購票端點不得暴露當前策略

回應 MUST NOT 包含當前使用哪一種併發策略的資訊。策略的選擇 MUST 封裝於 `PurchaseFacade` 之內。

四層策略是同一契約的不同實作。若回應洩漏策略名稱,呼叫端就可能依它分支,策略便不再可自由抽換 —— 那會摧毀「同一個 API、四種實作」這項前提。

#### Scenario: 切換策略不改變回應形狀

- **WHEN** 當前策略由一種切換為另一種
- **THEN** 相同請求的成功回應形狀 SHALL 完全相同,不含任何策略識別資訊

