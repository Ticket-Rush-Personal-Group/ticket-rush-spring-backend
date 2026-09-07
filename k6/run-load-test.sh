#!/usr/bin/env bash
#
# 執行壓測並輸出可直接引用的摘要。
#
# 前置重置與後置查詢以 psql 完成，不放進 k6：k6 連不了資料庫，
# 而「售出張數 / 最終庫存 / 超賣張數」是 strategy-* 驗收的一半，k6 量不到。
# 也刻意不為此在應用加測試專用端點——那會污染正式 API。
#
# **一定會先跑一次暖機並丟棄。** 每組壓測都是剛啟動的 JVM，
# 那 1000 個請求打的是還沒經過 JIT 的程式碼——不暖機的話整場壓測都在暖機階段裡。
# 實測(第 8 支)：未暖機時同設定連續量測的全距，無鎖 52.5%、Redis 預扣 38.4%
# （以下方 range_pct 的定義 (max-min)/median 重算；第 8 支口頭引用的 72% / 48%
#  用的是 (max-min)/min，換分母不算改善——比較前後必須用同一個定義）。
#
# **輸出一律包含每次的值、中位數、全距。** 只跑一次時全距為 0，
# 而那個 0 是提醒——「這個數字沒有可信度資訊」，不是「這個數字很穩定」。
#
# 用法：
#   ./k6/run-load-test.sh                 # 暖機一次 + 量測一次
#   RUNS=3 ./k6/run-load-test.sh          # 暖機一次 + 量測三次，輸出中位數與全距
#   WARMUP=false ./k6/run-load-test.sh    # 略過暖機（只在除錯腳本本身時使用）
#
set -euo pipefail
cd "$(dirname "$0")/.."

# 庫存與請求數維持 2:1——競爭的性質不變（一半的人買得到），只是窗口變長。
# 25000 張 / 50000 個請求，在暖機後的吞吐下約 20 秒，
# 遠大於單次 GC 停頓的影響（原本 0.4 秒的窗口，一次停頓就砍半）。
INITIAL_STOCK="${INITIAL_STOCK:-25000}"
VUS="${VUS:-1000}"
ITERATIONS="${ITERATIONS:-50}"
RUNS="${RUNS:-1}"
WARMUP="${WARMUP:-true}"
# **暖機暖到「收斂」為止，不用固定輪數。**
#
# 實測(第 11 支)：Redis 預扣在固定輪數下,量到的數字隨輪數單調上升 ——
# 1 輪 → 3810、2 輪 → 5219、3 輪 → 8130 req/s。**每加一輪就更高一截,始終沒有停。**
# 而每一次的第 1 輪都落在 1600～1800,那正是「完全沒暖」的水位。
#
# 固定輪數的問題是它必然低估,而低估的幅度隨層而異(有背景執行緒的層收斂更慢)——
# 那會讓層與層之間的比較失真,而且是系統性的失真,不是雜訊。
#
# 因此改為:連續兩輪的差距小於門檻才算暖完,並設上限避免無限迴圈。
WARMUP_CONVERGE_PCT="${WARMUP_CONVERGE_PCT:-5}"
WARMUP_MAX_ROUNDS="${WARMUP_MAX_ROUNDS:-10}"
# 快取的保存期限（秒）。預設一天，遠長於任何一次壓測。
CACHE_TTL_SECONDS="${CACHE_TTL_SECONDS:-86400}"

# -q 不可省：INSERT ... RETURNING 會同時輸出 tuple 與 "INSERT 0 1" 這行 command status，
# 後者會被一起吃進變數，造成下一句 SQL 語法錯誤。
psql() { docker compose --profile perf exec -T postgres-perf psql -q -U postgres -d ticket_rush_db -tA "$@"; }
redis_cli() { docker compose --profile perf exec -T redis-perf redis-cli "$@"; }

