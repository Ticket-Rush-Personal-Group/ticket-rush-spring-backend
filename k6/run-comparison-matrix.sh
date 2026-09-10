#!/usr/bin/env bash
#
# 八組(四策略 × 兩執行緒模型)的交錯量測矩陣。
#
# **為什麼要交錯:** 「同一組連續量三次再換下一組」會讓每一組的量測值集中在時間軸的某一段。
# 環境若在量測期間漂移,那個漂移就成為**偏向特定組別的偏差**,而不是共有的雜訊 ——
# 而偏差無法用增加量測次數消除。
#
# 第 11 支即為此情形:四組虛擬執行緒全部排在四組平台之後,其全距(20.6%～53.1%)
# 明顯高於平台組(6.0%～8.9%),而「執行緒模型造成」與「時間順序造成」在該批數據中無法分離。
#
# 因此本腳本以「一輪跑完全部八組」為單位重複,並**每輪旋轉組別順序**,
# 讓每一組落在不同的位置。
#
# **本腳本不修改 run-load-test.sh,只呼叫它。** 暖到收斂、CHECKPOINT、失敗率
# 那些協定原樣沿用 —— 一次只動一個變數,而本支動的是順序。
#
# 用法:
#   ./k6/run-comparison-matrix.sh                      # 三輪 × 八組（約 45 分鐘）
#   ROUNDS=2 MATRIX_GROUPS="P_pessimistic V_pessimistic" \
#     ./k6/run-comparison-matrix.sh                    # 小規模驗證
#   ALLOW_BATTERY=1 ./k6/run-comparison-matrix.sh      # 明確允許在電池上跑（不建議）
#
# 相容性:刻意不使用關聯陣列(macOS 內建 bash 為 3.2)。結果寫入 TSV,統計交給 awk。
#
set -euo pipefail

# 以 caffeinate 重新執行自己,擋掉 idle sleep。**必須在 cd 之前** —— exec 保留原本的
# 工作目錄,$0 仍是呼叫端給的路徑。
#
# 2026-09-09 實測:電池剩 8% 時 macOS 於 04:25 進入 idle sleep,整批連同 Docker 凍結六個半小時。
# 而睡著的那一刻正在量測的組別得到 141 req/s(前兩輪同組 1909 / 1791)——
# **那不是一筆偏低的量測,那是量到一半機器睡著了。** 若它剛好落在 1400 而不是 141,
# 這批數據會被靜靜地接受。
if [ -z "${MATRIX_CAFFEINATED:-}" ] && command -v caffeinate >/dev/null 2>&1; then
    MATRIX_CAFFEINATED=1 exec caffeinate -ims "$0" "$@"
fi

cd "$(dirname "$0")/.."

ROUNDS="${ROUNDS:-3}"
# 漂移門檻:三輪環境水位的全距若超過它，整批數據不可用。
# **一個永遠不會失敗的驗收不是驗收。**
DRIFT_THRESHOLD="${DRIFT_THRESHOLD:-15}"
# 單組全距的上限。**漂移看的是每輪的水位,不是每組的離散度** ——
# 一筆脫序的量測藏在單一組裡時,水位幾乎不動(漂移照樣過關),但那一組已經廢了。
#
# 全距本來就印在輸出上,**而印出來卻沒有任何東西根據它做判斷,
# 就是「一個永遠不會失敗的驗收」。**
#
# 25% 的依據是歷史批次:有效批次的單組全距落在 2%～12.3%,
# **門檻訂在那之上一截,而不是訂在讓某一批剛好通過的位置。**
GROUP_RANGE_MAX="${GROUP_RANGE_MAX:-25}"
# 單組耗時上限。正常一組約 55～130 秒（含重啟與暖到收斂）；遠超過它代表機器中途睡著或
# 被別的東西卡住，**而不是這一組比較慢**。
MAX_GROUP_SECONDS="${MAX_GROUP_SECONDS:-420}"

