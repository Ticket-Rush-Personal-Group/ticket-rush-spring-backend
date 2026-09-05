/**
 * 領域政策:跨聚合的業務規則,例如單人限購上限。
 *
 * <p>四個併發策略共用同一份政策實作,以組合注入而非繼承 —— 各策略的流程骨架不同,
 * 以繼承綁定會使骨架充斥 hook 與條件分支。
 */
package com.alantsai.ticketrush.domain.policy;
