## Context

第 3 支建立了購票路徑並證明庫存會超賣。本支加入單人限購,並揭露它在無鎖策略下的同型缺陷。

六角分層、domain 不依賴框架、交易邊界歸各策略等既有決策見 `openspec/project/`,此處只寫本支特有的決定。

## Goals / Non-Goals

**Goals:**

- 限購規則,定義於 domain 且可獨立於基礎設施驗證
- 上限值可設定
- 限購在無鎖下被突破的證據

**Non-Goals:**

- **不解決限購的併發問題** —— 那是第 6～8 支各策略的工作。本支只揭露它
- **不做黑名單、裝置指紋、IP 限制** —— 那些是防刷手段,與併發控制無關
- **不做「已購數快取」** —— 過早優化,且會引入新的一致性問題

## Decisions

### D1. `PurchaseLimitPolicy` 是純 Java 值物件,由 config 建立為 bean

```java
public record PurchaseLimitPolicy(int maxTicketsPerUser) {
    public void ensureWithinLimit(UserId userId, int alreadyPurchased, Quantity requesting) { ... }
}
```

類別本身**沒有任何 Spring 註解**(domain 的硬性約束,由 ArchUnit 強制),實例由 `infrastructure.config` 依設定值建立。

**不選「把 `@ConfigurationProperties` 直接放進 domain」**:那會讓 domain 依賴 Spring,破壞「四種策略切換、domain 零改動」的基礎,而且 ArchUnit 會擋。

**不選「把上限寫成 domain 的常數」**:壓測時調整上限會改變競爭形態 —— 上限越低,同一人的請求被拒越多,鎖競爭的分布就不同。那是值得觀察的變因,不該寫死。

### D2. 檢查順序:先限購,後庫存

先查已購張數並檢查上限,通過後才讀取庫存。

**理由是失敗成本**:限購檢查只需要一次索引查詢(`idx_purchase_order_event_user` 已存在);庫存檢查則會進入後續的鎖競爭。把便宜的檢查放前面,被限購擋下的請求就不必參與庫存競爭。

在有鎖的策略(第 6～8 支)這個順序更重要 —— 它決定了多少請求會真正去搶那把鎖。

**代價**:兩次查詢而非一次。可以用單一 SQL 同時取得,但那會讓 out port 的語意變成「查詢購票前置狀態」這種模糊的東西,而各策略需要的前置狀態不同。維持兩個語意清楚的 port。

### D3. 已購張數以 `SUM(quantity)` 計算,不是訂單筆數

限購的單位是**張數**而非訂單數 —— 一次買 4 張與四次各買 1 張,對限購而言應該等價。

`SELECT COALESCE(SUM(quantity), 0) FROM purchase_order WHERE event_id = ? AND user_id = ?`

**目前不排除任何訂單狀態。** Phase 2 引入逾時取消後,已取消的訂單應排除在計算之外 —— 這一點記入 `tasks/todo.md`,不在本支處理(現在只有 PENDING 狀態,排除邏輯無法驗證)。

### D4. 限購突破的證據與超賣證據共用同一個 tag

`@Tag("overselling-evidence")`。

**不為它新增一個 tag**:兩者是同一個問題的兩種表現(lost update),而且都是「刻意呈現錯誤行為」的證據測試。分開的 tag 會讓「跑所有證據測試」需要記兩個名字。

tag 的名稱維持 `overselling-evidence` 而不改成更廣義的名稱 —— 改名要動 pom、CLAUDE.md、既有 spec 與文件,而收益只是措辭更精準。**記在 spec 裡說明它涵蓋兩類證據即可。**

### D5. 證據測試的情境:一人、上限 4、發 20 個請求

單一使用者、庫存充足(避免庫存不足干擾)、上限 4 張、併發送出 20 個各買 1 張的請求。

斷言:
- **該使用者的成交張數大於上限 4** —— 限購被突破
- 沒有任何請求收到 `409 PURCHASE_LIMIT_EXCEEDED`,或收到的數量遠少於預期(20 - 4 = 16)

**庫存刻意設得充足**:若庫存不足,失敗的原因會混入「沒票了」,證據就不乾淨了 —— 我們要證明的是限購失效,不是庫存失效。

## Risks / Trade-offs

- **[限購檢查多一次查詢,影響吞吐]** → 這是必要成本;它落在索引上,而且擋下的請求不必進入鎖競爭,整體可能反而有利。第 6～8 支的數據會顯示實際影響。
- **[已購張數的計算未排除已取消訂單]** → Phase 2 才有取消狀態,現在無法驗證該邏輯。記入 `todo.md`。
- **[證據測試每次的突破量不同]** → 併發的本質;斷言用「大於上限」而非固定值。

## Migration Plan

無 schema 變更。`idx_purchase_order_event_user` 於第 2 支建立,本支是它的第一個使用者。

## Open Questions

無。上限預設值 4 來自 `todo.md` 的既有決定,可由設定調整。
