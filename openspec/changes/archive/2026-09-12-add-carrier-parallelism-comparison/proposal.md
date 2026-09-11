## Why

第 19 支把「虛擬執行緒吞吐較低」縮到一個很具體的位置:
**決定吞吐的是「持有連線多久」,而虛擬握著連線的時間裡有 94.7% 沒在用資料庫**
(平台 93.5%,持有 ÷ postgres CPU = 18.9 倍 vs 15.4 倍)。

**虛擬不是做了更多事** —— app CPU/請求與平台分不出差異、postgres CPU/請求還更低。
**是同樣的事花了更多牆鐘時間。**

而查下去有一個具體的不對稱:

| 執行緒模型 | 瞬時並行度的上限 |
| --- | --- |
| 平台 | 受 OS 排程,**可同時跑在主機的 10 個邏輯核上**(`cpus: 4` 是 CFS 配額,不是 CPU 集合) |
| 虛擬 | **carrier 數 = `availableProcessors` = 4,硬上限** |

請求在持有連線期間若有 CPU 側的工作,平台可以四條以上同時做,虛擬最多四條 ——
**其餘的在 carrier 佇列裡等,而連線在那段時間是被握著的。**

> **原本的候選是 JDK 21 的 pinning,但查 jar 之後排除了。**
> PgJDBC 42.7.13 的 `QueryExecutorImpl` 雖然還有 10 個 `monitorenter`,
> **但全在 OID 集合的存取方法,不在 I/O 路徑上**。
> **開始設計之前查掉它,省下的是一整支圍繞錯誤前提的 change。**

**怎樣算做完:**

1. 對「虛擬握著連線的閒置時間為何較長」給出**可否證**的答案 —— 包含「不是 carrier」
2. carrier 並行度成為**被系統自報**的測量條件

## What Changes

- `compose.yml`:app 可設定 `-Djdk.virtualThreadScheduler.parallelism`(預設不設 = JDK 預設值)
- `RuntimeInfoLogger`:自報 carrier 並行度 ——
  **設定可以被賦值卻不生效,讀設定值會在條件表寫下不是事實的數字**(第 15 支的教訓)
- `run-comparison-matrix.sh`:`V<n>` 的數字改傳給 carrier 並行度。
  **label 的數字語意統一為「該模型的並行度旋鈕」** ——
  平台是 Tomcat 執行緒數、虛擬是 carrier 數,兩者是同一個概念的兩種實作。
  `V_noLock`(不帶數字)仍為預設值,既有八組行為不變
- 三組 × 三輪:`P1000_noLock`(對照)/ `V_noLock`(carrier 4)/ `V16_noLock`(carrier 16)

## Capabilities

### Modified Capabilities

- `platform-load-test-environment`:擴充「准入併發度上限」那條需求 ——
  測量條件要記錄的是**該執行緒模型「同時能有幾個在執行」的上限**,
  而不只是「同時能放進來幾個」;兩者在不同的執行緒模型下由不同的機制決定,
  且其中一個可能是硬上限、另一個只是配額。

## Impact

**不改 schema,無相依變更,四層策略的實作一行不動。**
唯一的應用程式碼是 `RuntimeInfoLogger` 多輸出一行測量條件。

**不改動任何量測參數**:負載、暖機、排程、策略、配額沿用第 18、19 支
(`PG_CPUS=4`、1000 VU × 50、連線池 50)。

**不升級 JDK** —— pinning 已被排除,沒有理由改變數十件事。

**需要使用者手動執行:** 三組 × 三輪約 12 分鐘。
機器須接上電源、**電池高電量且未在充電**、OrbStack 不得關閉、主機不得有其他負載 ——
第 18、19 支實測這三者各自都足以讓整批作廢。