# 電源來源是一個**不會出現在條件表裡的變數**,而它會在批次期間自己改變:
# 電池持續放電、低電量觸發節流、再低就直接 idle sleep。漂移門檻抓不到這些 ——
# 水位算得出來,但「機器睡了六小時」不在那個指標裡。
POWER_SOURCE=$(pmset -g batt 2>/dev/null | sed -n "1s/.*'\(.*\)'.*/\1/p")
BATTERY_PCT=$(pmset -g batt 2>/dev/null | sed -n '2s/.*[^0-9]\([0-9][0-9]*\)%.*/\1/p')
if [ -n "$POWER_SOURCE" ] && [ "$POWER_SOURCE" != "AC Power" ] \
    && [ -z "${ALLOW_BATTERY:-}" ] && [ -z "${MATRIX_RESOLVE_ONLY:-}" ]; then
    echo "電源為「${POWER_SOURCE}」(電量 ${BATTERY_PCT:-?}%)——批次中止。" >&2
    echo "45 分鐘的批次在電池上跑,放電曲線會成為一個漸變的隱藏變數;低電量還會直接進入" >&2
    echo "idle sleep 把整批凍住。接上電源再跑,或明確設 ALLOW_BATTERY=1。" >&2
    exit 1
fi

ALL_GROUPS="P_noLock P_pessimistic P_optimistic P_redisPreDeduct V_noLock V_pessimistic V_optimistic V_redisPreDeduct"
# **變數名不可叫 GROUPS。** 那是 bash 的內建陣列變數（當前使用者的群組 ID），
# `${GROUPS:-...}` 會拿到它的第一個元素（macOS 上是 20，staff 群組），
# 於是整個矩陣變成「1 個組別、名叫 20」。
#
# 這個坑在小規模驗證時被蓋掉了——那次我明確設了 GROUPS=，剛好覆寫了內建值，
# 所以驗證通過、完整矩陣才炸。**能被參數覆寫掩蓋的預設值,驗證時要連預設路徑一起走。**
MATRIX_GROUPS="${MATRIX_GROUPS:-$ALL_GROUPS}"

# label 格式：**<模型><准入上限>_<策略>**
#
#   P_noLock           → 平台 / 准入上限用應用預設 / noLock
#   P1000_pessimistic  → 平台 / 准入上限 1000     / pessimistic
#   V_noLock           → 虛擬 / 上限不適用         / noLock
#
# 取代原本 strategy_of / virtual_of 兩份寫死的 case 清單 ——
# 那兩份清單每加一個維度就要改兩處，而這段解析加維度不必改。**行數也比原本少。**
#
# **向下相容是硬需求：** 預設八組（P_noLock…）是 Phase 1 已封存數據的來源，
# 准入上限留空即沿用應用預設（200），行為與改版前完全相同。
#
# 結果放全域而非回傳字串：一次解析要吐三個值，三次 $( ) 就是三次 fork。
LABEL_MODEL=""
LABEL_THREADS=""
LABEL_STRATEGY=""
parse_label() {
    local prefix
    case "$1" in
        *_*) : ;;
        *) echo "組別格式錯誤：$1（應為 <模型><准入上限>_<策略>）" >&2; return 1 ;;
    esac
    prefix="${1%%_*}"
    LABEL_STRATEGY="${1#*_}"
    # **策略要白名單，不能照單全收。** 打錯字若被當成合法策略，
    # 應用會退回預設策略而照樣跑完，得到的是「一組標錯名字的數據」。
    case "$LABEL_STRATEGY" in
        noLock|pessimistic|optimistic|redisPreDeduct) : ;;
        *) echo "未知的策略：${LABEL_STRATEGY}（來自組別 $1）" >&2; return 1 ;;
    esac
    case "$prefix" in
        P) LABEL_MODEL=false; LABEL_THREADS="" ;;
        V) LABEL_MODEL=true;  LABEL_THREADS="" ;;
        P[0-9]*) LABEL_MODEL=false; LABEL_THREADS="${prefix#P}" ;;
        V[0-9]*) LABEL_MODEL=true;  LABEL_THREADS="${prefix#V}" ;;
        *) echo "未知的模型前綴：${prefix}（來自組別 $1）" >&2; return 1 ;;
    esac
    # P12a 會通過上面的 P[0-9]* 而留下 "12a"。數字部分必須全是數字。
    if [ -n "$LABEL_THREADS" ]; then
        case "$LABEL_THREADS" in
            *[!0-9]*) echo "准入上限不是數字：${LABEL_THREADS}（來自組別 $1）" >&2; return 1 ;;
        esac
    fi
}

