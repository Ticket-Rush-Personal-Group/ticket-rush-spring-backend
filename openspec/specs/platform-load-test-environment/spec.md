# platform-load-test-environment Specification

## Purpose
TBD - created by archiving change add-load-test-harness. Update Purpose after archive.
## Requirements
### Requirement: 壓測必須在資源受限的容器內執行

壓測時應用 MUST 執行於容器內並套用固定的 CPU 與記憶體限制。MUST NOT 直接跑在 host 上。

理由不是模擬生產環境,而是**讓多組數據可以互相比較**:虛擬執行緒的 carrier thread 數量預設等於 `Runtime.availableProcessors()`,跑在 host 上時該值為整台機器的核心數,且與 k6、IDE、瀏覽器競爭 —— 每次執行的有效並行度都不同。並行度本身在浮動時,量到的差異無法歸因於策略。

資源分配 MUST 保留部分核心給作業系統與容器執行環境。將全部核心分配出去會使 host 的排程壓力回過頭影響容器。

#### Scenario: 應用跑在 host 上進行壓測

- **WHEN** 壓測時應用未在容器內執行
- **THEN** 該次數據 SHALL 標記為不可與其他組別比較

#### Scenario: 資源分配未預留餘裕

- **WHEN** 容器的 CPU 限制總和等於或超過機器核心數
- **THEN** 設定 SHALL 被視為不當 —— host 的排程壓力會回饋到容器內

---

### Requirement: 資源限制必須經過驗證而非假設

應用 MUST 於啟動時記錄 JVM 實際觀察到的執行環境:`Runtime.availableProcessors()`、`Runtime.maxMemory()`、虛擬執行緒是否啟用。該輸出 MUST 與 compose 的設定相符。

**「限制寫在設定檔裡」不等於「限制生效了」。** cgroup 的行為在不同的容器執行環境(OrbStack、Docker Desktop、Colima)未必一致,而失效時的症狀是「數字看起來怪怪的」,不會產生任何錯誤。

此輸出同時是測量條件的一部分 —— **條件應由被測系統自行報告,而非由人抄寫設定檔**,抄寫會漂移。

#### Scenario: JVM 觀察到的資源與設定不符

- **WHEN** 啟動記錄顯示的 CPU 數或記憶體上限與 compose 設定不一致
- **THEN** 該次壓測數據 SHALL 作廢,並先排除環境問題

#### Scenario: 測量條件的來源

- **WHEN** 記錄一組壓測數據的測量條件
- **THEN** CPU 數與 heap 上限 SHALL 取自應用的啟動記錄,而非設定檔的內容

---

### Requirement: JVM heap 上限必須明確設定

壓測容器 MUST 明確設定 heap 上限(如 `-XX:MaxRAMPercentage=75`),MUST NOT 依賴預設值。

`MaxRAMPercentage` 預設為 **25%** —— 容器給 2GB,JVM 只取 512MB 作為 heap,壓測時 GC 頻率會使數據失真,**且不會產生任何錯誤或警告**。

#### Scenario: 未設定 heap 上限

- **WHEN** 壓測容器未明確設定 heap 相關參數
- **THEN** 設定 SHALL 被視為不完整 —— 預設值會靜默地讓數據失真

---

### Requirement: 壓測環境不得使用共用資料庫,且不對外開埠

壓測 MUST 使用 `perf` profile 自行啟動的資料庫,MUST NOT 連線至 `~/dev-databases`。該資料庫的 `max_connections` MUST 調高至足以容納壓測併發量。

`perf` profile 的所有服務 MUST NOT 發布 host 埠。k6 與應用位於同一 compose network,以服務名稱連線。

共用資料庫的 `max_connections` 為預設 100,壓測會將其耗盡並波及所有連線該埠的專案。不開埠則使埠衝突的可能性直接歸零,而非依賴「挑一個應該沒人用的號碼」。

#### Scenario: 壓測連到共用資料庫

- **WHEN** 壓測的資料來源指向 `localhost:5432`
- **THEN** 設定 SHALL 被視為錯誤 —— 它會耗盡共用資料庫的連線並影響其他專案

