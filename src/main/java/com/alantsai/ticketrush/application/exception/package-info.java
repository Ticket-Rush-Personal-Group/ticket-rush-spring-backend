/**
 * 應用層例外:策略機制本身的失敗,而非領域規則的違反。
 *
 * <p><b>與 {@code domain.exception} 的分界在於「domain 該不該知道」。</b> 庫存不足與超過限購是領域規則,
 * 任何策略下都成立;重試耗盡則只在樂觀鎖存在 —— 把它放進 domain 會讓領域層知道當前用的是哪一種鎖,
 * 而「四種策略切換、domain 零改動」正是靠 domain 對此一無所知才成立的。
 */
package com.alantsai.ticketrush.application.exception;