OUT_DIR=$(mktemp -d)
RESULTS="$OUT_DIR/results.tsv"
trap 'rm -rf "$OUT_DIR"' EXIT
# dur_s 附在最後一欄 —— 前面幾欄的位置是統計用 awk 的 $2 / $3 / $4,不動它們。
printf 'label\tround\telapsed_s\trps\tsold\toversold\terr5xx\tdur_s\tadmission\tapp_mem_pct\tcpu_app_ms\tcpu_app_sys_ms\tcpu_pg_ms\tcpu_pg_sys_ms\n' > "$RESULTS"

# 統計輔助:與 run-load-test.sh 使用完全相同的定義。
# **全距一律為 (max-min)/median** —— 換分母就能讓任何修正看起來有效。
median() {
    printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {
        if (NR == 0) { print "0"; exit }
        if (NR % 2) printf "%.2f\n", a[(NR+1)/2]; else printf "%.2f\n", (a[NR/2]+a[NR/2+1])/2
    }'
}

range_pct() {
    printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {
        if (NR == 0) { print "0.0"; exit }
        med = (NR % 2) ? a[(NR+1)/2] : (a[NR/2]+a[NR/2+1])/2;
        if (med > 0) printf "%.1f\n", (a[NR]-a[1])/med*100; else print "0.0"
    }'
}

group_list() {
    # shellcheck disable=SC2086
    set -- $MATRIX_GROUPS
    printf '%s\n' "$@"
}

GROUP_COUNT=$(group_list | wc -l | tr -d ' ')

# ---------------------------------------------------------------------------
# 排程。**抽成函式讓「檢查」與「實際執行」共用同一份邏輯** ——
# 兩份實作必然漂移,而漂移的症狀是「檢查通過但跑起來不是那樣」。
# ---------------------------------------------------------------------------
# 正向輪的數量。偶數輪都是反序,不佔旋轉的位置。
FWD_ROUNDS=$(( (ROUNDS + 1) / 2 ))

# **偶數輪 = 前一輪的完整反序;奇數輪 = 正向旋轉。**
#
# 為什麼不是「旋轉之後再把偶數輪反轉」—— 那個直覺的做法是錯的:
# 設配對三輪的先後為 (f₁,f₂,f₃),反轉第 2 輪得到 (f₁,¬f₂,f₃),於是
#   (先,先,先) 3:0 → (先,後,先) 2:1  改善
#   (先,後,先) 2:1 → (先,先,先) 3:0  **惡化**
# **它把一部分配對修好,同時把另一部分弄壞。**
#
# 完整反序則翻轉**所有**配對,因此第 1、2 輪對任一配對必定各得一次,
# 第 3 輪不論怎麼排都只能讓它成為 2:1 —— **對所有配對同時成立,無一例外。**
# 輪數為奇數時 2:1 即理論上界(兩個非負整數相加為奇數就不可能相等)。
#
# 正向輪之間仍然旋轉,因為反序只解決先後、不解決絕對位置 ——
# 兩種偏差要同時處理。
round_order() {
    local r="$1" m offset
    if [ $((r % 2)) -eq 0 ]; then
        round_order $((r - 1)) | awk '{a[NR]=$0} END {for (i=NR;i>=1;i--) print a[i]}'
        return
    fi
    m=$(( (r + 1) / 2 ))
    offset=$(( (m - 1) * GROUP_COUNT / FWD_ROUNDS ))
    group_list | awk -v off="$offset" -v n="$GROUP_COUNT" '
        {a[NR]=$0} END { for (i=0;i<n;i++) print a[(off+i)%n+1] }'
}

