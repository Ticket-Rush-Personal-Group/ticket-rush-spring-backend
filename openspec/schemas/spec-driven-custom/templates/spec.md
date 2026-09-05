<!--
  能力（capability）名稱決定這份 spec 要不要寫 Request / Response 區塊：

    api-*       REST endpoint 契約 → 四段式（Request / Success / Failure / Scenario）必寫
    strategy-*  併發策略實作驗收   → 三段式（正確性驗收 / 效能量測項目 / 與前一層的對照）必寫
    ui-*        管理介面畫面行為   → 不寫 JSON，改述版面、互動、顯示差異
    platform-*  跨切面工程規則     → 不寫 JSON，改述約束與其強制手段

  下方骨架以 api-* 為例。ui-* / platform-* 請刪掉 Request / Success / Failure 三段，
  只留「### Requirement:」＋敘述＋「#### Scenario:」。

  strategy-* 改用三段式，且驗收條件必須可證偽（能直接翻成一條會失敗的測試）：

    **正確性驗收**：如「1000 執行緒同時購買、庫存 500，最終庫存為 0 且成功訂單張數總和為 500」。
                    無鎖對照組是唯一例外——它的驗收是刻意失敗，不可以把它「修好」。
    **效能量測項目**：QPS、P50/P95/P99、錯誤率，加上本層特有指標。
                    每個數字都要附測量條件（策略、執行緒模型、CPU/記憶體限制、
                    max_connections、k6 VU 數與時長）——沒有條件的數字無法比較。
    **與前一層的對照**：改善了什麼、代價是什麼。只有數字沒有解讀不算完成。
-->

## ADDED Requirements

### Requirement: <!-- 需求名稱，中文動詞短語，如「作者列表查詢」 -->

<!--
  一段話交代完：系統 SHALL 提供哪個 method + path、要哪個權限碼、
  有哪些查詢/篩選行為、回應走統一 wrapper。
  用 SHALL / MUST / MUST NOT，不要用 should / may。
-->

**Request**（<!-- query | body | path -->）：

```json
{}
```

**Success Response** `200 OK`：

```json
{
  "success": true,
  "data": {},
  "timestamp": "2026-08-16T06:00:00.000Z"
}
```

**Failure Responses**：

- `409`、`code: "INSUFFICIENT_STOCK"`：庫存不足
- `409`、`code: "PURCHASE_LIMIT_EXCEEDED"`：超過單人限購上限
- <!-- 其餘業務錯誤：`<status>`、`code: "<領域錯誤碼>"`：觸發條件。
     碼名是領域概念，不是 HTTP 狀態的複述 -->

#### Scenario: <!-- 情境名稱 -->

- **WHEN** <!-- 條件 -->
- **THEN** <!-- 預期結果，含 HTTP status 與 code -->

#### Scenario: <!-- 失敗情境也要有 -->

- **WHEN** <!-- 條件 -->
- **THEN** <!-- 預期結果 -->
