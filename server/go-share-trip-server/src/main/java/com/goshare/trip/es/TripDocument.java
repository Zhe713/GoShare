package com.goshare.trip.es;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行程 ES 文档（用于搜索引擎检索，余座实时性以数据库为准）
 */
@Data
@Document(indexName = "goshare_trip")
public class TripDocument {

    @Id
    private Long id;

    /** 车主用户ID */
    private Long driverId;

    /** 出发城市 */
    @Field(type = FieldType.Text)
    private String fromCity;

    /** 到达城市 */
    @Field(type = FieldType.Text)
    private String toCity;

    /** 出发时间（显式指定秒级时间格式，避免被截断成日期） */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime departureTime;

    /** 总座位数 */
    private Integer totalSeats;

    /** 剩余座位数（快照，实时值以数据库为准） */
    private Integer remainSeats;

    /** 每人价格（元） */
    @Field(type = FieldType.Double)
    private BigDecimal price;

    /** 行程状态 0待出发 1进行中 2已完成 3已取消 */
    private Integer status;
}
