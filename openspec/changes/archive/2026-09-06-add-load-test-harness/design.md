## Context

前三支的產出在無資源限制的環境執行,量到的吞吐不可跨次比較。本支建立一個條件受控、可重現的壓測環境。

三種執行環境的分工、壓測環境的既有決策(應用進 container、`fsync` 不關、不對外開埠)見 `openspec/project/backend-runtime.md`,此處只寫本支特有的決定與實際數值。

**本機資源:10 核心 / 24 GB。** 資源限制的分配以此為前提。

## Goals / Non-Goals

**Goals:**

- Dockerfile 與 compose `perf` profile,資源限制固定且可重現
- k6 腳本與情境,量測項目符合 `strategy-*` 的驗收要求
- **驗證資源限制確實生效**,而非只是寫在設定裡
- 取得第 0 層的兩組基準數據
- 驗證「啟動兩次跑八組」的流程可行

**Non-Goals:**

- **不加 Redis** —— 第 8 支才有用途
- **不做 Grafana / Prometheus** —— Phase 4。本支的輸出是 k6 的終端摘要,足以支撐比較
- **不做 CI 上的壓測** —— 壓測需要穩定的資源環境,CI runner 給不了
- **不追求絕對效能數字** —— 目標是跨策略可比較,不是逼近生產環境的真實吞吐

## Decisions

### D1. 資源分配:app 4 核 / 2GB、postgres 2 核 / 1GB、k6 2 核 / 512MB

合計 8 核,**刻意保留 2 核給作業系統與 Docker 本身**。若把 10 核全部分配出去,host 的排程壓力會回過頭影響容器,量到的數字反而更不穩定。

**app 的 `cpus: 4` 是這組設定裡最關鍵的一個。** 虛擬執行緒的 carrier thread 數量預設等於 `Runtime.availableProcessors()`,而 JVM 在容器內會讀 cgroup 限制。固定成 4,八組數據的並行度基準才一致 —— 這正是「壓測必須在容器內執行」的唯一理由。

**k6 與被測系統共用同一台機器,這點無解。** 但把它的份額固定成 2 核,至少讓這個干擾在每次執行中保持相同。

### D2. JVM heap 必須明確設定,不能靠預設

`JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75`。

容器給 2GB 而 `MaxRAMPercentage` 預設只有 **25%** —— JVM 只會拿 512MB 當 heap,壓測時 GC 頻繁到數據失真,**而且不會有任何錯誤訊息**。這是本專案 runtime 文件已記載的坑,本支是它第一次真的生效。

### D3. 資源限制必須被驗證,不能只是寫在設定裡

新增 `RuntimeInfoLogger`,應用啟動時記錄:

- `Runtime.availableProcessors()`
- `Runtime.maxMemory()`
- `spring.threads.virtual.enabled` 的實際值

**沒有這一步,「資源限制生效了」只是一個假設。** cgroup 的行為在不同 Docker 實作(OrbStack / Docker Desktop / Colima)上未必一致,而失效時的症狀是「數字怪怪的」,不會有錯誤。

這份輸出同時是壓測記錄的一部分 —— 測量條件不該靠人抄寫設定檔,應該由被測系統自己報告。

### D4. k6 情境模擬瞬間爆發,不是持續負載

1000 VU、每個 VU 發送 1 次請求、庫存 500。

**不選「固定 VU 持續 30 秒」的常見壓測模式**:搶票的本質是瞬間爆發,不是穩態負載。持續負載會在庫存賣完後變成「量測 409 回應的吞吐」,那不是我們要比較的東西。

量測項目:
- k6 內建的 `http_req_duration`(P50 / P95 / P99)、`http_req_failed`、總耗時
- 從資料庫查詢的**售出張數、最終庫存、超賣張數** —— k6 量不到這些,但它們是正確性欄位

庫存刻意設為請求數的一半:有鎖的策略會是 500 成功 / 500 拒絕,無鎖則會超賣。**兩者的差異在同一個情境下就能顯現。**

### D5. 原始輸出不進版控,摘要進

k6 的 summary JSON 每次執行都不同、體積不小,而它的價值在摘要數字。`k6/results/` 加入 `.gitignore`,數據寫進 change 的 spec 與最終 README。

**但摘要必須連同測量條件一起記錄。** 一個沒有條件的數字無法與另一個比較,在一個以比較為產出的專案裡等於沒有數據。

### D6. perf profile 完全不對外開埠

k6 與 app 在同一個 compose network,以服務名稱連線(`http://app:8080`)。

因此不需要挑選避開 `~/dev-databases`(5432 / 6379)與 nexus(5442 / 6389)的埠號 —— **埠衝突的可能性直接歸零**,而不是靠選一個「應該沒人用」的號碼。

### D7. 八組數據的取得流程

虛擬執行緒無法於執行期切換(`spring.threads.virtual.enabled` 是啟動時設定),因此:

```
啟動 1(平台執行緒)→ 依序跑 4 種策略 → 4 組數據
啟動 2(虛擬執行緒)→ 依序跑 4 種策略 → 4 組數據
```

策略可於執行期切換(`StrategyRegistry` 的 `volatile` 欄位),所以同一次啟動內不需要重啟。

本支只有一個策略,故驗證的是 1 × 2 = 2 組。**流程的可行性在此驗證,而非在第 8 支才發現問題。**

## Risks / Trade-offs

- **[k6 與被測系統共用機器]** → 無解,但固定其資源份額使干擾在每次執行中一致。此限制必須寫入 README。
- **[macOS 上的容器經過虛擬化層]** → 絕對數字不代表生產效能。README 已規劃聲明此事;本支的數據標註為「同一環境下的相對比較」。
- **[cgroup 限制在不同 Docker 實作上行為未必一致]** → 這正是 D3 要驗證的;若 `availableProcessors()` 與設定不符,數據作廢。
- **[首次執行需拉映像與建置,耗時長]** → 一次性成本;layered jar 讓後續重建只更新應用層。

## Migration Plan

無 schema 或程式行為變更。`RuntimeInfoLogger` 只寫日誌,不影響既有功能。

## Open Questions

無。
