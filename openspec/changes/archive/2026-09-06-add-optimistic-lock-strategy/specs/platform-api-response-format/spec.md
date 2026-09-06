## MODIFIED Requirements

### Requirement: 錯誤碼是領域概念而非 HTTP 狀態的複述

`code` SHALL 描述**發生了什麼事**,而非把客戶端已從 HTTP 狀態得知的資訊重複一次。客戶端 MUST 能單憑 `code` 決定處理方式,無需解析 `message` —— 訊息是給人看的,可能被翻譯或改寫。

**同一個 HTTP 狀態可以對應多個錯誤碼,那正是錯誤碼存在的理由。** 本專案的 `409` 現有四種:庫存不足、超過限購、重複請求、重試耗盡 —— 客戶端對這四者的處理方式完全不同(換場次 / 放棄 / 忽略 / 重送)。

本專案目前的對應:

| 例外情境 | HTTP | code |
| --- | --- | --- |
| 請求格式不合法 | 400 | `INVALID_REQUEST` |
| 缺少使用者標頭 | 400 | `MISSING_USER_ID` |
| 場次不存在 | 404 | `EVENT_NOT_FOUND` |
| 超過單人限購上限 | 409 | `PURCHASE_LIMIT_EXCEEDED` |
| 庫存不足 | 409 | `INSUFFICIENT_STOCK` |
| 冪等鍵重複 | 409 | `DUPLICATE_REQUEST` |
| **樂觀鎖重試耗盡** | **409** | **`RETRY_EXHAUSTED`** |
| 未預期的錯誤 | 500 | `INTERNAL_ERROR` |

**`RETRY_EXHAUSTED` MUST NOT 併入 `INSUFFICIENT_STOCK`。** 兩者的意義相反:庫存不足是「沒票了」,重試耗盡是「有票,但你在版本競爭中連續搶輸到達上限」。客戶端對後者的合理反應是**重送**,對前者則是放棄或換場次 —— 合併會讓客戶端做出錯誤的決定,而這正是錯誤碼要防止的事。

訊息 MUST NOT 洩漏重試次數、版本號等內部細節。

#### Scenario: 新增錯誤情境

- **WHEN** 新增一種失敗情境
- **THEN** 其 `code` MUST 於 spec 中先行定名,再進行實作 —— 實作階段才命名會讓碼名淪為當下的臨時選擇

#### Scenario: 錯誤碼不重複 HTTP 語意

- **WHEN** 檢視任一錯誤碼
- **THEN** 它 SHALL NOT 為 `CONFLICT`、`BAD_REQUEST`、`NOT_FOUND` 這類 HTTP 狀態的直譯

#### Scenario: 同一狀態碼的多種錯誤

- **WHEN** 客戶端收到 `409`
- **THEN** 它 SHALL 能單憑 `code` 分辨是庫存不足、超過限購、重複請求、還是重試耗盡,無需解析 `message`

#### Scenario: 重試耗盡與庫存不足的處置不同

- **WHEN** 客戶端收到 `RETRY_EXHAUSTED`
- **THEN** 該情境 SHALL 表示庫存仍可能存在,重送是合理的下一步
- **AND** 它 SHALL NOT 與 `INSUFFICIENT_STOCK` 使用同一個 code
