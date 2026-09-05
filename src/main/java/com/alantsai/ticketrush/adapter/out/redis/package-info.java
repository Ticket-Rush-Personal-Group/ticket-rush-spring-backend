/**
 * 出站 Redis adapter:庫存預扣與對帳。
 *
 * <p>扣減以 Lua 腳本保證原子性;扣減成功但落庫失敗需有補償機制。
 */
package com.alantsai.ticketrush.adapter.out.redis;
