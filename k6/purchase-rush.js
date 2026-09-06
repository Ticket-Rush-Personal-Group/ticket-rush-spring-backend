import http from 'k6/http';
import { check } from 'k6';

// 搶票的本質是瞬間爆發，不是穩態負載。
// 刻意不用「固定 VU 持續 N 秒」的常見壓測模式——那會在庫存賣完後
// 變成「量測 409 回應的吞吐」，而那不是四層策略要比較的東西。
const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const EVENT_ID = __ENV.EVENT_ID || '1';
const VUS = parseInt(__ENV.VUS || '1000', 10);

export const options = {
    scenarios: {
        rush: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '2m',
        },
    },
    // strategy-* 的驗收要求 P50 / P95 / P99，而 k6 預設只輸出 p(90) 與 p(95)
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
    // 刻意不設 thresholds。本腳本的目的是取得數據，不是判定通過與否——
    // 第 0 層必然大量「成功」（那正是問題），設門檻只會讓它紅得沒有意義。
};

export default function () {
    const payload = JSON.stringify({
        quantity: 1,
        // 每個 VU 一次 iteration，__VU 即可保證唯一。
        // 撞到冪等鍵約束會讓我們量到「重複請求的拒絕率」而非併發控制的效果。
        idempotencyKey: `k6-vu-${__VU}`,
    });

    const res = http.post(`${BASE_URL}/api/events/${EVENT_ID}/purchase`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'X-User-Id': String(__VU),
        },
    });

    // 成功的狀態碼依策略而異：同步落庫的三層回 201 Created，
    // Redis 預扣回 202 Accepted——回應的當下訂單確實還不存在。
    // 只檢查 201 的話，第 3 層的成功率會顯示成 0，那是量測工具的錯，不是系統的。
    check(res, {
        accepted: (r) => r.status === 201 || r.status === 202,
        rejected: (r) => r.status === 409,
    });
}
