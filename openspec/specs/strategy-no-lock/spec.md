# strategy-no-lock Specification

## Purpose
TBD - created by archiving change add-purchase-api-no-lock. Update Purpose after archive.
## Requirements
### Requirement: 無鎖對照組

第 0 層策略 SHALL 以「讀取庫存 → 於領域層計算 → 寫回絕對值」實作,不使用任何鎖機制。

它 SHALL 具備 `@Transactional`。**交易不是為了正確性,是為了讓四層策略的比較基準一致** —— 若只有本層沒有交易,量到的差異會混入「有無交易」而非「有無鎖」。這同時使本層成為一個實務上極普遍的誤解的反例:`@Transactional` 不等於併發安全。

**正確性驗收:刻意失敗。**

本層是四層中唯一的例外 —— 它的驗收條件是**證明錯誤會發生**,而非證明不會:

- 1000 執行緒對可用庫存 500 的場次各購買 1 張
- **累計成功訂單張數 SHALL 大於 500**(超賣確實發生)
- **`stock.available` 最終 SHALL 大於等於 0**(成因是 lost update,而非扣成負數)
- **成功訂單張數 SHALL 不等於庫存減少量**,兩者差額即為超賣張數

第三條是最精確的表述:超賣的定義是「售出量與庫存減少量不一致」,不是「庫存為負」。

此測試 MUST 以 `@Tag("overselling-evidence")` 隔離,由 surefire 預設排除,MUST NOT 使用 `@Disabled` —— 後者在報告中顯示為 skipped,外觀等同壞掉或未完成的測試,而它是**證據**。**任何人都不得「修好」這個測試。**

**效能量測項目:**

本層是後續三層的比較基準。基本組為 QPS、P50 / P95 / P99、錯誤率;本層特有指標:

| 指標 | 說明 |
| --- | --- |
| **超賣張數** | 售出量減去庫存減少量。這是問題的量化,也是 README 的開場數字 |
| 成功率 | 無鎖情況下幾乎所有請求都會「成功」,這個數字本身就是問題的一部分 |

效能數據於資源受限的容器環境取得,環境契約見 `platform-load-test-environment`。實際數字與測量條件記錄於對應 change 的 tasks 與 README —— **spec 定義的是量測項目與記錄要求,不是數字本身**,否則每次重跑壓測都要改 spec。

**JUnit 併發測試的數據與壓測數據 MUST NOT 並列比較。** 前者不受資源限制約束,JVM 觀察到整台機器的核心數且與其他行程競爭;後者在固定的 CPU 與記憶體限制下執行。兩者的正確性結論一致(都會超賣),但吞吐與延遲不可互相參照。

**與前一層的對照:**

本層無前一層,它就是基準。後續三層 MUST 以本層的數據作為改善幅度的分母,並說明各自付出的代價。

#### Scenario: 併發購票造成超賣

- **WHEN** 1000 執行緒同時對可用庫存 500 的場次各購買 1 張
- **THEN** 累計成功訂單張數 SHALL 大於 500
- **AND** `stock.available` SHALL 大於等於 0
- **AND** 成功訂單張數與庫存減少量 SHALL 不相等

#### Scenario: 單執行緒購票行為正確

- **WHEN** 無併發的情況下依序購票
- **THEN** 庫存扣減與訂單建立 SHALL 完全正確 —— 本層的缺陷只在併發下顯現,這正是它難以在開發階段被發現的原因

#### Scenario: 交易存在但無法阻止超賣

- **WHEN** 本層策略執行於 `@Transactional` 之下、隔離級別為 PostgreSQL 預設的 READ COMMITTED
- **THEN** 超賣 SHALL 仍然發生 —— 交易保證原子性,不保證併發下的互斥

#### Scenario: 超賣測試不阻斷預設建置

- **WHEN** 執行 `./mvnw verify`
- **THEN** 超賣證據測試 SHALL 被排除,建置 SHALL 為綠
- **AND** 以 `-Dsurefire.excludedGroups= -Dgroups=overselling-evidence` 執行時 SHALL 能重現超賣

#### Scenario: 引用本層的效能數據

- **WHEN** 後續策略以本層作為改善幅度的分母
- **THEN** 兩者的測量條件 SHALL 相同(相同的資源限制、執行緒模型、初始庫存、請求模式)
- **AND** 條件不同時 SHALL NOT 直接相除計算改善倍數

### Requirement: 無鎖對照組的第二個缺陷 —— 限購同樣被突破

第 0 層策略下,單人限購 SHALL 與庫存一樣被併發突破,**且成因完全相同**:檢查「這個人已經買了幾張」需要先讀取,兩個併發請求讀到相同的已購數、各自通過檢查,寫入後其中一次的計數就此遺失。

**這組證據比超賣更有說服力,因為它更難被發現。** 庫存超賣會讓總量對不上,任何對帳都會抓到;而「某個人多買了兩張」不會讓任何總量出錯 —— 除非專門去查那個人。實務上這類缺陷可以存活很久,直到有人拿它套利。

證據測試 MUST 與超賣證據共用 `@Tag("overselling-evidence")`。兩者是同一個問題(lost update)的兩種表現,分開的 tag 會讓「執行所有證據測試」需要記兩個名字。**該 tag 名稱涵蓋兩類證據,不因措辭不夠廣義而改名** —— 改名要動 pom、CLAUDE.md 與既有文件,收益只是用詞更精準。

**情境的庫存必須充足。** 若庫存不足,失敗原因會混入「沒票了」,證據就不乾淨 —— 要證明的是限購失效,不是庫存失效。

#### Scenario: 同一人併發下單突破限購

- **WHEN** 單一使用者對庫存充足的場次併發送出 20 個各購 1 張的請求(上限 4)
- **THEN** 該使用者的成交張數 SHALL 大於 4
- **AND** 收到 `PURCHASE_LIMIT_EXCEEDED` 的請求數 SHALL 遠少於 16

#### Scenario: 單執行緒下限購有效

- **WHEN** 同一使用者依序購買至超過上限
- **THEN** 超限的請求 SHALL 被拒絕 —— 與超賣相同,缺陷只在併發時顯現

#### Scenario: 兩類證據的共同成因

- **WHEN** 檢視超賣與限購突破的成因
- **THEN** 兩者 SHALL 皆為 lost update:讀取 → 於應用層判斷 → 寫回,而讀取到寫回之間沒有任何互斥

#### Scenario: 證據測試不阻斷預設建置

- **WHEN** 執行 `./mvnw verify`
- **THEN** 限購突破的證據測試 SHALL 與超賣證據一同被排除
- **AND** 以 `-Dsurefire.excludedGroups= -Dgroups=overselling-evidence` 執行時,兩者 SHALL 皆能重現

