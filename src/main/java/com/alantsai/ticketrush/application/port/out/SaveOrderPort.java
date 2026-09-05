package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.model.Order;

/** 儲存訂單,回傳帶有資料庫產生識別碼的訂單。 */
public interface SaveOrderPort {
    Order saveOrder(Order order);
}
