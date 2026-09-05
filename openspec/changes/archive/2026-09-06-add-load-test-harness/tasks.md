> 每一塊(`##` 標題)須能獨立通過驗證鏈:
> `./mvnw compile && ./mvnw spotless:check && ./mvnw verify`
> **本支不改業務邏輯,既有 55 個測試必須全程維持綠。**
> 另有容器層面的驗證:`docker compose --profile perf up` 能起得來、k6 能產出摘要。
> 每塊綠燈才進下一塊。**不逐塊 commit** —— 一支 change 一個 commit,收尾塊(塊 6)才給指令。
>
> 塊的依賴關係:
> - 塊 1(RuntimeInfoLogger)獨立,可先做 —— 它在 host 上就能驗證基準值。
> - 塊 2 → 3 → 4 為一條鏈:要有映像才能組 compose,要有 compose 才能讓 k6 打到 app。
> - **塊 3 的反向驗證是本支最關鍵的一步** —— 改變 `cpus` 設定並確認 JVM 報告的數字跟著變,
>   那是「資源限制真的生效」的唯一證據。沒有它,後面所有數據都建立在假設上。
> - 塊 5 依賴塊 4,且需要**兩次啟動**(平台 / 虛擬執行緒)。

## 1. RuntimeInfoLogger:讓被測系統自己報告環境

- [x] 1.1 `infrastructure/RuntimeInfoLogger`(`ApplicationRunner`)
- [x] 1.2 輸出為可直接貼進壓測記錄的區塊
- [x] 1.3 **host 基準值(未受限)**:

      ```
      ===== 執行環境(壓測測量條件的來源) =====
      availableProcessors : 10
      maxMemory (heap)    : 6144 MB
      虛擬執行緒          : 停用(平台執行緒)
      當前策略            : noLock
      ==========================================
      ```

      **`6144 MB` 正是 `MaxRAMPercentage` 預設 25% 的實證**(host 24GB × 25% = 6GB)。
      同一個預設值套到 2GB 的容器就只剩 512MB —— D2 要防的正是這件事。
      取值方式:透過既有的 `@SpringBootTest` 啟動來觀察,**不需要 `spring-boot:run`**
      (CLAUDE.md 的 Hard Rules 禁止 AI 自行執行它)
- [x] 1.4 **驗證**:`./mvnw verify` 綠,既有 55 個測試不受影響

## 2. Dockerfile:多階段建置與 layered jar

- [x] 2.1 三階段:`maven:3.9-eclipse-temurin-21` 建置 → `eclipse-temurin:21-jre` 拆層 → JRE 執行
- [x] 2.2 拆層指令為 **Boot 4 的 `-Djarmode=tools ... extract --layers --launcher`**
      (Boot 3.2 之前是 `-Djarmode=layertools`),entrypoint 為
      `org.springframework.boot.loader.launch.JarLauncher`(Boot 3.2 起 launcher 已改 package)
- [x] 2.3 `.dockerignore` 排除 `target/`、`.git/`、`openspec/`、`tasks/`、`k6/results/`
- [x] 2.4 **驗證方式改用更直接的做法**:原定「啟動映像看它因連不上資料庫而失敗」只能證明
      JVM 會跑。改為讓 JVM 直接報告它在資源限制下看到什麼:

      ```
      mem=2g + MaxRAMPercentage=75  →  Max. Heap Size: 1.50G
      mem=1g + 未設定(預設 25%)    →  Max. Heap Size: 247.50M
      ```

      **這直接證實了 D2**:1GB 的容器不設定就只有 247MB heap,而且不會有任何警告

## 3. compose perf profile 與資源限制

- [x] 3.1 `compose.yml`:`name: ticket-rush`,三個服務皆掛 `profiles: [perf]` ——
      `app`、`postgres-perf`、`k6`
- [x] 3.2 資源限制(D1,本機 10 核 / 24GB):
      `app` 4 核 / 2GB、`postgres-perf` 2 核 / 1GB、`k6` 2 核 / 512MB。
      **合計 8 核,刻意保留 2 核給 OS 與 Docker**
- [x] 3.3 `app` 的 `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75`(D2)——
      預設 25% 會讓 2GB 的容器只有 512MB heap,GC 頻繁到數據失真且無任何錯誤訊息