round_desc() {
    local r="$1" m
    if [ $((r % 2)) -eq 0 ]; then
        echo "第 $((r - 1)) 輪的完整反序"
    else
        m=$(( (r + 1) / 2 ))
        echo "正向,起點偏移 $(( (m - 1) * GROUP_COUNT / FWD_ROUNDS ))"
    fi
}

schedule_tsv() {
    local r pos label
    for r in $(seq 1 "$ROUNDS"); do
        pos=0
        for label in $(round_order "$r"); do
            pos=$((pos + 1))
            printf '%s\t%s\t%s\n' "$r" "$pos" "$label"
        done
    done
}

# 逐配對統計先後次數。
#
# **旋轉只均衡絕對位置,不均衡相對先後** —— 循環位移是保序的,配對 (A,B) 的先後
# 只取決於環繞點有沒有落在它們之間,落不到就每一輪都是 A 在前。
# 環境若有系統性的時間趨勢,那就成為只偏袒其中一方的偏差,而增加輪數消不掉它。
#
# 輪數為奇數時完全相等不可能,`ceil : floor` 即理論上界。
check_order_balance() {
    schedule_tsv | awk -v R="$ROUNDS" '
        { pos[$1 SUBSEP $3] = $2; if ($1 == 1) { L[$2] = $3; if ($2 > n) n = $2 } }
        END {
            lo = int(R / 2); hi = R - lo
            bad = 0; mn = R + 1; mx = -1
            for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) {
                a = L[i]; b = L[j]; c = 0
                for (r = 1; r <= R; r++) if (pos[r SUBSEP a] < pos[r SUBSEP b]) c++
                if (c < mn) mn = c
                if (c > mx) mx = c
                if (c < lo || c > hi) {
                    bad++
                    printf "  失衡:%s 先於 %s 共 %d / %d 輪\n", a, b, c, R
                }
            }
            printf "\n配對先後:最少 %d / 最多 %d(允許 %d～%d,共 %d 個配對)\n",
                mn, mx, lo, hi, n * (n - 1) / 2
            if (bad > 0) {
                printf ">>> **排程有缺陷** —— %d 個配對的先後固定,\n", bad
                printf "    環境若有時間趨勢,那些比較會被一致地偏移。\n"
                exit 1
            }
            printf ">>> 排程均衡:所有配對的先後都在允許範圍內。\n"
        }'
}

# 只解析不執行。**把「走一次預設路徑」從「開一次矩陣」變成一秒鐘的事。**
#
# 那條教訓的成本原本很高：驗證都會帶參數（為了縮小規模），而正式跑的是預設值，
# 兩條是不同的路徑——`GROUPS` 是 bash 內建變數那次就是這樣躲過驗證的。
# 檢查便宜到可以每次都做，它才真的會被做。
if [ -n "${MATRIX_RESOLVE_ONLY:-}" ]; then
    printf '%-22s %-8s %-14s %s\n' 組別 模型 准入上限 策略
    for label in $(group_list); do
        parse_label "$label"
        # 虛擬執行緒不受 tomcat 執行緒上限約束 —— **印「200」會是一個不是事實的數字**，
        # 而條件表上錯誤的數字比缺漏的更危險：缺漏看得出來，錯誤看不出來。
        if [ "$LABEL_MODEL" = true ]; then
            printf '%-22s %-8s %-14s %s\n' "$label" 虛擬 "不適用" "$LABEL_STRATEGY"
        else
            printf '%-22s %-8s %-14s %s\n' "$label" 平台 "${LABEL_THREADS:-200(預設)}" "$LABEL_STRATEGY"
        fi
    done
    echo
    echo "組別 ${GROUP_COUNT} 個 × ${ROUNDS} 輪 = $((GROUP_COUNT * ROUNDS)) 次重啟"
    echo
    echo "==================== 各輪排程 ===================="
    for r in $(seq 1 "$ROUNDS"); do
        printf '第 %s 輪(%s):%s\n' "$r" "$(round_desc "$r")" \
            "$(round_order "$r" | tr '\n' ' ')"
    done
    echo
    echo "==================== 絕對位置 ===================="
    echo "反序解決先後,旋轉解決位置 —— **兩種偏差要同時處理**,"
    echo "反序不得把旋轉原本解決的問題弄回來。"
    schedule_tsv | awk -v R="$ROUNDS" '
        { p[$3, $1] = $2; if ($1 == 1) { L[$2] = $3; if ($2 > n) n = $2 } }
        END {
            worst = 0
            for (i = 1; i <= n; i++) {
                line = ""; seen = 0; delete d
                for (r = 1; r <= R; r++) {
                    line = line sprintf("%3d", p[L[i], r])
                    if (!(p[L[i], r] in d)) { d[p[L[i], r]] = 1; seen++ }
                }
                printf "  %-22s 各輪位置:%s   相異 %d / %d\n", L[i], line, seen, R
                if (worst == 0 || seen < worst) worst = seen
            }
            printf "\n最少相異位置數:%d / %d 輪\n", worst, R
        }'
    echo
    echo "==================== 先後均衡 ===================="
    # **一次完整批次要數十分鐘,排程缺陷必須在付出那個成本之前就看得見。**
    check_order_balance
    exit $?