# 當前策略取自應用的啟動記錄，不是取自環境變數——
# 第 4 支的教訓：compose 可能靜默替換容器，環境變數說的是「應該是什麼」而非「實際是什麼」。
# 應用剛啟動時，啟動記錄可能還沒寫出來（healthcheck 通過不代表 ApplicationRunner 已執行完）。
#
# **找不到時必須大聲失敗，不能讓腳本靜默死掉。** 原本直接 grep，找不到就回 1，
# 而 `set -e` 會讓 `STRATEGY_IN_USE=$(current_strategy)` 中止整個腳本——
# 那一行在任何 echo 之前，於是**連一個字都不會輸出**，看起來像什麼事都沒發生。
current_strategy() {
    local attempt found
    for attempt in $(seq 1 30); do
        found=$(docker compose --profile perf logs app 2>/dev/null | grep -m1 "當前策略" | sed 's/.*: *//' | tr -d '\r' || true)
        if [ -n "$found" ]; then
            echo "$found"
            return 0
        fi
        sleep 1
    done
    echo "無法從應用的啟動記錄判斷當前策略（等了 30 秒）。應用起來了嗎？" >&2
    return 1
}

STRATEGY_IN_USE=$(current_strategy)
EVENT_ID=""

# ---------------------------------------------------------------------------
# 重置。**只有這一份實作**——暖機與正式量測都走它。
#
# 另寫一套給暖機用的話必然漂移，而漂移的症狀正是「第一次跑的初始狀態與後續不同」，
# 那恰好是暖機要消除的東西。
# ---------------------------------------------------------------------------
reset_data() {
    psql -c "TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE;" >/dev/null
    # **CHECKPOINT 不可省。** TRUNCATE 清得掉資料表，清不掉 WAL。
    # 每一輪對單一庫存列做兩萬五千次 UPDATE、外加同量的 INSERT，WAL 累積很快，
    # 而壓測環境的 postgres 資料放在 tmpfs（記憶體）——WAL 堆積會直接吃掉可用記憶體。
    #
    # 症狀是「同一組的三次量測逐次變慢」（實測悲觀鎖 4572 → 4246 → 3953），
    # 看起來像雜訊，實際上是單調衰退。強制檢查點讓每一輪從同樣的狀態開始。
    psql -c "CHECKPOINT;" >/dev/null
    EVENT_ID=$(psql -c "INSERT INTO event (name, sales_start_at, total_quantity) VALUES ('壓測場次', now(), ${INITIAL_STOCK}) RETURNING id;")
    psql -c "INSERT INTO stock (event_id, available) VALUES (${EVENT_ID}, ${INITIAL_STOCK});" >/dev/null

    # 第 3 層把庫存搬到 Redis。**應用刻意不提供「載入庫存」的端點**——
    # 既有原則是不為壓測在正式 API 開後門，因此由本腳本直接寫入。
    if [ "$STRATEGY_IN_USE" = "redisPreDeduct" ]; then
        # **掃描與刪除都在容器內完成，用一個 sh -c。**
        #
        # 原本寫成 `redis_cli --scan ... | xargs -r redis_cli DEL`，那是壞的：
        # redis_cli 是 shell function，而 **xargs 無法執行 shell function** ——
        # 它 exec 的是真正的執行檔。錯誤被 `2>&1 || true` 吞掉，
        # 於是 purchased:* 從來沒有被刪除過，而且完全沒有徵兆。
        #
        # 第 8 支沒有暴露這個 bug，是因為當時每個使用者每輪只買 1 張、
        # 只跑三輪——累計 3 張仍在限購上限 4 以下。放大成每 VU 50 次之後，
        # 使用者在第四輪就撞上限購，量到的變成「限購拒絕的吞吐」。
        redis_purge() {
            docker compose --profile perf exec -T redis-perf sh -c \
                "redis-cli --scan --pattern '$1' | xargs -r redis-cli UNLINK" >/dev/null 2>&1 || true
        }
        redis_purge 'stock:*'
        redis_purge 'purchased:*'
        # 清空 stream 用 XTRIM 而不是 DEL：DEL 會連 consumer group 一起刪掉，
        # 而應用正在跑，它的消費者會拿到 NOGROUP 並中止訂閱——症狀是「訂單再也不落庫」。
        redis_cli XTRIM orders MAXLEN 0 >/dev/null 2>&1 || true
        # **必須帶過期時間。** 不帶的話 purchased 是「每個買過票的人一個 key」，
        # 會無限累積，且不會有任何測試變紅、不會有錯誤訊息。
        redis_cli SET "stock:${EVENT_ID}" "${INITIAL_STOCK}" EX "${CACHE_TTL_SECONDS}" >/dev/null
    fi
}

