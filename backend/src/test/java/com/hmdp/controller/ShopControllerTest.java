package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class ShopControllerTest {
    @Test
    void creationUsesSynchronizationEntryInsteadOfBareSave() {
        var controller = new ShopController();
        controller.shopService = mock(IShopService.class);
        var shop = new Shop();
        var result = Result.ok(42L);
        when(controller.shopService.createShop(shop)).thenReturn(result);
        assertSame(result, controller.saveShop(shop));
        verify(controller.shopService).createShop(shop);
        verify(controller.shopService, never()).save(shop);
    }
}