- [x] 3.4 `postgres-perf`:`postgres:17`、`max_connections=500`、資料放 tmpfs、
      **`fsync` 不得關閉**(關閉會不均等地偏袒悲觀鎖,破壞跨層比較)
- [x] 3.5 **所有服務不得設定 `ports`**(D6)—— k6 與 app 同 network,以服務名稱連線,
      埠衝突的可能性直接歸零
- [x] 3.6 healthcheck 打 `/actuator/health`,`depends_on` 等 `service_healthy` ——
      容器 running 與「可接受請求」之間有實質空窗
- [x] 3.7 **反向驗證(本支最關鍵的一步)—— 通過**:

      | compose 設定 | JVM 報告的 availableProcessors | heap |
      | --- | --- | --- |
      | `cpus: 4` / `mem_limit: 2g` | **4** | 1536 MB |
      | `cpus: 2` / `mem_limit: 1g` | **2** | 742 MB |
      | host(未受限,對照) | 10 | 6144 MB |

      數字隨設定改變,cgroup 限制在 OrbStack 上確實生效且 JVM 正確讀取。
      三組 heap 都精確符合 `記憶體 × 75%`(host 那組是 `24GB × 25%` 的預設值)
- [x] 3.8 **驗證**:`docker compose --profile perf up -d --wait app postgres-perf` 兩者皆 healthy。
      healthcheck 用 `curl` —— 已先確認 `eclipse-temurin:21-jre`(Ubuntu 26.04)內建 curl 與 wget,
      避免「寫了不存在的工具導致容器永遠 unhealthy」這個症狀誤導成「服務起不來」

## 4. k6 腳本

- [x] 4.1 `k6/purchase-rush.js`:1000 VU、每 VU 發送 1 次請求、庫存 500(D4)。
      **不用「固定 VU 持續 N 秒」的常見模式** —— 搶票是瞬間爆發,持續負載在庫存賣完後
      變成「量測 409 的吞吐」,那不是要比較的東西
- [x] 4.2 每個 VU 使用唯一的 `idempotencyKey` 與 `X-User-Id`,避免撞到冪等鍵約束而
      量到錯誤的東西
- [x] 4.3 前置與後置**以獨立的 `k6/run-load-test.sh` 完成,不放進 k6**:
      k6 連不了資料庫,而「售出張數 / 最終庫存 / 超賣張數」是 `strategy-*` 驗收的一半。
      也刻意不為此在應用加測試專用端點 —— 那會污染正式 API。
      **`psql` 必須帶 `-q`**:`INSERT ... RETURNING` 會同時輸出 tuple 與 `INSERT 0 1`
      這行 command status,後者被吃進變數後造成下一句 SQL 語法錯誤
- [x] 4.4 摘要輸出:`http_req_duration` 的 P50 / P95 / P99、`http_req_failed`、總耗時、
      各狀態碼的分佈
- [x] 4.5 **正確性欄位由資料庫查詢取得**:售出張數、最終庫存、超賣張數 ——
      k6 量不到這些,但它們是 `strategy-*` 驗收的一半
- [x] 4.6 `k6/results/` 加入 `.gitignore`(D5)
- [x] 4.7 **驗證**:能完成並輸出摘要。
      另加 `summaryTrendStats` 輸出 **p(99)** —— k6 預設只給 p(90) 與 p(95),
      而 `strategy-*` 的驗收要求 P50 / P95 / P99

## 5. 取得第 0 層的基準數據

- [x] 5.1 **啟動 1 —— 平台執行緒**
- [x] 5.2 **啟動 2 —— 虛擬執行緒**。第一次嘗試時 `RuntimeInfoLogger` 仍顯示「停用」,
      追查後發現是 `docker compose run` 重建了 app(見 5.2b),修正後才真正切換
- [x] 5.2b **踩到的坑:`docker compose run` 會靜默替換掉 `depends_on` 的服務。**
      `run` 會依「當前解析到的設定」比對 depends_on 的服務,不一致就重建它。
      `run-load-test.sh` 執行時沒有 `VIRTUAL_THREADS` 環境變數,compose 解析成 `false`,
      於是把正在跑虛擬執行緒的 app 換成平台執行緒版本 —— **沒有任何錯誤訊息**。
      解法是給 `run` 加 `--no-deps`。
      **這個問題是 `RuntimeInfoLogger` 抓到的** —— 沒有它,我會拿著兩組其實都是
      平台執行緒的數據當作對照組,而兩者的「差異」只是執行間的浮動。
      D3 說「限制寫在設定裡不等於生效」,這裡證明了它的價值不只在資源限制