fi

# **先建一次 image。** 每組的 `up --force-recreate` 只重建容器,不重建 image ——
# 改了應用程式碼卻沒重建,量到的是舊 jar,而且**沒有任何徵兆**:
# 容器是新的、設定是新的、日誌照常輸出,只有 jar 是舊的。
# 本支就踩到了(新增的測量條件那一行整個沒出現,看起來像 grep 寫錯)。
#
# 建一次而不是每組帶 --build:一次就夠,而每組帶等於 18 次快取檢查。
echo "建置 app image(避免量到舊 jar)…"
docker compose --profile perf build app >/dev/null 2>&1 || {
    echo ">>> app image 建置失敗 —— 中止。" >&2
    exit 1
}

START_EPOCH=$(date +%s)

echo "==================== 交錯量測矩陣 ===================="
echo "組別 ${GROUP_COUNT} 個 × ${ROUNDS} 輪 = $((GROUP_COUNT * ROUNDS)) 次重啟"
echo "每次重啟都含「暖到收斂」——暖度綁在 JVM 實例上，重啟就沒了，這一項無法省。"
echo "漂移門檻：${DRIFT_THRESHOLD}%（超過即整批不可用）"
echo "單組全距上限：${GROUP_RANGE_MAX}%（超過即該組不可用——漂移抓不到組內的離散）"
echo "單組耗時上限：${MAX_GROUP_SECONDS}s（超過即中止，代表機器中途睡著或被卡住）"
echo "電源：${POWER_SOURCE:-未知}（電量 ${BATTERY_PCT:-?}%）—— 電源是量測條件的一部分。"
echo

