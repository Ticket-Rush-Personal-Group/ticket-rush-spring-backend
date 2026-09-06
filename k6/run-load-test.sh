#!/usr/bin/env bash
#
# 執行一次壓測並輸出可直接引用的摘要。
#
# 前置重置與後置查詢以 psql 完成，不放進 k6：k6 連不了資料庫，
# 而「售出張數 / 最終庫存 / 超賣張數」是 strategy-* 驗收的一半，k6 量不到。
# 也刻意不為此在應用加測試專用端點——那會污染正式 API。
#
# 用法：
#   ./k6/run-load-test.sh
#   VIRTUAL_THREADS=true ./k6/run-load-test.sh    # 需先以該設定重啟 app
#
set -euo pipefail
cd "$(dirname "$0")/.."

INITIAL_STOCK="${INITIAL_STOCK:-500}"
VUS="${VUS:-1000}"
# 快取的保存期限（秒）。預設一天，遠長於任何一次壓測。
CACHE_TTL_SECONDS="${CACHE_TTL_SECONDS:-86400}"

# -q 不可省：INSERT ... RETURNING 會同時輸出 tuple 與 "INSERT 0 1" 這行 command status，
# 後者會被一起吃進變數，造成下一句 SQL 語法錯誤。
psql() { docker compose --profile perf exec -T postgres-perf psql -q -U postgres -d ticket_rush_db -tA "$@"; }
redis_cli() { docker compose --profile perf exec -T redis-perf redis-cli "$@"; }

# 當前策略取自應用的啟動記錄，不是取自環境變數——
# 第 4 支的教訓：compose 可能靜默替換容器，環境變數說的是「應該是什麼」而非「實際是什麼」。
current_strategy() {
    docker compose --profile perf logs app 2>/dev/null | grep -m1 "當前策略" | sed 's/.*: *//' | tr -d '\r'
}

echo "===== 前置：重置場次與庫存 ====="
psql -c "TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE;" >/dev/null
EVENT_ID=$(psql -c "INSERT INTO event (name, sales_start_at, total_quantity) VALUES ('壓測場次', now(), ${INITIAL_STOCK}) RETURNING id;")
psql -c "INSERT INTO stock (event_id, available) VALUES (${EVENT_ID}, ${INITIAL_STOCK});" >/dev/null

STRATEGY_IN_USE=$(current_strategy)

# 第 3 層把庫存搬到 Redis。**應用刻意不提供「載入庫存」的端點**——
# run-load-test.sh 的既有原則是不為壓測在正式 API 開後門，因此由本腳本直接寫入。
if [ "$STRATEGY_IN_USE" = "redisPreDeduct" ]; then
    redis_cli --scan --pattern 'stock:*' | xargs -r redis_cli DEL >/dev/null 2>&1 || true
    redis_cli --scan --pattern 'purchased:*' | xargs -r redis_cli DEL >/dev/null 2>&1 || true
    # 清空 stream 用 XTRIM 而不是 DEL：DEL 會連 consumer group 一起刪掉，
    # 而應用正在跑，它的消費者會拿到 NOGROUP 並中止訂閱——症狀是「訂單再也不落庫」。
    redis_cli XTRIM orders MAXLEN 0 >/dev/null 2>&1 || true
    # **必須帶過期時間。** 不帶的話 stock 與 purchased 都會永遠留著，
    # 而 purchased 是「每個買過票的人一個 key」——那是無界的記憶體成長，
    # 且不會有任何測試變紅、不會有錯誤訊息、壓測也看不出來（環境每次重建）。
    #
    # 這裡代表的是「快取的保存期限」，不是「場次的結束時間」——schema 沒有後者。
    # 銷售期若超過它，場次會在銷售中變成「未開賣」：fail-closed 的停擺，不是資料錯誤。
    redis_cli SET "stock:${EVENT_ID}" "${INITIAL_STOCK}" EX "${CACHE_TTL_SECONDS}" >/dev/null
    echo "Redis 快取庫存已載入：stock:${EVENT_ID} = ${INITIAL_STOCK}（保存期限 ${CACHE_TTL_SECONDS}s）"
fi

echo "場次 ${EVENT_ID}，初始庫存 ${INITIAL_STOCK}，策略 ${STRATEGY_IN_USE}"
echo

echo "===== 執行 k6（${VUS} VU，每 VU 一次請求）====="
# --no-deps 不可省：docker compose run 會依「當前解析到的設定」比對 depends_on 的服務，
# 不一致就重建它。本腳本執行時若沒有 VIRTUAL_THREADS 環境變數，compose 會解析成 false，
# 於是把正在跑虛擬執行緒的 app 靜默替換成平台執行緒版本——
# 症狀是「設定明明改了卻沒生效」，而且沒有任何錯誤訊息。
docker compose --profile perf run --rm --no-deps -e EVENT_ID="${EVENT_ID}" -e VUS="${VUS}" k6

echo
echo "===== 後置：正確性欄位（k6 量不到，須查資料庫）====="

# 第 3 層是非同步落庫：k6 結束時訂單還沒全部進資料庫。
# 這段等待本身就是本層的產出——「回應之後還要多久訂單才全部出現」。
DRAIN_MS=0
if [ "$STRATEGY_IN_USE" = "redisPreDeduct" ]; then
    echo "等待非同步落庫收斂..."
    # 以輪詢次數 × 間隔計算，不用 date：BSD 的 date 沒有毫秒，
    # 而 `date +%s000` 其實是「秒 × 1000」——所有低於一秒的耗時都會顯示為 0，
    # 一個看起來像「瞬間完成」的錯誤數字。
    POLLS=0
    PREV=-1
    for _ in $(seq 1 300); do
        CURRENT=$(psql -c "SELECT COALESCE(SUM(quantity),0) FROM purchase_order;")
        PENDING=$(redis_cli XPENDING orders order-persistence 2>/dev/null | head -1 | tr -d '\r')
        if [ "$CURRENT" = "$PREV" ] && [ "${PENDING:-0}" = "0" ]; then break; fi
        PREV="$CURRENT"
        POLLS=$((POLLS + 1))
        sleep 0.2
    done
    # 解析度為 200ms；這是「k6 結束後到訂單全部可見」的上界。
    DRAIN_MS=$((POLLS * 200))
fi

SOLD=$(psql -c "SELECT COALESCE(SUM(quantity),0) FROM purchase_order;")
ORDERS=$(psql -c "SELECT count(*) FROM purchase_order;")

if [ "$STRATEGY_IN_USE" = "redisPreDeduct" ]; then
    # **判準必須換來源。** 第 3 層完全不扣資料庫的 stock.available——
    # 沿用「初始 − 資料庫餘量」會算出庫存減少 0、超賣等於全部售出，一個完全錯誤的結論。
    # 本層的餘量在 Redis，而「超賣」仍是「售出超過初始配額」。
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
# 才拿得到，因此這裡主動 stop。每組壓測本來就要重啟應用切換策略，不增加額外步驟。
if echo "$CONDITIONS" | grep -q "當前策略.*optimistic"; then
    echo
    echo "===== 重試次數分佈（樂觀鎖）====="
    docker compose --profile perf stop app >/dev/null 2>&1
    docker compose --profile perf logs app 2>/dev/null \
        | sed 's/^app-1  *| //' \
        | sed -n '/重試次數分佈/,/^=====*$/p'
    echo
    echo "（已 stop app 以取得分佈；下一組壓測請重新 up）"
fi
