## ADDED Requirements

### Requirement: 單人購買量上限

系統 SHALL 限制同一使用者在同一場次的累計購買張數。上限值 MUST 可設定,預設為 4 張。

**計算單位是張數而非訂單筆數** —— 一次買 4 張與四次各買 1 張,對限購而言等價。已購張數以該使用者在該場次所有訂單的 `SUM(quantity)` 計算。

檢查 MUST 在讀取庫存**之前**執行。限購檢查只需一次索引查詢,庫存檢查則會進入鎖競爭 —— 把便宜的檢查放前面,被限購擋下的請求就不必參與競爭。在有鎖的策略中,這個順序決定了多少請求會真正去搶那把鎖。

限購規則 MUST 定義於 domain 層且能以純單元測試驗證,MUST NOT 依賴資料庫約束或框架。

#### Scenario: 累計未超過上限

- **WHEN** 使用者在該場次已購 2 張,再購買 2 張(上限 4)
- **THEN** 回應 `201`,訂單成立

#### Scenario: 累計超過上限

- **WHEN** 使用者在該場次已購 3 張,再購買 2 張(上限 4)
- **THEN** 回應 `409` 且 `code` 為 `PURCHASE_LIMIT_EXCEEDED`
- **AND** 不建立訂單,**且不扣減庫存** —— 限購檢查在庫存之前,被擋下的請求不應影響庫存

#### Scenario: 單次請求即超過上限

- **WHEN** 使用者尚未購買,一次請求 5 張(上限 4)
- **THEN** 回應 `409` 且 `code` 為 `PURCHASE_LIMIT_EXCEEDED`

#### Scenario: 限購以張數而非訂單數計算

- **WHEN** 使用者已有 4 筆各 1 張的訂單(上限 4),再購買 1 張
- **THEN** 回應 `409` —— 判定依據是累計 4 張,不是「只有 4 筆訂單」

#### Scenario: 限購為每場次獨立

- **WHEN** 使用者在 A 場次已購滿 4 張,對 B 場次購買 1 張
- **THEN** 回應 `201` —— 上限是「每人每場次」,不是「每人全站」

#### Scenario: 領域規則可獨立驗證

- **WHEN** 以純單元測試(不啟動 Spring、不連資料庫)驗證限購規則
- **THEN** 測試 SHALL 能執行並涵蓋通過與超限兩種情況

## MODIFIED Requirements

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
- `409`、`code: "PURCHASE_LIMIT_EXCEEDED"`:累計購買張數超過單人上限
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

#### Scenario: 失敗檢查的先後順序

- **WHEN** 某請求同時超過限購上限且庫存不足
- **THEN** 回應 SHALL 為 `PURCHASE_LIMIT_EXCEEDED` —— 限購檢查在庫存之前
