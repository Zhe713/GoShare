package com.goshare.assistant.es;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行程 ES 文档（assistant 侧只读复用 trip 服务索引 goshare_trip，结构保持一致）
 */
@Data
@Document(indexName = "goshare_trip")
public class EsTripHit {

    @Id
    private Long id;

    private Long driverId;

    @Field(type = FieldType.Text)
    private String fromCity;

    @Field(type = FieldType.Text)
    private String toCity;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime departureTime;

    private Integer totalSeats;

    private Integer remainSeats;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    private Integer status;
}
