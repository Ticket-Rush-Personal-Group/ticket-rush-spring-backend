> 每一塊(`##` 標題)須能獨立通過驗證鏈:
> `./mvnw compile && ./mvnw spotless:check && ./mvnw verify`
> **本支動到持久化查詢,`verify` 是必要的。既有 55 個測試必須全程維持綠。**
> 證據測試以 `@Tag("overselling-evidence")` 排除於預設建置之外,
> 需以 `./mvnw test -Dsurefire.excludedGroups= -Dgroups=overselling-evidence` 單獨執行。
> 每塊綠燈才進下一塊。**不逐塊 commit** —— 一支 change 一個 commit,收尾塊(塊 5)才給指令。
>
> 塊的依賴關係:
> - **塊 1 完全獨立** —— domain 的限購規則是純 Java,不需要 port 也不需要資料庫,
>   可以先寫完並用純單元測試驗證。這正是「領域規則不依賴基礎設施」的實際好處。
> - 塊 2 → 3 為一條鏈:要有 port 才能在 service 套用。
> - 塊 4 依賴塊 3(證據要走完整的 HTTP 路徑)。

## 1. domain:限購規則

- [x] 1.1 `domain.policy.PurchaseLimitPolicy`(record,**無任何框架註解**):
      `ensureWithinLimit(UserId, int alreadyPurchased, Quantity requesting)`,
      超限時拋 `PurchaseLimitExceededException`
- [x] 1.2 `domain.exception.PurchaseLimitExceededException`,攜帶 userId、已購數、請求數、上限值 ——
      錯誤訊息要能讓人看出「差多少」,而不只是「超過了」
- [x] 1.3 **純單元測試,不啟動 Spring 也不連資料庫**(D1 的理由所在):
      未達上限通過、剛好等於上限通過、超過上限拋例外、單次請求即超限拋例外
- [x] 1.4 **反向驗證通過**:把 `total > maxTicketsPerUser` 改為 `>=`,
      `allowsWhenExactlyAtLimit` 與三個參數化案例共 **4 項變紅**,還原後回綠。
      **邊界條件的差一錯誤是限購最典型的 bug**,因此「剛好等於上限」必須是獨立的測試案例,
      不能只測「明顯超過」與「明顯未達」——後兩者在 `>` 與 `>=` 之下行為相同,抓不到
- [x] 1.5 **驗證**:`./mvnw test` 綠

## 2. out port 與查詢實作

- [x] 2.1 `application.port.out.LoadUserPurchasedQuantityPort`:
      查詢某使用者在某場次的**累計張數**
- [x] 2.2 實作以 `SUM(quantity)` 計算而非訂單筆數(D3)—— 一次買 4 張與四次各買 1 張,
      對限購而言等價。使用第 2 支建立的 `idx_purchase_order_event_user` 索引
- [x] 2.3 **目前不排除任何訂單狀態**。Phase 2 引入逾時取消後,已取消的訂單應排除 ——
      **記入 `tasks/todo.md`**,不在本支處理(現在只有 PENDING,排除邏輯無法驗證)
- [x] 2.4 整合測試:無訂單時回 0、多筆訂單時回張數總和、不同場次互不影響、
      不同使用者互不影響
- [x] 2.5 **反向驗證通過**:把 `sum(o.quantity)` 改為 `count(o)`,
      `sumsQuantitiesNotOrderCount` 變紅。
      **測試資料刻意用不同的張數(1、2、3 共 6 張)** —— 若每筆都是 1 張,
      `sum` 與 `count` 的結果相同,這個錯誤就抓不到
- [x] 2.6 **驗證**:`./mvnw verify` 綠

## 3. 套用限購與錯誤映射

- [x] 3.1 `infrastructure.config` 依 `ticket-rush.max-tickets-per-user`(預設 4)
      建立 `PurchaseLimitPolicy` bean。**policy 類別本身不得有 Spring 註解**(D1)
- [x] 3.2 `NoLockPurchaseService` 套用限購,**檢查順序為先限購、後庫存**(D2)——
      限購只需一次索引查詢,庫存檢查會進入鎖競爭;被限購擋下的請求不應參與競爭
