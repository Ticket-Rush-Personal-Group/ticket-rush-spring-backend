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

# -q 不可省：INSERT ... RETURNING 會同時輸出 tuple 與 "INSERT 0 1" 這行 command status，
# 後者會被一起吃進變數，造成下一句 SQL 語法錯誤。
psql() { docker compose --profile perf exec -T postgres-perf psql -q -U postgres -d ticket_rush_db -tA "$@"; }

echo "===== 前置：重置場次與庫存 ====="
psql -c "TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE;" >/dev/null
EVENT_ID=$(psql -c "INSERT INTO event (name, sales_start_at, total_quantity) VALUES ('壓測場次', now(), ${INITIAL_STOCK}) RETURNING id;")
psql -c "INSERT INTO stock (event_id, available) VALUES (${EVENT_ID}, ${INITIAL_STOCK});" >/dev/null
echo "場次 ${EVENT_ID}，初始庫存 ${INITIAL_STOCK}"
echo

echo "===== 執行 k6（${VUS} VU，每 VU 一次請求）====="
# --no-deps 不可省：docker compose run 會依「當前解析到的設定」比對 depends_on 的服務，
# 不一致就重建它。本腳本執行時若沒有 VIRTUAL_THREADS 環境變數，compose 會解析成 false，
# 於是把正在跑虛擬執行緒的 app 靜默替換成平台執行緒版本——
# 症狀是「設定明明改了卻沒生效」，而且沒有任何錯誤訊息。
docker compose --profile perf run --rm --no-deps -e EVENT_ID="${EVENT_ID}" -e VUS="${VUS}" k6

echo
echo "===== 後置：正確性欄位（k6 量不到，須查資料庫）====="
SOLD=$(psql -c "SELECT COALESCE(SUM(quantity),0) FROM purchase_order;")
ORDERS=$(psql -c "SELECT count(*) FROM purchase_order;")
REMAINING=$(psql -c "SELECT available FROM stock WHERE event_id = ${EVENT_ID};")
DECREASE=$((INITIAL_STOCK - REMAINING))
OVERSOLD=$((SOLD - DECREASE))

printf '訂單筆數      : %s\n累計售出張數  : %s\n初始庫存      : %s\n最終庫存      : %s\n庫存實際減少  : %s\n>>> 超賣張數  : %s\n' \
    "$ORDERS" "$SOLD" "$INITIAL_STOCK" "$REMAINING" "$DECREASE" "$OVERSOLD"

echo
echo "===== 測量條件（取自應用的啟動記錄，非設定檔）====="
docker compose --profile perf logs app 2>/dev/null | grep -A5 "執行環境" | tail -6 | sed 's/^app-1  | //'
