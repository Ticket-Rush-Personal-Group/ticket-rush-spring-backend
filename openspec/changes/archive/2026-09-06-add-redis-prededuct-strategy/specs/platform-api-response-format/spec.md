## MODIFIED Requirements

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