- [x] 3.3 `ErrorCode` 新增 `PURCHASE_LIMIT_EXCEEDED`,`GlobalExceptionHandler` 映射為 409
- [x] 3.4 service 單元測試(8 項)。`PurchaseLimitPolicy` **使用真實實例而非 mock** ——
      它是 domain 值物件、無外部依賴,mock 它只會讓測試驗證不到真正的規則。
      **只 mock 跨越邊界的東西**
- [x] 3.5 `PurchaseLimitContractTest`(7 項),涵蓋 spec 全部 scenario 外加「不同使用者互不影響」。
      **測試不覆寫上限設定** —— 若日後預設值變更而測試沒跟著調,這些測試會失敗,那正是想要的
- [x] 3.6 超限被拒時斷言庫存維持 500 且訂單數不變。
      **既有的 `insufficientStock` 測試因此需要修改**:它原本買 5 張,而限購上限是 4,
      現在會先被 `PURCHASE_LIMIT_EXCEEDED` 擋下、測不到庫存不足那條路徑。改為買 2 張。
      **這是 D2「限購先於庫存」的實際影響,不是測試寫錯**
- [x] 3.7 **反向驗證通過**:把兩個檢查區塊對調(庫存先、限購後),
      `limitCheckPrecedesStockCheck` 在單元與契約測試中皆變紅,還原後回綠。
      第一次的改壞腳本因 spotless 重新排版而字串不匹配 —— **`assert` 讓它明確失敗,
      而不是靜默地「沒改到卻顯示綠」**
- [x] 3.8 **驗證**:`./mvnw verify` 綠

## 4. 限購突破的證據

- [x] 4.1 `PurchaseLimitEvidenceTest`,`@Tag("overselling-evidence")`(D4,與超賣證據共用 tag)
- [x] 4.2 情境:單一使用者、**庫存充足**(避免失敗原因混入「沒票了」)、上限 4、
      併發送出 20 個各購 1 張的請求,經由真實 HTTP 路徑
- [x] 4.3 斷言:該使用者成交張數**大於 4**;收到 `PURCHASE_LIMIT_EXCEEDED` 的數量
      **遠少於 16**
- [x] 4.4 輸出可直接引用的摘要:總請求數、成交張數、上限值、被拒數、超買張數
- [x] 4.5 **反向驗證通過**:改為 `SERIALIZABLE` 後該使用者只成交 **2 張**(未達上限 4),
      測試變紅;還原後超買 6 張重現。
      **依第 3 支的 lessons 直接跳過 `synchronized`** —— 那條紀錄省下了一次無效的嘗試
- [x] 4.6 **驗證**:`./mvnw verify` 綠(證據測試被排除);單獨執行能重現限購突破

## 5. 收尾

- [x] 5.1 完整驗證鏈:`compile` BUILD SUCCESS、`spotless:check` 76 files clean、
      `verify` **Tests run: 82, Failures: 0, Errors: 0** / BUILD SUCCESS
- [x] 5.2 **兩組證據的實際數字**(單獨執行,`-Dgroups=overselling-evidence`):

      | | 超賣證據 | 限購突破證據 |
      | --- | --- | --- |
      | 併發請求 | 1000 | 20(單一使用者) |
      | 上限 | 庫存 500 | 限購 4 張 |
      | 成功建立訂單 | 1000 筆 | 10 筆 |
      | 被擋下 | 0 | 10 |
      | **突破量** | **超賣 832～836 張** | **超買 6 張** |

      **一個值得記下的對比:限購擋下了一半的請求,庫存則完全沒擋到。**
      原因是「窗口大小」不同 —— 限購的窗口只有 4 張,很快被填滿後後續請求就讀得到新值;
      庫存有 500 張,1000 個請求幾乎全在同一個時間窗內讀到相同的值。
      **同樣的 lost update,失效程度取決於窗口相對於併發量的大小。**
- [x] 5.3 更新 `tasks/todo.md`:第 5 支打勾;新增「已取消訂單應排除於限購計算」至延後項目
- [x] 5.4 `tasks/lessons.md` 寫入一則:**在流程前面插入檢查會改變既有測試測到的路徑** ——
      修正方式是調整輸入而非改預期值,後者等於默默放棄原本要測的東西
- [x] 5.5 需要使用者手動執行的動作:無
- [x] 5.6 archive 這支 change(`openspec archive add-purchase-limit-policy -y`)