- [x] 5.3 **第 0 層基準數據**(策略 `noLock`,兩組的測量條件除執行緒模型外完全相同):

      | 指標 | 平台執行緒 | 虛擬執行緒 |
      | --- | --- | --- |
      | avg | 568.76 ms | **468.69 ms** |
      | med | 622.51 ms | 460.56 ms |
      | p(95) | 918.07 ms | 850.71 ms |
      | **p(99)** | 932.59 ms | **872.88 ms** |
      | max | 955 ms | 889.59 ms |
      | http_reqs | 884.55 /s | 660.09 /s |
      | 失敗率 | 0% | 0% |
      | **超賣張數** | **862** | **884** |
      | 最終庫存 | 362 | 384 |

      **測量條件**(取自應用啟動記錄,非設定檔):
      `availableProcessors: 4`、heap `1536 MB`、容器 `cpus: 4` / `mem_limit: 2g`、
      postgres `max_connections=500` / 2 核 / 1GB / tmpfs / fsync 開啟、
      k6 2 核 / 512MB、1000 VU 每 VU 一次請求、初始庫存 500、
      策略 `noLock`、隔離級別 READ COMMITTED、macOS + OrbStack。

      **單次執行的數字,尚未取多次平均。** 延遲指標虛擬執行緒較優,但 `http_reqs/s`
      反而較低 —— 這個矛盾在只有一個策略、單次執行的情況下不足以下結論,
      待第 6～8 支有四種策略可對照時再判讀。**現在就編故事會編錯。**
- [x] 5.4 正確性欄位:兩組皆 1000 筆訂單全部成立,超賣 862 / 884 張。
      **壓測環境下第 0 層仍然超賣**,與第 3 支的 JUnit 結論一致
- [x] 5.5 **流程驗證完成,而且真的抓到問題**(D7):切換執行緒模型需重啟並
      **必須驗證重啟後的實際值**;切換策略不需重啟。
      發現的 `--no-deps` 問題若拖到第 8 支才浮現,要重跑的是八組而不是兩組 ——
      這正是「在只有一個策略時先驗證流程」的理由
- [x] 5.6 與第 3 支對照:正確性結論一致(都超賣,量級相近:JUnit 856～868、壓測 862～884)。
      **吞吐與延遲不可互相參照** —— JUnit 那組 `availableProcessors` 為 10 且無記憶體限制,
      壓測這組為 4 / 1536MB

## 6. 收尾

- [x] 6.1 完整驗證鏈實際輸出:

      ```
      ./mvnw compile        → BUILD SUCCESS
      ./mvnw spotless:check → keeping 69 files clean / BUILD SUCCESS
      ./mvnw verify         → Tests run: 55, Failures: 0, Errors: 0 / BUILD SUCCESS
      ```

      **本支不改業務邏輯,55 個測試全程維持綠**(與第 3 支相同)
- [x] 6.2 兩組數據與完整測量條件已填入 5.3。**它們是後續三層改善幅度的分母** ——
      引用時測量條件必須一併呈現,條件不同的數據不得直接相除計算改善倍數
- [x] 6.3 更新 `tasks/todo.md`:第 4 支打勾
- [x] 6.4 `tasks/lessons.md` 寫入一則:**`docker compose run` 會靜默替換 depends_on 的服務**,
      以及由此得到的更根本一課 —— **跨組比較的實驗,要讓被測系統自報條件**,
      不能由人抄寫設定檔
- [x] 6.5 需要使用者手動執行的動作:**無程式碼層面的動作**。
      執行壓測的前提:Docker 需在執行中(OrbStack);首次會建置應用映像並拉取 `grafana/k6`。
      指令:
      ```bash
      VIRTUAL_THREADS=false docker compose --profile perf up -d --wait app postgres-perf
      ./k6/run-load-test.sh
      docker compose --profile perf down    # 用完記得停,它佔 8 核
      ```
- [x] 6.6 archive 這支 change(`openspec archive add-load-test-harness -y`):
      `platform-load-test-environment` 新建、`strategy-no-lock` 更新
