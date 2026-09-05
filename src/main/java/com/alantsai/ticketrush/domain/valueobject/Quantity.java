package com.alantsai.ticketrush.domain.valueobject;

/** 票券張數。零與負數在領域上沒有意義,於建構時即拒絕。 */
public record Quantity(int value) {
    public Quantity {
        if (value <= 0) {
            throw new IllegalArgumentException("張數必須大於 0,實際為 " + value);
        }
    }
}