# ---------------------------------------------------------------------------
# 跑一次 k6，把輸出寫到指定檔案。
#
# --no-deps 不可省：docker compose run 會依「當前解析到的設定」比對 depends_on 的服務，
# 不一致就重建它。本腳本執行時若沒有 VIRTUAL_THREADS 環境變數，compose 會解析成 false，
# 於是把正在跑虛擬執行緒的 app 靜默替換成平台執行緒版本——
# 症狀是「設定明明改了卻沒生效」，而且沒有任何錯誤訊息。
# ---------------------------------------------------------------------------
run_k6() {
    docker compose --profile perf run --rm --no-deps \
        -e EVENT_ID="${EVENT_ID}" -e VUS="${VUS}" -e ITERATIONS="${ITERATIONS}" k6 >"$1" 2>&1
}

# 等非同步落庫收斂。第 3 層專用——k6 結束時訂單還沒全部進資料庫。
# 回傳收斂耗時（毫秒，解析度 200ms）。
drain_async_persistence() {
    [ "$STRATEGY_IN_USE" = "redisPreDeduct" ] || { echo 0; return; }

    # 以輪詢次數 × 間隔計算，不用 date：BSD 的 date 沒有毫秒，
    # 而 `date +%s000` 其實是「秒 × 1000」——所有低於一秒的耗時都會顯示為 0，
    # 一個看起來像「瞬間完成」的錯誤數字。
    local polls=0 prev=-1 current pending
    for _ in $(seq 1 300); do
        current=$(psql -c "SELECT COALESCE(SUM(quantity),0) FROM purchase_order;")
        pending=$(redis_cli XPENDING orders order-persistence 2>/dev/null | head -1 | tr -d '\r')
        if [ "$current" = "$prev" ] && [ "${pending:-0}" = "0" ]; then break; fi
        prev="$current"
        polls=$((polls + 1))
        sleep 0.2
    done
    echo $((polls * 200))
}

# 從 k6 的輸出取出每秒請求數。
extract_rps() {
    grep -m1 'http_reqs' "$1" | grep -oE '[0-9]+\.[0-9]+/s' | head -1 | sed 's|/s||'
}

extract_metric() {
    grep -m1 'http_req_duration' "$1" | grep -oE "$2=[^ ]+" | head -1 | sed "s|$2=||"
}

# k6 摘要中某一列的第一個數值。列的形狀是 `name.....: 25000  11574.28/s`。
#
# **值為 0 的自訂 counter 不會出現在 k6 的摘要裡**，因此找不到時必須回 0 而不是失敗。
# 少了 `|| true`，`set -e` 會讓整個腳本在「這一組完全沒有錯誤」時中止——
# 一個只在系統健康時才發生的失敗。
extract_counter() {
    { grep -m1 "$2" "$1" | awk -F: '{print $2}' | awk '{print $1}'; } 2>/dev/null || true
}

# 中位數。**刻意不用平均**——平均會被離群值拉到一個從未出現過的值上
# （實測：無鎖三次為 494 / 682 / 852）。
median() {
    printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {
        if (NR % 2) printf "%.2f\n", a[(NR+1)/2];
        else printf "%.2f\n", (a[NR/2] + a[NR/2+1]) / 2
    }'
}

# 全距佔中位數的百分比：(max − min) / median。
#
# **定義必須寫死並標示出來。** 同一組數字用 (max−min)/min 會算出 72%、
# 用 (max−min)/median 會算出 52.5%——換個分母就能讓修正「看起來有效」，
# 而那是最容易在收尾時不知不覺發生的自我欺騙。
# 比較修正前後時，兩邊必須用同一個定義重算。
range_pct() {
    printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {
        med = (NR % 2) ? a[(NR+1)/2] : (a[NR/2] + a[NR/2+1]) / 2;
        if (med > 0) printf "%.1f\n", (a[NR] - a[1]) / med * 100; else print "0.0"
    }'
}

