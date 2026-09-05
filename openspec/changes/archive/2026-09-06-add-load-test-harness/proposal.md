## Why

前三支產出了**正確性**證據 —— 超賣確實會發生,而且成因是 lost update。但沒有任何**效能**數據,而專案的核心產出是四層策略的比較,比較需要可量化的數字。

第 3 支的 JUnit 併發測試不足以擔任這個角色:它在無資源限制的環境執行,JVM 看到整台機器的 10 個核心,而 k6 之外還有 IDE、瀏覽器在競爭。**在那種環境下量到的吞吐每次都不同,兩組數字不可互相比較。**

本支同時要驗證一件流程層面的事:**「啟動兩次、每次跑完四種策略」這個取得八組數據的方式是否真的可行。** 目前只有一個策略,是 1 × 2 = 2 組;若流程有問題,現在發現遠比第 8 支發現划算 —— 那時要重跑的是八組。

**怎樣算做完:**

1. `docker compose --profile perf up` 能起完整環境、跑完 k6、輸出結果
2. **資源限制確實生效** —— 應用啟動時記錄 JVM 看到的 CPU 數與 heap 上限,數值與 compose 設定相符
3. 取得第 0 層在平台執行緒與虛擬執行緒兩種模型下的數據,**每個數字都附測量條件**
4. `perf` profile 的所有服務不對外開埠,與 `~/dev-databases`、nexus 皆無埠衝突

## What Changes

- 新增 `Dockerfile`:多階段建置 + Spring Boot layered jar
- 新增 `compose.yml`:`perf` profile,含 app、postgres、k6 三個服務
- 新增 `k6/` 目錄:壓測腳本與情境設定
- 新增 `RuntimeInfoLogger`:啟動時記錄 JVM 實際看到的 CPU 數、heap 上限、執行緒模型
- `.gitignore` 排除 k6 的原始輸出

**本支不加 Redis。** compose 多一個服務的成本很低,但它到第 8 支才有用途 —— 現在加,等於留一個四支 change 之內沒有任何東西會碰到的服務。

**壓測環境下第 0 層仍會超賣**,因此本支記錄的「正確性」欄位是超賣張數而非零超賣。它與第 3 支的 JUnit 數據**不可互相比較** —— 一個有資源限制、一個沒有。

## Capabilities

### New Capabilities

- `platform-load-test-environment`:壓測環境的契約 —— 資源限制的數值與理由、JVM 容器資源感知的處理、`max_connections` 的設定、測量條件的必要欄位、環境隔離要求(不得打共用資料庫、不得對外開埠)。此契約會被後續每個 `strategy-*` 引用。

### Modified Capabilities

- `strategy-no-lock`:補上效能量測結果。原 spec 記載「效能數據於第 4 支取得」,本支履行該承諾並填入實際數字與測量條件。

## Impact

**新增相依:** 無。k6 以官方映像執行,不進 Maven。

**受影響的既有檔案:** `.gitignore`、`TicketRushApplication`(註冊 `RuntimeInfoLogger`)、`openspec/specs/strategy-no-lock/spec.md`(archive 時合併)。

**需要使用者手動執行:** 無 —— 但**首次執行會拉取 `grafana/k6` 與建置應用映像**,耗時較長。Docker 需在執行中(OrbStack)。
