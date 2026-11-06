package com.goshare.order.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.keygen.KeyGenerateStrategyConfiguration;
import org.apache.shardingsphere.single.api.config.SingleRuleConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 分库分表数据源配置（ShardingSphere-JDBC 手动装配）
 * 说明：ShardingSphere 5.3+ 不再提供独立 Spring Boot Starter（Maven 中央仓库最高 5.2.1），
 * 这里直接用官方 Java API 创建分片数据源，交给 MyBatis-Plus 使用。
 * 规则：逻辑表 trip_order 按乘客ID(passenger_id) 取模 2，路由到 trip_order_0 / trip_order_1；
 *       未参与分片的表（幂等记录、行程余座等）走默认数据源 goshare。
 */
@Configuration
public class ShardingDataSourceConfig {

    @Value("${goshare.sharding.datasource-url}")
    private String datasourceUrl;

    @Value("${goshare.sharding.datasource-username}")
    private String datasourceUsername;

    @Value("${goshare.sharding.datasource-password}")
    private String datasourcePassword;

    @Bean
    @Primary
    public DataSource shardingDataSource() throws Exception {
        // 1. 物理数据源：本服务唯一数据库（同库分表演示）
        Map<String, DataSource> dataSourceMap = new HashMap<>(1);
        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(datasourceUrl);
        hikari.setUsername(datasourceUsername);
        hikari.setPassword(datasourcePassword);
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setMaximumPoolSize(20);
        dataSourceMap.put("goshare", hikari);

        // 2. 分片规则：trip_order -> trip_order_0 / trip_order_1，按乘客ID取模
        ShardingRuleConfiguration ruleConfig = new ShardingRuleConfiguration();
        ShardingTableRuleConfiguration tableRule =
                new ShardingTableRuleConfiguration("trip_order", "goshare.trip_order_${0..1}");
        tableRule.setTableShardingStrategy(
                new StandardShardingStrategyConfiguration("passenger_id", "passengerInline"));
        ruleConfig.getTables().add(tableRule);
        // 内联取模分片算法（标准算法，表达式计算结果即物理表名）
        Properties inlineProps = new Properties();
        inlineProps.setProperty("algorithm-expression", "trip_order_${passenger_id % 2}");
        ruleConfig.getShardingAlgorithms().put("passengerInline",
                new AlgorithmConfiguration("INLINE", inlineProps));
        // 主键生成器：雪花算法（分片表无自增，订单ID由分片引擎生成）
        Properties snowProps = new Properties();
        ruleConfig.getKeyGenerators().put("snowflake",
                new AlgorithmConfiguration("SNOWFLAKE", snowProps));
        tableRule.setKeyGenerateStrategy(
                new KeyGenerateStrategyConfiguration("id", "snowflake"));

        // 未参与分片的表（幂等记录、行程余座表、区间库存、退款单等）显式声明，全部走默认数据源 goshare
        SingleRuleConfiguration singleRule = new SingleRuleConfiguration(
                java.util.List.of("goshare.idempotency_key", "goshare.trip",
                        "goshare.trip_segment", "goshare.refund_order", "goshare.refund_transaction",
                        "goshare.order_review"), "goshare");

        // 3. 全局属性：控制台打印真实执行 SQL（方便演示路由结果）
        Properties props = new Properties();
        props.setProperty("sql-show", "true");

        return ShardingSphereDataSourceFactory.createDataSource(
                dataSourceMap, java.util.List.of(ruleConfig, singleRule), props);
    }
}
