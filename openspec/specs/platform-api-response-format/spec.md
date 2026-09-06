# platform-api-response-format Specification

## Purpose
TBD - created by archiving change add-purchase-api-no-lock. Update Purpose after archive.
## Requirements
### Requirement: 統一的成功回應 wrapper

所有 REST 端點的成功回應 MUST 使用統一 wrapper:

```json
{
  "success": true,
  "data": { },
  "timestamp": "2026-09-06T06:00:00.000Z"
}
```

`timestamp` MUST 為 UTC 的 ISO-8601 格式。

**當回傳值為空時,`data` 這個 key MUST 整個不存在**,而非 `"data": null`。兩者對客戶端是不同的訊號:缺少 key 表示「本操作沒有回傳內容」,`null` 表示「有這個欄位但值為空」。

#### Scenario: 有回傳內容的成功回應

- **WHEN** 端點成功且有資料回傳
- **THEN** 回應 SHALL 包含 `success`、`data`、`timestamp` 三個 key

#### Scenario: 無回傳內容的成功回應

- **WHEN** 端點成功但無資料回傳
- **THEN** 回應 SHALL 只包含 `success` 與 `timestamp`,`data` 這個 key SHALL 不存在

---

### Requirement: 統一的失敗回應 wrapper

所有失敗回應 MUST 使用統一 wrapper,且 MUST NOT 包含 `data`:

```json
{
  "success": false,
  "message": "庫存不足",
  "code": "INSUFFICIENT_STOCK",
  "timestamp": "2026-09-06T06:00:00.000Z"
}
```

失敗回應 MUST 由 `@RestControllerAdvice` 統一產生,MUST NOT 在個別 controller 內組裝 —— 分散組裝必然漂移,而漂移的症狀是「某幾個端點的錯誤格式跟其他的不一樣」,客戶端要為此寫特例。

#### Scenario: 領域例外轉為失敗回應

- **WHEN** application service 拋出領域例外
- **THEN** 回應 SHALL 為對應的 HTTP 狀態,且 body 符合失敗 wrapper

#### Scenario: 失敗回應不含 data

- **WHEN** 任何失敗回應產生
- **THEN** body SHALL 不包含 `data` key

---

### Requirement: 錯誤碼是領域概念而非 HTTP 狀態的複述

`code` SHALL 描述**發生了什麼事**,而非把客戶端已從 HTTP 狀態得知的資訊重複一次。客戶端 MUST 能單憑 `code` 決定處理方式,無需解析 `message`。

**同一個 HTTP 狀態可以對應多個錯誤碼,那正是錯誤碼存在的理由。** 本專案的 `409` 現有五種:庫存不足、超過限購、重複請求、重試耗盡、場次未開賣 —— 客戶端對這五者的處理方式完全不同(換場次 / 放棄 / 忽略 / 重送 / 稍後再來)。

本專案目前的對應:

| 例外情境 | HTTP | code |
| --- | --- | --- |
| 請求格式不合法 | 400 | `INVALID_REQUEST` |
| 缺少使用者標頭 | 400 | `MISSING_USER_ID` |
| 場次不存在 | 404 | `EVENT_NOT_FOUND` |
| 超過單人限購上限 | 409 | `PURCHASE_LIMIT_EXCEEDED` |
| 庫存不足 | 409 | `INSUFFICIENT_STOCK` |
| 冪等鍵重複 | 409 | `DUPLICATE_REQUEST` |
| 樂觀鎖重試耗盡 | 409 | `RETRY_EXHAUSTED` |
| **場次未載入快取庫存** | **409** | **`EVENT_NOT_ON_SALE`** |
| 未預期的錯誤 | 500 | `INTERNAL_ERROR` |

**`EVENT_NOT_ON_SALE` MUST NOT 併入 `EVENT_NOT_FOUND` 或 `INSUFFICIENT_STOCK`。** 三者的意義各不相同:

| code | 意義 | 客戶端該做什麼 |
| --- | --- | --- |
| `EVENT_NOT_FOUND` | 這個場次不存在 | 檢查連結是否有誤 |
| `EVENT_NOT_ON_SALE` | 場次存在,但尚未開賣 | **稍後再來** |
| `INSUFFICIENT_STOCK` | 已開賣,票賣完了 | 放棄或換場次 |

把「還沒開賣」說成「找不到」會讓使用者以為連結壞了;說成「賣完了」則會讓他直接放棄一場根本還沒開始賣的活動。**兩種誤導都會讓使用者做出錯誤的決定,而那正是錯誤碼要防止的事。**

訊息 MUST NOT 洩漏內部細節(重試次數、版本號、快取 key 名稱等)。

#### Scenario: 新增錯誤情境

- **WHEN** 新增一種失敗情境
- **THEN** 其 `code` MUST 於 spec 中先行定名,再進行實作 —— 實作階段才命名會讓碼名淪為當下的臨時選擇

#### Scenario: 錯誤碼不重複 HTTP 語意

- **WHEN** 檢視任一錯誤碼
- **THEN** 它 SHALL NOT 為 `CONFLICT`、`BAD_REQUEST`、`NOT_FOUND` 這類 HTTP 狀態的直譯

#### Scenario: 同一狀態碼的多種錯誤

- **WHEN** 客戶端收到 `409`
- **THEN** 它 SHALL 能單憑 `code` 分辨是庫存不足、超過限購、重複請求、重試耗盡、還是場次未開賣,無需解析 `message`

#### Scenario: 重試耗盡與庫存不足的處置不同

- **WHEN** 客戶端收到 `RETRY_EXHAUSTED`
- **THEN** 該情境 SHALL 表示庫存仍可能存在,重送是合理的下一步
- **AND** 它 SHALL NOT 與 `INSUFFICIENT_STOCK` 使用同一個 code

#### Scenario: 未開賣與不存在的處置不同

- **WHEN** 客戶端收到 `EVENT_NOT_ON_SALE`
- **THEN** 該情境 SHALL 表示場次存在但尚未開賣,稍後再來是合理的下一步
- **AND** 它 SHALL NOT 與 `EVENT_NOT_FOUND` 或 `INSUFFICIENT_STOCK` 使用同一個 code

### Requirement: 未預期的錯誤不得洩漏內部資訊

未被明確處理的例外 MUST 回應 500 與泛用訊息。SQL 語句、堆疊軌跡、內部類別名稱 MUST NOT 出現在回應中。

#### Scenario: 未預期的例外

- **WHEN** 發生未被 `@RestControllerAdvice` 明確處理的例外
- **THEN** 回應 SHALL 為 500、`code` 為 `INTERNAL_ERROR`、`message` 為泛用文字
- **AND** 詳細資訊 SHALL 只寫入伺服器日誌,不回傳給客戶端

