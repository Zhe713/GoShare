package com.goshare.order.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.common.security.InternalApi;
import com.goshare.order.dto.OrderCancelRequest;
import com.goshare.order.dto.OrderCreateRequest;
import com.goshare.order.dto.OrderVO;
import com.goshare.order.dto.PayCallbackRequest;
import com.goshare.order.dto.WaitlistRequest;
import com.goshare.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单接口：下单、取消、支付回调、查询
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final CurrentUserResolver currentUserResolver;

    /** 下单占座 */
    @PostMapping("/create")
    public Result<OrderVO> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody @Valid OrderCreateRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.create(passengerId, request));
    }

    /** 取消订单 */
    @PostMapping("/cancel")
    public Result<OrderVO> cancel(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody @Valid OrderCancelRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.cancel(passengerId, request.getOrderNo()));
    }

    /** 取消挽留预览（A2 费用解释：按时间窗判责，违约金/退款由 FeeCalculator 纯 Java 计算） */
    @PostMapping("/cancel-preview")
    public Result<com.goshare.order.util.FeeCalculator.FeeBreakdown> cancelPreview(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody @Valid OrderCancelRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.cancelPreview(passengerId, request.getOrderNo()));
    }

    /**
     * 支付成功回调。
     *
     * <p>标 {@link InternalApi}：这是支付服务发给订单服务的内部命令，不是用户 API。
     * 之前它和普通接口混在一起，任何登录用户拿自己的 JWT 就能把订单改成 PAID（实测可白嫖）。
     * 现在网关会挡掉公网访问，服务端还要校验内部令牌。
     */
    @InternalApi
    @PostMapping("/pay-callback")
    public Result<OrderVO> payCallback(@RequestBody @Valid PayCallbackRequest request) {
        return Result.success(orderService.handlePayCallback(request.getOrderNo(), request.getPaymentNo()));
    }

    /** 订单详情（仅本人可查，防止遍历单号读他人订单） */
    @GetMapping("/{orderNo}")
    public Result<OrderVO> detail(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @PathVariable("orderNo") String orderNo) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.getByOrderNo(passengerId, orderNo));
    }

    /**
     * 内部取消挽留预览（AI 助手调用）。
     *
     * <p>带可选 {@code passengerId}：助手会传当前登录用户，订单服务据此校验归属，
     * 避免"用 AI 助手查别人的订单要扣多少钱"。
     */
    @InternalApi
    @PostMapping("/internal/cancel-preview")
    public Result<com.goshare.order.util.FeeCalculator.FeeBreakdown> internalCancelPreview(
            @RequestParam(value = "passengerId", required = false) Long passengerId,
            @RequestBody @Valid OrderCancelRequest request) {
        return Result.success(orderService.cancelPreview(passengerId, request.getOrderNo()));
    }

    /**
     * 内部订单查询（支付服务发起扣款前取金额与状态用）。
     *
     * <p>和上面的用户接口分开：它不做归属校验（调用方可传 passengerId 要求校验），
     * 但只接受内部令牌，否则支付服务要么拿不到订单，要么只能伪造一个用户身份去查。
     */
    @InternalApi
    @GetMapping("/internal/{orderNo}")
    public Result<OrderVO> internalDetail(@PathVariable("orderNo") String orderNo,
                                          @RequestParam(value = "passengerId", required = false) Long passengerId) {
        return Result.success(orderService.getByOrderNo(passengerId, orderNo));
    }

    /** 我的订单 */
    @GetMapping("/mine")
    public Result<List<OrderVO>> mine(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.listByPassenger(passengerId));
    }

    /** 候补登记：行程满座时排队等座位 */
    @PostMapping("/waitlist")
    public Result<Void> joinWaitlist(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody @Valid WaitlistRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        orderService.joinWaitlist(passengerId, request.getTripId());
        return Result.success(null);
    }

    /** 退出候补 */
    @PostMapping("/waitlist/leave")
    public Result<Void> leaveWaitlist(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody @Valid WaitlistRequest request) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        orderService.leaveWaitlist(passengerId, request.getTripId());
        return Result.success(null);
    }

    /** 车主视角：某行程的乘客名单（阶段1新增） */
    @GetMapping("/by-trip/{tripId}")
    public Result<List<OrderVO>> listByTrip(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable("tripId") Long tripId) {
        Long driverId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.listByTrip(driverId, tripId));
    }

    /** 车主确认乘客上车（阶段1履约闭环新增） */
    @PostMapping("/board")
    public Result<OrderVO> board(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestParam("tripId") Long tripId,
                                 @RequestParam("orderNo") String orderNo) {
        Long driverId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.board(driverId, tripId, orderNo));
    }

    /** 车主确认到达（阶段1履约闭环新增） */
    @PostMapping("/complete")
    public Result<OrderVO> complete(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam("tripId") Long tripId,
                                    @RequestParam("orderNo") String orderNo) {
        Long driverId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.complete(driverId, tripId, orderNo));
    }

    /** 乘客评价（阶段1履约闭环新增） */
    @PostMapping("/review")
    public Result<OrderVO> review(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody Map<String, Object> body) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        String orderNo = String.valueOf(body.get("orderNo"));
        int rating = Integer.parseInt(String.valueOf(body.get("rating")));
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        return Result.success(orderService.review(passengerId, orderNo, rating, comment));
    }

    /** 线路价格统计（F3 价格参谋：同线路近 N 天均价/最低/中位数，阶段3 AI 扩展） */
    @GetMapping("/price-stats")
    public Result<com.goshare.order.dto.PriceStatsVO> priceStats(
            @RequestParam("fromCity") String fromCity,
            @RequestParam("toCity") String toCity,
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return Result.success(orderService.priceStats(fromCity, toCity, days));
    }

    /** 行程账单（F5：按乘客月度聚合，数据全部来自真实订单表；month 格式 yyyy-MM，缺省当月） */
    @GetMapping("/bill")
    public Result<com.goshare.order.dto.TripBillVO> bill(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(value = "month", required = false) String month) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        return Result.success(orderService.tripBill(passengerId, month));
    }

    /** 改签（F4：PAID 订单改签到同线路新班次，改签免费、差额自动退） */
    @PostMapping("/reschedule")
    public Result<OrderVO> reschedule(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody Map<String, Object> body) {
        Long passengerId = currentUserResolver.resolveUserId(auth);
        String orderNo = String.valueOf(body.get("orderNo"));
        Long newTripId = Long.valueOf(String.valueOf(body.get("newTripId")));
        Integer fromSeq = body.get("fromStopSeq") == null ? null : Integer.valueOf(String.valueOf(body.get("fromStopSeq")));
        Integer toSeq = body.get("toStopSeq") == null ? null : Integer.valueOf(String.valueOf(body.get("toStopSeq")));
        return Result.success(orderService.reschedule(passengerId, orderNo, newTripId, fromSeq, toSeq));
    }
}
