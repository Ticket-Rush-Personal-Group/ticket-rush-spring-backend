## ADDED Requirements

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

`code` MUST 描述**發生了什麼事**,MUST NOT 只是 HTTP 狀態的名稱。

`INSUFFICIENT_STOCK` 說明了原因,`CONFLICT` 沒有 —— 後者只是把客戶端已經從狀態碼知道的資訊重複一次。客戶端要能單憑 `code` 決定如何處理,而不需要額外解析 `message`(訊息是給人看的,可能被翻譯或改寫)。

本專案目前的對應:

| 例外情境 | HTTP | code |
| --- | --- | --- |
| 請求格式不合法 | 400 | `INVALID_REQUEST` |
| 缺少使用者標頭 | 400 | `MISSING_USER_ID` |
| 場次不存在 | 404 | `EVENT_NOT_FOUND` |
| 庫存不足 | 409 | `INSUFFICIENT_STOCK` |
| 冪等鍵重複 | 409 | `DUPLICATE_REQUEST` |
| 未預期的錯誤 | 500 | `INTERNAL_ERROR` |

#### Scenario: 新增錯誤情境

- **WHEN** 新增一種失敗情境
- **THEN** 其 `code` MUST 於 spec 中先行定名,再進行實作 —— 實作階段才命名會讓碼名淪為當下的臨時選擇

#### Scenario: 錯誤碼不重複 HTTP 語意

- **WHEN** 檢視任一錯誤碼
- **THEN** 它 SHALL NOT 為 `CONFLICT`、`BAD_REQUEST`、`NOT_FOUND` 這類 HTTP 狀態的直譯

---

### Requirement: 未預期的錯誤不得洩漏內部資訊

未被明確處理的例外 MUST 回應 500 與泛用訊息。SQL 語句、堆疊軌跡、內部類別名稱 MUST NOT 出現在回應中。

#### Scenario: 未預期的例外

- **WHEN** 發生未被 `@RestControllerAdvice` 明確處理的例外
- **THEN** 回應 SHALL 為 500、`code` 為 `INTERNAL_ERROR`、`message` 為泛用文字
- **AND** 詳細資訊 SHALL 只寫入伺服器日誌,不回傳給客戶端
