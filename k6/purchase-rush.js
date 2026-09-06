import http from 'k6/http';
import { check } from 'k6';

// 搶票的本質是瞬間爆發，不是穩態負載。
// 刻意不用「固定 VU 持續 N 秒」的常見壓測模式——那會在庫存賣完後
// 變成「量測 409 回應的吞吐」，而那不是四層策略要比較的東西。
const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const EVENT_ID = __ENV.EVENT_ID || '1';
const VUS = parseInt(__ENV.VUS || '1000', 10);
// 每個 VU 送幾次請求。**這是量測窗口的長度旋鈕。**
//
// 原本是 1：1000 個請求在約 1.2 秒內打完。加上暖機之後系統快了三倍，
// 窗口縮到 0.4 秒——一次 200ms 的 GC 停頓就能砍掉一半吞吐，
// 而悲觀鎖（原本最穩定的一層）的全距因此從 0.8% 變成 75.7%。
//
// **放大的是爆發的規模，不是把爆發改成穩態。** 庫存與請求數同步放大，
// 競爭仍然集中在同一列庫存上，策略之間的比較性質不變。
const ITERATIONS = parseInt(__ENV.ITERATIONS || '50', 10);

export const options = {
    scenarios: {
        rush: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: ITERATIONS,
            maxDuration: '5m',
        },
    },
    // strategy-* 的驗收要求 P50 / P95 / P99，而 k6 預設只輸出 p(90) 與 p(95)
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
    // 刻意不設 thresholds。本腳本的目的是取得數據，不是判定通過與否——
    // 第 0 層必然大量「成功」（那正是問題），設門檻只會讓它紅得沒有意義。
};

export default function () {
    // **使用者與冪等鍵都必須每請求唯一。**
    //
    // 冪等鍵重複 → 量到的是「重複請求的拒絕率」而非併發控制的效果。
    // 使用者重複 → 同一個 VU 送第 5 次請求時就撞上限購上限 4，
    //             之後每一次都是限購拒絕——量到的變成限購的吞吐。
    // 後者在「每個 VU 只送一次」時不存在，放大 iteration 之後就成了必須處理的事。
    const requestId = `${__VU}-${__ITER}`;
    const payload = JSON.stringify({
        quantity: 1,
        idempotencyKey: `k6-${requestId}`,
    });

    const res = http.post(`${BASE_URL}/api/events/${EVENT_ID}/purchase`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'X-User-Id': String(__VU * 100000 + __ITER),
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
