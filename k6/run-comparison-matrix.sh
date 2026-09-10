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
# 單組耗時上限。正常一組約 55～130 秒（含重啟與暖到收斂）；遠超過它代表機器中途睡著或
# 被別的東西卡住，**而不是這一組比較慢**。
MAX_GROUP_SECONDS="${MAX_GROUP_SECONDS:-420}"

# 電源來源是一個**不會出現在條件表裡的變數**,而它會在批次期間自己改變:
# 電池持續放電、低電量觸發節流、再低就直接 idle sleep。漂移門檻抓不到這些 ——
# 水位算得出來,但「機器睡了六小時」不在那個指標裡。
POWER_SOURCE=$(pmset -g batt 2>/dev/null | sed -n "1s/.*'\(.*\)'.*/\1/p")
BATTERY_PCT=$(pmset -g batt 2>/dev/null | sed -n '2s/.*[^0-9]\([0-9][0-9]*\)%.*/\1/p')
if [ -n "$POWER_SOURCE" ] && [ "$POWER_SOURCE" != "AC Power" ] && [ -z "${ALLOW_BATTERY:-}" ]; then
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

# label → 策略與執行緒模型。以 case 而非關聯陣列表達，維持 bash 3.2 相容。
strategy_of() {
    case "$1" in
        P_noLock|V_noLock) echo noLock ;;
        P_pessimistic|V_pessimistic) echo pessimistic ;;
        P_optimistic|V_optimistic) echo optimistic ;;
        P_redisPreDeduct|V_redisPreDeduct) echo redisPreDeduct ;;
        *) echo "未知的組別:$1" >&2; return 1 ;;
    esac
}

virtual_of() {
    case "$1" in
        P_*) echo false ;;
        V_*) echo true ;;
        *) echo "未知的組別:$1" >&2; return 1 ;;
    esac
}

OUT_DIR=$(mktemp -d)
RESULTS="$OUT_DIR/results.tsv"
trap 'rm -rf "$OUT_DIR"' EXIT
# dur_s 附在最後一欄 —— 前面幾欄的位置是統計用 awk 的 $2 / $3 / $4,不動它們。
printf 'label\tround\telapsed_s\trps\tsold\toversold\terr5xx\tdur_s\n' > "$RESULTS"

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
START_EPOCH=$(date +%s)

echo "==================== 交錯量測矩陣 ===================="
echo "組別 ${GROUP_COUNT} 個 × ${ROUNDS} 輪 = $((GROUP_COUNT * ROUNDS)) 次重啟"
echo "每次重啟都含「暖到收斂」——暖度綁在 JVM 實例上，重啟就沒了，這一項無法省。"
echo "漂移門檻：${DRIFT_THRESHOLD}%（超過即整批不可用）"
echo "單組耗時上限：${MAX_GROUP_SECONDS}s（超過即中止，代表機器中途睡著或被卡住）"
echo "電源：${POWER_SOURCE:-未知}（電量 ${BATTERY_PCT:-?}%）—— 電源是量測條件的一部分。"
echo

for r in $(seq 1 "$ROUNDS"); do
    # 旋轉：每一輪把起點往後推，讓每組落在不同位置。
    # **確定性的旋轉而非隨機打亂** —— 只有幾輪時，隨機無法保證位置分佈平均。
    offset=$(( (r - 1) * GROUP_COUNT / ROUNDS ))
    ordered=$(group_list | awk -v off="$offset" -v n="$GROUP_COUNT" '
        {a[NR]=$0} END { for (i=0;i<n;i++) print a[(off+i)%n+1] }')

    echo "########## 第 ${r} 輪（起點偏移 ${offset}）##########"
    for label in $ordered; do
        st=$(strategy_of "$label")
        vt=$(virtual_of "$label")
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
        STRATEGY="$st" MAX_ATTEMPTS=100 POOL_SIZE=50 VIRTUAL_THREADS="$vt" \
            docker compose --profile perf up -d --force-recreate --wait postgres-perf app >/dev/null 2>&1

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
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$label" "$r" "$elapsed" "$rps" "${sold:-?}" "${over:-?}" "${e5:-?}" "$dur" >> "$RESULTS"
        printf '  %-20s %10s req/s   t=%ss  耗時=%ss  售出=%-6s 超賣=%-6s 5xx=%s\n' \
            "$label" "$rps" "$elapsed" "$dur" "${sold:-?}" "${over:-?}" "${e5:-?}"
    done
    echo
done

echo "==================== 每組結果 ===================="
printf '%-20s %-28s %-12s %-8s\n' 組別 各輪 中位數 全距
for label in $(group_list); do
    vals=$(awk -F'\t' -v l="$label" '$1==l{print $4}' "$RESULTS")
    [ -z "$vals" ] && continue
    # shellcheck disable=SC2086
    printf '%-20s %-28s %-12s %-8s\n' "$label" \
        "$(echo $vals | sed 's/ / \/ /g')" "$(median $vals)" "$(range_pct $vals)%"
done

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