for r in $(seq 1 "$ROUNDS"); do
    # 旋轉：每一輪把起點往後推，讓每組落在不同位置。
    # **確定性的旋轉而非隨機打亂** —— 只有幾輪時，隨機無法保證位置分佈平均。
    ordered=$(round_order "$r")

    echo "########## 第 ${r} 輪（$(round_desc "$r")）##########"
    for label in $ordered; do
        parse_label "$label"
        st="$LABEL_STRATEGY"
        vt="$LABEL_MODEL"
        # 留空即用應用預設 200 —— 顯式帶 200 與先前「完全不設」的實際生效值相同。
        tm="${LABEL_THREADS:-200}"
        group_start=$(date +%s)
        elapsed=$(( group_start - START_EPOCH ))

        # **postgres-perf 必須一起重建。** 資料目錄在 tmpfs，隨容器消滅；
        # 而 pg_multixact 是 TRUNCATE / CHECKPOINT / VACUUM FREEZE 都清不掉的
        # （截斷取決於整個 cluster 的 datminmxid，而 template0 的 datallowconn=false）。
        #
        # 第 13 支即毀於此：24 組跑下來累積 679MB，佔滿容器 1GB 上限的 68%，
        # 三輪水位單調下降、漂移 21.2%，整批作廢。實測重建後 679M → 16K、91.29% → 17.95%。
        #
        # **兩個服務要寫在同一道指令裡。** 只重建 postgres 的話 app 會連著一個空資料庫，
        # Flyway 不會重跑——症狀是 `relation "purchase_order" does not exist`。
        # depends_on 已設 condition: service_healthy，compose 會先起 postgres 再起 app。
        STRATEGY="$st" MAX_ATTEMPTS=100 POOL_SIZE=50 VIRTUAL_THREADS="$vt" TOMCAT_THREADS="$tm" \
            docker compose --profile perf up -d --force-recreate --wait postgres-perf app >/dev/null 2>&1

        # **准入上限取自應用自報,不取自我們剛才傳出去的值。**
        # 傳出去的是「應該是什麼」,自報的是「實際是什麼」——第 4 支的教訓。
        # 這一欄同時是 spec 要求的測量條件:效能數據必須附帶准入併發度上限。
        adm=$(docker compose --profile perf logs app 2>/dev/null \
            | grep -m1 '准入併發度上限' | sed 's/^.*上限 *: *//' | tr -d '\r' || true)
        if [ -z "$adm" ]; then
            echo
            echo ">>> **整批數據不可用** —— ${label}(第 ${r} 輪)的應用沒有報告准入上限。"
            echo "    最可能的原因是跑的是舊 jar(image 未重建)。**那會讓整批量到錯的東西。**"
            exit 1
        fi
        case "$adm" in 不適用*) adm=不適用 ;; esac

        log="$OUT_DIR/${label}_r${r}.log"
        RUNS=1 ./k6/run-load-test.sh > "$log" 2>&1 || true

        dur=$(( $(date +%s) - group_start ))
        # 睡眠會讓牆鐘時間出現大跳,而**那一組正在進行的量測本身就已經被毀掉了** ——
        # 繼續跑只會產出「與前面幾輪隔了數小時」的數據。整批中止,不留半批。
        if [ "$dur" -gt "$MAX_GROUP_SECONDS" ]; then
            echo
            echo ">>> **整批數據不可用** —— ${label}（第 ${r} 輪）耗時 ${dur}s，"
            echo "    超過單組上限 ${MAX_GROUP_SECONDS}s。機器極可能在量測期間睡眠或被其他負載卡住，"
            echo "    這一組的數字不是量測結果，而其後所有組別與前面幾輪已不在同一個時間脈絡上。"
            exit 1
        fi

        # app 容器記憶體。**1000 條平台執行緒的堆疊是本支的已知風險** ——
        # 每條預設 1MB,保留空間約 1GB,而容器上限 2g、heap 1536MB。
        # 觸及上限時量到的是「記憶體不夠」,不是「平台在高併發下比較慢」,
        # **而那兩者在吞吐數字上長得一模一樣。**
        appmem=$(docker stats --no-stream --format '{{.MemPerc}}' \
            "$(docker compose --profile perf ps -q app)" 2>/dev/null | tr -d '% ' || echo "")
        if [ -n "$appmem" ] && awk -v m="$appmem" 'BEGIN{exit !(m > 90)}'; then
            echo
            echo ">>> **整批數據不可用** —— ${label}(第 ${r} 輪)的 app 容器記憶體達 ${appmem}%。"
            echo "    此時量到的是資源不足,不是該組態的效能特性,兩者在吞吐上無法分辨。"
            exit 1
        fi

        # CPU 成本。**吞吐是結果不是機制** —— 兩個組態可以有相同的吞吐而 CPU 差一倍。
        # 取機器可讀的那一行,不是人類可讀的對齊版面。
        cpu_line=$(grep -m1 'CPU 摘要' "$log" || true)
        cpu_app=$(echo "$cpu_line" | sed -n 's/.*app=\([0-9.]*\).*/\1/p')
        cpu_asys=$(echo "$cpu_line" | sed -n 's/.*app_sys=\([0-9.]*\).*/\1/p')
        cpu_pg=$(echo "$cpu_line" | sed -n 's/.*[^_]pg=\([0-9.]*\).*/\1/p')
        cpu_psys=$(echo "$cpu_line" | sed -n 's/.*pg_sys=\([0-9.]*\).*/\1/p')
        cpu_thr=$(echo "$cpu_line" | sed -n 's/.*throttled=\([0-9]*\).*/\1/p')
        if [ -z "$cpu_app" ] || [ -z "$cpu_thr" ]; then
            echo
            echo ">>> **整批數據不可用** —— ${label}(第 ${r} 輪)沒有 CPU 摘要。"
            echo "    探針取不到數字時不得留空繼續:輸出少一欄看起來像 grep 寫錯,"
            echo "    而實際上可能是別的東西壞了。log 末尾:"
            tail -5 "$log" | sed 's/^/      /'
            exit 1
        fi
        # **節流不為零即整批中止** —— 與記憶體守則同一個處置。
        # 撞到配額時吞吐是被上限決定的,而症狀只是「數字比預期低」。
        if [ "$cpu_thr" -gt 0 ]; then
            echo
            echo ">>> **整批數據不可用** —— ${label}(第 ${r} 輪)量測期間撞到 CPU 配額"
            echo "    (節流 ${cpu_thr} 次)。此時的吞吐由配額決定,不是由被測特性決定。"
            exit 1
        fi

        rps=$(grep -m1 '中位數' "$log" | grep -oE '[0-9.]+' || echo "")
        sold=$(grep -m1 '累計售出' "$log" | grep -oE '[0-9]+$' || echo "")
        over=$(grep -m1 '超賣張數' "$log" | grep -oE '[0-9]+$' || echo "")
        e5=$(grep -m1 '5xx' "$log" | grep -oE '[0-9]+$' || echo "")

        # **單一組量測失敗即整批中止，不是跳過那一格。**
        #
        # 跳過的話那一組只有兩個值、其他組有三個 —— 那是**不對稱**，也就是偏差，
        # 而最後的表格會照常印出來，看起來與一批完整的數據沒有差別。
        # 實際踩到：2026-09-09 OrbStack 中途被關掉，這一行印了「本組本輪作廢」之後
        # 還繼續往下跑；若 Docker 只是抖一下就恢復，這批就會以「少一格」的狀態跑完。
        if [ -z "$rps" ]; then
            echo
            echo ">>> **整批數據不可用** —— ${label}（第 ${r} 輪）量測失敗，log 中沒有吞吐數字。"
            echo "    少掉一格會讓該組的樣本數與其他組不同,那是不對稱而非雜訊。"
            echo "    log 末尾:"
            tail -5 "$log" | sed 's/^/      /'
            exit 1
        fi
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$label" "$r" "$elapsed" "$rps" "${sold:-?}" "${over:-?}" "${e5:-?}" "$dur" "$adm" \
            "${appmem:-?}" "$cpu_app" "$cpu_asys" "$cpu_pg" "$cpu_psys" >> "$RESULTS"
        printf '  %-20s %10s req/s   准入=%-8s CPU app=%s(sys %s) pg=%s  t=%ss 耗時=%ss  售出=%-6s 超賣=%-6s 5xx=%s\n' \
            "$label" "$rps" "$adm" "$cpu_app" "$cpu_asys" "$cpu_pg" \
            "$elapsed" "$dur" "${sold:-?}" "${over:-?}" "${e5:-?}"
    done
    echo