TMPDIR_RUN=$(mktemp -d)
trap 'rm -rf "$TMPDIR_RUN"' EXIT

echo "策略 ${STRATEGY_IN_USE}，初始庫存 ${INITIAL_STOCK}，${VUS} VU × ${ITERATIONS} 次 = $((VUS * ITERATIONS)) 個請求，量測 ${RUNS} 次"
echo

# ---------------------------------------------------------------------------
# 暖機。跑一次完整的負載並丟棄。
#
# **參數與正式量測完全相同。** 用較小的負載暖機會讓 JIT 走上不同的分支路徑，
# 連線池與資料庫的 buffer cache 也不會進入正式量測時的狀態。
# ---------------------------------------------------------------------------
WARMUP_ROUNDS_USED=0
WARMUP_CONVERGED="否"
if [ "$WARMUP" = "true" ]; then
    echo "===== 暖機至收斂（連續兩輪差距 < ${WARMUP_CONVERGE_PCT}%，上限 ${WARMUP_MAX_ROUNDS} 輪）====="
    prev=""
    for w in $(seq 1 "$WARMUP_MAX_ROUNDS"); do
        reset_data
        run_k6 "$TMPDIR_RUN/warmup_$w.log"
        drain_async_persistence >/dev/null
        cur=$(extract_rps "$TMPDIR_RUN/warmup_$w.log")
        WARMUP_ROUNDS_USED=$w

        if [ -n "$prev" ]; then
            delta=$(awk -v a="$prev" -v b="$cur" 'BEGIN{ if (b>0) printf "%.1f", (b>a? b-a : a-b)/b*100; else print 999 }')
            echo "  第 ${w} 輪：${cur} req/s（與前一輪相差 ${delta}%）"
            if awk -v d="$delta" -v t="$WARMUP_CONVERGE_PCT" 'BEGIN{exit !(d < t)}'; then
                WARMUP_CONVERGED="是"
                break
            fi
        else
            echo "  第 ${w} 輪：${cur} req/s"
        fi
        prev="$cur"
    done
    if [ "$WARMUP_CONVERGED" = "否" ]; then
        echo "  >>> 警告：${WARMUP_MAX_ROUNDS} 輪內未收斂 —— **本組數據不得用於跨層比較**"
    fi
    echo
fi

RPS_VALUES=()
LAST_LOG=""
DRAIN_MS=0

for i in $(seq 1 "$RUNS"); do
    echo "===== 量測 ${i}/${RUNS} ====="
    reset_data
    run_k6 "$TMPDIR_RUN/run_$i.log"
    DRAIN_MS=$(drain_async_persistence)
    LAST_LOG="$TMPDIR_RUN/run_$i.log"

    rps=$(extract_rps "$LAST_LOG")
    RPS_VALUES+=("$rps")
    echo "  ${rps} req/s   avg=$(extract_metric "$LAST_LOG" avg)   p(99)=$(extract_metric "$LAST_LOG" 'p\(99\)')"
done

echo
echo "===== 吞吐 ====="
printf '各次          : %s\n' "$(printf '%s / ' "${RPS_VALUES[@]}" | sed 's| / $||')"
printf '中位數        : %s req/s\n' "$(median "${RPS_VALUES[@]}")"
# 全距是這份輸出裡最重要的一個數字：兩組數據的差距若小於全距，就不能下結論。
printf '>>> 全距      : %s%%   ((max-min)/median)\n' "$(range_pct "${RPS_VALUES[@]}")"
printf '暖機          : %s 輪，收斂 %s\n' "$WARMUP_ROUNDS_USED" "$WARMUP_CONVERGED"
if [ "$RUNS" -eq 1 ]; then
    echo "    （只量了一次，全距 0 代表「沒有可信度資訊」，不代表穩定）"
fi

