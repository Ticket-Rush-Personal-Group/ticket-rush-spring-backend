-- 第 3 層(Redis 預扣)的原子預扣:限購檢查、庫存檢查、扣減,三件事一起完成。
--
-- KEYS[1] = 庫存             KEYS[2] = 該使用者在該場次的已購數
-- ARGV[1] = 本次購買張數     ARGV[2] = 單人限購上限
--
-- 回傳: 1 成功 / -1 超過限購 / -2 庫存不足 / -3 場次未開賣
--
-- **限購檢查必須留在這支腳本內。** 若移到應用端(讀 Redis → 判斷 → 扣),那就是
-- check-then-act:兩個併發請求讀到相同的已購數、各自通過檢查 ——
-- 那正是第 0 層的缺陷,只是換一個儲存體重演一次。
-- Redis 的 Lua 是單執行緒原子執行的,兩個檢查寫在同一支腳本內才都受保護。
--
-- 檢查順序為「先限購、後庫存」,與前三層一致(第 5 支已定調:被限購擋下的請求
-- 不該再去競爭庫存)。四層的檢查順序必須相同,否則同一組輸入在不同層會得到不同的錯誤碼。
--
-- 單節點假設:兩個 key 未加 hash tag。Redis Cluster 下同一支腳本的 key 必須落在同一個 slot,
-- 屆時要改用 hash tag。高可用與 Cluster 是 Phase 4 的議題,見本 change 的 Non-Goals。

local stock = redis.call('GET', KEYS[1])

-- 缺 key 是「尚未開賣」,不是「庫存無限」。
-- **把「不知道」當成「可以」是超賣最廉價的來源。**
if not stock then
  return -3
end

local quantity = tonumber(ARGV[1])
local purchased = tonumber(redis.call('GET', KEYS[2]) or '0')

if purchased + quantity > tonumber(ARGV[2]) then
  return -1
end

if tonumber(stock) < quantity then
  return -2
end

redis.call('DECRBY', KEYS[1], quantity)
redis.call('INCRBY', KEYS[2], quantity)
return 1
