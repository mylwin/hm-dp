package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BlogServiceImplTest {

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    @Test
    void likeUsesWatchdogLockInsteadOfFixedLease() throws Exception {
        BlogServiceImpl service = new BlogServiceImpl();
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(false);

        UserDTO user = new UserDTO();
        user.setId(1L);
        UserContext.saveUser(user);

        Result result = service.likeBlog(10L);

        assertFalse(result.getSuccess());
        verify(lock).tryLock(1, TimeUnit.SECONDS);
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
    }
}
