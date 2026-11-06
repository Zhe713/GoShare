package com.goshare.notify.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.notify.entity.NotifyMessage;
import com.goshare.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知接口（阶段2「通知服务重定位」）
 */
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotifyMessageController {

    private final NotifyService notifyService;
    private final CurrentUserResolver currentUserResolver;

    /** 未读消息列表 */
    @GetMapping("/messages/unread")
    public Result<List<NotifyMessage>> unread(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = currentUserResolver.resolveUserId(auth);
        return Result.success(notifyService.listUnread(userId));
    }

    /** 全部消息列表 */
    @GetMapping("/messages")
    public Result<List<NotifyMessage>> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = currentUserResolver.resolveUserId(auth);
        return Result.success(notifyService.listAll(userId));
    }

    /** 未读数量 */
    @GetMapping("/messages/unread-count")
    public Result<Map<String, Object>> unreadCount(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = currentUserResolver.resolveUserId(auth);
        Map<String, Object> data = new HashMap<>();
        data.put("count", notifyService.unreadCount(userId));
        return Result.success(data);
    }

    /** 标记单条已读 */
    @PostMapping("/messages/{id}/read")
    public Result<Void> markRead(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @PathVariable("id") Long id) {
        Long userId = currentUserResolver.resolveUserId(auth);
        notifyService.markRead(userId, id);
        return Result.success(null);
    }

    /** 全部标记已读 */
    @PostMapping("/messages/read-all")
    public Result<Void> markAllRead(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = currentUserResolver.resolveUserId(auth);
        notifyService.markAllRead(userId);
        return Result.success(null);
    }
}