#### Scenario: perf 服務發布 host 埠

- **WHEN** `perf` profile 的任一服務設定了 `ports`
- **THEN** 該設定 SHALL 被移除 —— 同一 network 內以服務名稱連線即可

---

### Requirement: 每組效能數據必須附帶完整的測量條件

任何被記錄或引用的效能數字 MUST 同時包含:策略名稱、執行緒模型(平台 / 虛擬)、容器 CPU 與記憶體限制、JVM 實際觀察到的處理器數、應用端的連線池大小、**該策略的策略專屬參數**、資料庫的 `max_connections`、k6 的 VU 數與請求模式、初始庫存量。

**連線池大小是必要欄位。** 需要鎖的策略會延長交易,而交易期間連線被佔用 —— 連線池因此可能成為比鎖更早出現的瓶頸。少了這個欄位,「加鎖之後變慢了」這個結論會無法判斷究竟是鎖造成的,還是連線池造成的,而兩者的處置方式完全不同。

**策略專屬參數是後加入的必要欄位。** 樂觀鎖的**重試上限**直接決定成交率與吞吐:上限 10 與上限 100 是兩組完全不同的系統行為,而它們在數據表上長得像同一個策略的兩次執行。少了這個欄位,兩組數字會看起來像矛盾的量測結果,而不是一組刻意的對照。

**沒有測量條件的數字無法與另一個比較。** 在一個以比較為唯一產出的專案裡,那等於沒有數據。

#### Scenario: 引用效能數字

- **WHEN** 於 README、spec 或任何文件中引用效能數字
- **THEN** 測量條件 SHALL 一併呈現,不得只列數字

#### Scenario: 不同資源限制下的數據

- **WHEN** 兩組數據的資源限制不同
- **THEN** 它們 SHALL NOT 被並列比較,即使量測項目相同

#### Scenario: 連線池大小不同的兩組數據

- **WHEN** 兩組數據的連線池大小不同
- **THEN** 它們 SHALL NOT 被用來論證策略之間的差異 —— 差異可能來自連線池而非策略
- **AND** 刻意改變連線池以定位瓶頸時,該對照 MUST 明確標示為「連線池對照」而非「策略對照」

#### Scenario: 策略專屬參數不同的兩組數據

- **WHEN** 兩組數據的策略專屬參數不同(例如樂觀鎖的重試上限)
- **THEN** 該參數 MUST 出現在兩組的測量條件中
- **AND** 該對照 MUST 明確標示為該參數的對照,而非策略對照

#### Scenario: 跨策略比較

- **WHEN** 比較兩種策略的數據
- **THEN** 除策略本身外的所有測量條件 MUST 相同
- **AND** 條件不同時 SHALL 重跑以匹配條件,而非在解讀時口頭校正

### Requirement: 八組數據的取得流程

虛擬執行緒的啟用與否 MUST 於應用啟動時決定(`spring.threads.virtual.enabled`),因此八組數據的取得方式 MUST 為:**啟動兩次,每次啟動內依序切換四種策略**。

策略可於執行期切換,同一次啟動內 MUST NOT 為了換策略而重啟。

#### Scenario: 切換執行緒模型

- **WHEN** 需要取得另一種執行緒模型的數據
- **THEN** 應用 SHALL 重新啟動,並確認啟動記錄中的虛擬執行緒狀態已改變

#### Scenario: 切換策略

- **WHEN** 需要取得另一種策略的數據
- **THEN** SHALL 透過執行期切換完成,不重啟應用

---

### Requirement: 原始輸出不進版控,摘要與條件進

k6 的原始輸出檔 MUST 排除於版控之外。摘要數字與測量條件 MUST 寫入文件。

原始 JSON 每次執行都不同且體積不小,而其價值在於摘要。將它們納入版控只會讓 diff 充滿無意義的變動。

#### Scenario: 壓測執行後

- **WHEN** 壓測產生原始輸出檔
- **THEN** 該檔案 SHALL 被 `.gitignore` 排除
- **AND** 摘要數字與測量條件 SHALL 寫入 change 的 tasks 或 README