done

BAD_GROUPS=0
echo "==================== 每組結果 ===================="
printf '%-20s %-28s %-12s %-8s\n' 組別 各輪 中位數 全距
for label in $(group_list); do
    vals=$(awk -F'\t' -v l="$label" '$1==l{print $4}' "$RESULTS")
    [ -z "$vals" ] && continue
    # shellcheck disable=SC2086
    grp_range=$(range_pct $vals)
    flag=""
    if awk -v g="$grp_range" -v t="$GROUP_RANGE_MAX" 'BEGIN{exit !(g > t)}'; then
        flag="   <<< **本組不可用**(超過 ${GROUP_RANGE_MAX}%)"
        BAD_GROUPS=$((BAD_GROUPS + 1))
    fi
    # shellcheck disable=SC2086
    printf '%-20s %-28s %-12s %-8s%s\n' "$label" \
        "$(echo $vals | sed 's/ / \/ /g')" "$(median $vals)" "${grp_range}%" "$flag"
done

if [ "$BAD_GROUPS" -gt 0 ]; then
    echo
    echo ">>> **整批數據不可用** —— ${BAD_GROUPS} 個組別的全距超過 ${GROUP_RANGE_MAX}%。"
    echo "    漂移看的是每輪的水位,抓不到藏在單一組裡的離散 ——"
    echo "    一筆脫序的量測幾乎不動水位,但那一組已經廢了。"
    echo "    **差距小於較不穩定那一方的全距時不得下結論,而該組的全距已經大到任何比較都無效。**"
