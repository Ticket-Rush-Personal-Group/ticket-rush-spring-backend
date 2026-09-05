## MODIFIED Requirements

### Requirement: 錯誤碼是領域概念而非 HTTP 狀態的複述

`code` MUST 描述**發生了什麼事**,MUST NOT 只是 HTTP 狀態的名稱。

`INSUFFICIENT_STOCK` 說明了原因,`CONFLICT` 沒有 —— 後者只是把客戶端已經從狀態碼知道的資訊重複一次。客戶端要能單憑 `code` 決定如何處理,而不需要額外解析 `message`(訊息是給人看的,可能被翻譯或改寫)。

**同一個 HTTP 狀態可以對應多個錯誤碼,那正是錯誤碼存在的理由。** 本專案的 `409` 就有三種:庫存不足、超過限購、重複請求 —— 客戶端對這三者的處理方式完全不同(換場次 / 放棄 / 忽略重試)。

本專案目前的對應:

| 例外情境 | HTTP | code |
| --- | --- | --- |
| 請求格式不合法 | 400 | `INVALID_REQUEST` |
| 缺少使用者標頭 | 400 | `MISSING_USER_ID` |
| 場次不存在 | 404 | `EVENT_NOT_FOUND` |
| 超過單人限購上限 | 409 | `PURCHASE_LIMIT_EXCEEDED` |
| 庫存不足 | 409 | `INSUFFICIENT_STOCK` |
| 冪等鍵重複 | 409 | `DUPLICATE_REQUEST` |
| 未預期的錯誤 | 500 | `INTERNAL_ERROR` |

#### Scenario: 新增錯誤情境

- **WHEN** 新增一種失敗情境
- **THEN** 其 `code` MUST 於 spec 中先行定名,再進行實作 —— 實作階段才命名會讓碼名淪為當下的臨時選擇

#### Scenario: 錯誤碼不重複 HTTP 語意

- **WHEN** 檢視任一錯誤碼
- **THEN** 它 SHALL NOT 為 `CONFLICT`、`BAD_REQUEST`、`NOT_FOUND` 這類 HTTP 狀態的直譯

#### Scenario: 同一狀態碼的多種錯誤

- **WHEN** 客戶端收到 `409`
- **THEN** 它 SHALL 能單憑 `code` 分辨是庫存不足、超過限購、還是重複請求,無需解析 `message`
