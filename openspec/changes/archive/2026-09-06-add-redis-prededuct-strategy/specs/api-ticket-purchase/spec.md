## MODIFIED Requirements

### Requirement: 購票

`POST /api/events/{eventId}/purchase`

系統 SHALL 提供購票端點。使用者身分由 `X-User-Id` 標頭帶入(Phase 1 不做認證)。請求 MUST 攜帶客戶端產生的冪等鍵,系統 SHALL 以它防止重試造成重複訂單。

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

**Success Response —— 同步落庫的策略(第 0 / 1 / 2 層)** `201 Created`:

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

**Success Response —— 非同步落庫的策略(第 3 層 Redis 預扣)** `202 Accepted`:

```json
{
  "success": true,
  "data": {
    "eventId": 7,
    "quantity": 2,
    "idempotencyKey": "b3f1c2a4-7d8e-4f10-9a2b-5c6d7e8f9a0b",
    "status": "PENDING"
  },
  "timestamp": "2026-09-06T06:00:00.000Z"
}
```

**第 3 層 MUST 回 `202`,且回應中 MUST NOT 包含 `orderId` 欄位。** 回應的那一刻訂單**確實還不存在** —— 它只是一則已受理的訊息。

**欄位不出現,而不是出現一個 `null`。** 這與 `platform-api-response-format` 對 `data` 的既有處置一致:缺少 key 表示「沒有這個東西」,`null` 表示「有這個欄位但值為空」。訂單識別碼屬於前者。

相對地,同步落庫的三層 MUST NOT 包含 `idempotencyKey` 欄位 —— **兩者互不干擾,同步策略的回應與第 3 層加入之前完全相同。**

`202 Accepted` 的語意正是「已受理、尚未完成」,精確描述了實際發生的事。**MUST NOT 為了讓四層看起來一致而回 `201`** —— `201 Created` 宣稱資源已建立,而它還沒有。客戶端據此認定訂單存在並立即查詢,會得到查無此單,那是比契約不一致更糟的結果。

第 3 層 SHALL 於 `data` 內回傳 `idempotencyKey`,作為客戶端後續查詢訂單的依據。

**Failure Responses**:

- `400`、`code: "INVALID_REQUEST"`:`quantity` 小於等於 0、`idempotencyKey` 為空或超過 64 字元
- `400`、`code: "MISSING_USER_ID"`:未帶 `X-User-Id` 標頭
- `404`、`code: "EVENT_NOT_FOUND"`:場次不存在
- `409`、`code: "PURCHASE_LIMIT_EXCEEDED"`:累計購買張數超過單人上限
- `409`、`code: "INSUFFICIENT_STOCK"`:可用庫存不足
- `409`、`code: "DUPLICATE_REQUEST"`:相同 `idempotencyKey` 的訂單已存在
- `409`、`code: "RETRY_EXHAUSTED"`:樂觀鎖重試達到上限(僅第 2 層)
- `409`、`code: "EVENT_NOT_ON_SALE"`:場次尚未載入快取庫存(僅第 3 層)

#### Scenario: 庫存充足時購票成功

- **WHEN** 於第 0 / 1 / 2 層(同步落庫)對可用庫存 500 的場次購買 2 張,且冪等鍵未曾使用
- **THEN** 回應 `201`,`data.orderId` 為正整數,`data.status` 為 `PENDING`
- **AND** 該場次的可用庫存減少 2

#### Scenario: 非同步落庫的策略購票成功

- **WHEN** 於第 3 層對可用庫存 500 的場次購買 2 張,且冪等鍵未曾使用
- **THEN** 回應 `202`,`data.orderId` **不存在**,`data.idempotencyKey` 為請求所帶的值
- **AND** Redis 的可用庫存立即減少 2
- **AND** 資料庫的訂單 SHALL 於稍後出現 —— 回應當下尚不存在,這是本層的定義性行為

#### Scenario: 庫存不足時拒絕

- **WHEN** 對可用庫存 1 的場次購買 5 張
- **THEN** 回應 `409` 且 `code` 為 `INSUFFICIENT_STOCK`
- **AND** 庫存維持 1,不建立任何訂單

#### Scenario: 場次不存在

- **WHEN** 對不存在的 `eventId` 購票
- **THEN** 回應 `404` 且 `code` 為 `EVENT_NOT_FOUND`

#### Scenario: 場次尚未載入快取庫存

- **WHEN** 於第 3 層對一個存在於資料庫、但尚未載入 Redis 庫存的場次購票
- **THEN** 回應 `409` 且 `code` 為 `EVENT_NOT_ON_SALE`
- **AND** SHALL NOT 將缺少的 key 視為庫存無限

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

### Requirement: 購票端點不得暴露當前策略

回應 MUST NOT 包含當前使用哪一種併發策略的**識別資訊** —— 不得有策略名稱、代號、或任何供呼叫端判斷「現在是哪一層」的欄位。策略的選擇 MUST 封裝於 `PurchaseFacade` 之內。

**本需求原本要求「成功回應形狀 SHALL 完全相同」,該要求於第 8 支更正。**

原始寫法把兩件事綁在一起:「不得暴露策略身分」與「回應形狀完全相同」。前者成立且必須維持;**後者在第 3 層不成立,而那不是實作偷懶,是流程本身不同** —— 非同步落庫的回應時機早於訂單建立,任何宣稱訂單已建立的回應都是謊報。

更正後的判準:

| 允許 | 不允許 |
| --- | --- |
| 因**流程本質不同**而產生的語意差異(同步 `201` / 非同步 `202`),且差異已寫入本 spec | 回應中出現策略名稱或代號 |
| 呼叫端依 **HTTP 語意**(已建立 / 已受理)分支 | 呼叫端依**策略身分**分支 |

**差異必須是「可從 spec 讀到」而非「必須在執行期試探才知道」。** 前者是契約,後者才是洩漏。

客戶端據 `202` 得知「訂單稍後才會出現」是**必要的**資訊;據策略名稱得知「現在跑悲觀鎖」則毫無正當用途 —— 那才是會讓策略無法自由抽換的東西。

#### Scenario: 回應不含策略識別資訊

- **WHEN** 檢視任一策略的成功或失敗回應
- **THEN** 其中 SHALL NOT 出現策略名稱、代號或等價的識別欄位

#### Scenario: 切換策略不改變回應形狀

- **WHEN** 當前策略在**同步落庫的策略之間**切換(第 0 / 1 / 2 層)
- **THEN** 相同請求的成功回應形狀 SHALL 完全相同,皆為 `201` 且 `data.orderId` 為正整數
- **AND** 切換至第 3 層時回應改為 `202` 且不含 `orderId`,該差異 MUST 來自流程本質且已載明於本 spec ——
  **本 scenario 於第 8 支自「所有策略形狀完全相同」更正為「同步策略之間形狀完全相同」**

#### Scenario: 非同步策略的差異必須寫在契約裡

- **WHEN** 當前策略為第 3 層
- **THEN** 回應 SHALL 為 `202` 且不含 `data.orderId`
- **AND** 該差異 MUST 已載明於本 spec —— 呼叫端 SHALL NOT 需要在執行期試探才能得知