fi

echo
echo "==================== 環境漂移 ===================="
echo "每一輪的水位 = 該輪所有組別吞吐的中位數。"
echo "**指標取自批次本身，不需要額外的哨兵組。**"
LEVELS=""
for r in $(seq 1 "$ROUNDS"); do
    rvals=$(awk -F'\t' -v rr="$r" '$2==rr{print $4}' "$RESULTS")
    [ -z "$rvals" ] && continue
    # shellcheck disable=SC2086
    lvl=$(median $rvals)
    LEVELS="$LEVELS $lvl"
    printf '  第 %s 輪水位：%s req/s\n' "$r" "$lvl"
done
# shellcheck disable=SC2086
DRIFT=$(range_pct $LEVELS)
POWER_END=$(pmset -g batt 2>/dev/null | sed -n "1s/.*'\(.*\)'.*/\1/p")
if [ -n "$POWER_SOURCE" ] && [ "$POWER_END" != "$POWER_SOURCE" ]; then
    echo
    echo ">>> **整批數據不可用** —— 電源在批次期間從「${POWER_SOURCE}」變成「${POWER_END}」。"
    echo "    那是一個條件表沒有列出、卻在量測中途改變的變數。"
fi

echo
printf '>>> 整批漂移：%s%%（門檻 %s%%）\n' "$DRIFT" "$DRIFT_THRESHOLD"
if awk -v d="$DRIFT" -v t="$DRIFT_THRESHOLD" 'BEGIN{exit !(d > t)}'; then
    echo ">>> **整批數據不可用** —— 環境在量測期間的變化足以蓋過層與層之間的差異。"
    echo "    不得從中挑選看起來合理的部分使用。"
else
    echo ">>> 批次有效：環境漂移在門檻之內，各組之間可比較。"
fi

echo
echo "==================== 單調性檢查 ===================="
echo "同一組的數值若隨時刻方向一致地變化，代表仍有東西在累積——"
echo "**單調的變化不是雜訊**，取中位數會把它藏起來而不是解決它。"
for label in $(group_list); do
    trend=$(awk -F'\t' -v l="$label" '$1==l{print $3"\t"$4}' "$RESULTS" | sort -n | awk '
        {v[NR]=$2} END {
            if (NR < 3) { print "資料不足"; exit }
            up = (v[1] < v[2] && v[2] < v[3]); down = (v[1] > v[2] && v[2] > v[3]);
            print (up ? "單調上升 ← 仍在暖機?" : (down ? "單調下降 ← 有東西在累積?" : "非單調"))
        }')
    printf '  %-20s %s\n' "$label" "$trend"
done

echo
echo "原始結果:"
cat "$RESULTS"