echo
echo "===== 失敗率（取自最後一次量測）====="
FAILED_RATE=$(extract_counter "$LAST_LOG" 'http_req_failed')
CLIENT_ERRORS=$(extract_counter "$LAST_LOG" 'client_errors')
SERVER_ERRORS=$(extract_counter "$LAST_LOG" 'server_errors')
printf 'http_req_failed : %s\n4xx（含 409）  : %s\n5xx             : %s\n' \
    "${FAILED_RATE:-n/a}" "${CLIENT_ERRORS:-0}" "${SERVER_ERRORS:-0}"
# **409 是策略正確運作的證據，不是故障。** 庫存不足、超過限購、重試耗盡、
# 場次未開賣都回 409。相對地 5xx 才是系統故障，任何一個都代表這組數據不能用。
if [ "${SERVER_ERRORS:-0}" != "0" ]; then
    echo ">>> 警告：出現 ${SERVER_ERRORS} 個 5xx —— **本組數據不得採用**，須先查明原因"
else
    echo "（4xx 主要是 409：庫存不足／超過限購／重試耗盡，那是併發控制在生效）"
fi

echo
echo "===== 正確性欄位（取自最後一次量測；每次都應相同，不同即代表有東西壞了）====="
SOLD=$(psql -c "SELECT COALESCE(SUM(quantity),0) FROM purchase_order;")
ORDERS=$(psql -c "SELECT count(*) FROM purchase_order;")

if [ "$STRATEGY_IN_USE" = "redisPreDeduct" ]; then
    # **判準必須換來源。** 第 3 層完全不扣資料庫的 stock.available——
    # 沿用「初始 − 資料庫餘量」會算出庫存減少 0、超賣等於全部售出，一個完全錯誤的結論。
    REMAINING=$(redis_cli GET "stock:${EVENT_ID}" | tr -d '\r')
    DISCREPANCY=$(( (INITIAL_STOCK - REMAINING) - SOLD ))
    OVERSOLD=$(( SOLD - INITIAL_STOCK ))
    [ "$OVERSOLD" -lt 0 ] && OVERSOLD=0
    printf '訂單筆數      : %s\n累計售出張數  : %s\n初始配額      : %s\n快取餘量      : %s\n>>> 超賣張數  : %s\n>>> 對帳差額  : %s\n>>> 落庫收斂  : %s ms\n' \
        "$ORDERS" "$SOLD" "$INITIAL_STOCK" "$REMAINING" "$OVERSOLD" "$DISCREPANCY" "$DRAIN_MS"
else
    REMAINING=$(psql -c "SELECT available FROM stock WHERE event_id = ${EVENT_ID};")
    DECREASE=$((INITIAL_STOCK - REMAINING))
    OVERSOLD=$((SOLD - DECREASE))
    printf '訂單筆數      : %s\n累計售出張數  : %s\n初始庫存      : %s\n最終庫存      : %s\n庫存實際減少  : %s\n>>> 超賣張數  : %s\n' \
        "$ORDERS" "$SOLD" "$INITIAL_STOCK" "$REMAINING" "$DECREASE" "$OVERSOLD"
fi

echo
echo "===== 測量條件（取自應用的啟動記錄，非設定檔）====="
CONDITIONS=$(docker compose --profile perf logs app 2>/dev/null | grep -A8 "執行環境" | tail -9 | sed 's/^app-1  *| //')
echo "$CONDITIONS"

# 樂觀鎖專屬：重試次數分佈。
#
# 分佈刻意只在 ContextClosedEvent 輸出，壓測期間完全不印——1000 併發下的 log I/O
# 會影響被量測的數字本身，而觀測手段不該改變被觀測的對象。代價是必須讓應用正常關閉
# 才拿得到，因此這裡主動 stop。**放在所有量測之後**，否則會把後續的量測打斷。
if [ "$STRATEGY_IN_USE" = "optimistic" ]; then
    echo
    echo "===== 重試次數分佈（樂觀鎖，累計含暖機）====="
    docker compose --profile perf stop app >/dev/null 2>&1
    docker compose --profile perf logs app 2>/dev/null \
        | sed 's/^app-1  *| //' \
        | sed -n '/重試次數分佈/,/^=====*$/p'
    echo
    echo "（已 stop app 以取得分佈；下一組壓測請重新 up）"
fi
